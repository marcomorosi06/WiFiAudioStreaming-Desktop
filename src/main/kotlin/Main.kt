import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import javax.sound.sampled.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Maximize
import androidx.compose.material.icons.filled.Restore
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

enum class Theme { Light, Dark, System }
data class AppSettings(
    val theme: Theme = Theme.System,
    val experimentalFeaturesEnabled: Boolean = false
)

data class ServerInfo(val ip: String, val isMulticast: Boolean, val port: Int)
data class AudioSettings_V1(
    val sampleRate: Float,
    val bitDepth: Int,
    val channels: Int,
    val bufferSize: Int
) {
    fun toAudioFormat(): AudioFormat {
        return AudioFormat(sampleRate, bitDepth, channels, true, false)
    }
}

// --- Sealed class for the virtual audio driver status ---
sealed class VirtualDriverStatus {
    object Ok : VirtualDriverStatus()
    data class Missing(val driverName: String, val downloadUrl: String) : VirtualDriverStatus()
}

object NetworkHandler_v1 {
    fun getLocalIpAddress(): String {
        return try {
            java.net.DatagramSocket().use { socket ->
                socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 10002)
                socket.localAddress.hostAddress
            }
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamingJob: Job? = null
    private var listeningJob: Job? = null
    private var broadcastingJob: Job? = null
    private var serverGrabber: org.bytedeco.javacv.FFmpegFrameGrabber? = null
    private var micReceiverJob: Job? = null

    private const val DISCOVERY_PORT = 9091
    private const val CLIENT_HELLO_MESSAGE = "HELLO_FROM_CLIENT"
    private const val MULTICAST_GROUP_IP = "239.255.0.1"
    private const val DISCOVERY_MESSAGE = "WIFI_AUDIO_STREAMER_DISCOVERY"

    fun findAvailableOutputMixers(): List<Mixer.Info> {
        return AudioSystem.getMixerInfo()
            .filter { mixerInfo ->
                !mixerInfo.name.startsWith("Port", ignoreCase = true) &&
                        AudioSystem.getMixer(mixerInfo).isLineSupported(Line.Info(SourceDataLine::class.java))
            }
    }

    fun findAvailableInputMixers(): List<Mixer.Info> {
        return AudioSystem.getMixerInfo()
            .filter { mixerInfo ->
                !mixerInfo.name.startsWith("Port", ignoreCase = true) &&
                        AudioSystem.getMixer(mixerInfo).isLineSupported(Line.Info(TargetDataLine::class.java))
            }
    }

    /**
     * Checks whether the required virtual audio driver is installed for the current OS.
     * Returns VirtualDriverStatus.Ok if found, VirtualDriverStatus.Missing with name and download URL otherwise.
     */
    fun checkVirtualDriverStatus(): VirtualDriverStatus {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val driverName = "CABLE Output (VB-Audio Virtual Cable)"
                val isInstalled = AudioSystem.getMixerInfo().any {
                    it.name.contains("CABLE Output", ignoreCase = true)
                }
                if (isInstalled) VirtualDriverStatus.Ok
                else VirtualDriverStatus.Missing(
                    driverName = "VB-Audio Virtual Cable",
                    downloadUrl = "https://vb-audio.com/Cable/"
                )
            }
            os.contains("mac") -> {
                val isInstalled = AudioSystem.getMixerInfo().any {
                    it.name.contains("BlackHole", ignoreCase = true)
                }
                if (isInstalled) VirtualDriverStatus.Ok
                else VirtualDriverStatus.Missing(
                    driverName = "BlackHole 2ch",
                    downloadUrl = "https://existential.audio/blackhole/"
                )
            }
            else -> {
                // On Linux/PulseAudio, we assume it's available (no easy install check)
                VirtualDriverStatus.Ok
            }
        }
    }

    fun beginDeviceDiscovery(onDeviceFound: (hostname: String, serverInfo: ServerInfo) -> Unit) {
        if (listeningJob?.isActive == true) return
        listeningJob = scope.launch {
            var socket: MulticastSocket? = null
            try {
                val localIps = NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .map { it.hostAddress }
                    .toSet()

                val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                socket = MulticastSocket(DISCOVERY_PORT).apply {
                    joinGroup(groupAddress)
                    soTimeout = 5000
                }

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        socket.receive(packet)
                        val remoteIp = packet.address.hostAddress
                        val message = String(packet.data, 0, packet.length).trim()

                        if (remoteIp != null && remoteIp !in localIps && message.startsWith(DISCOVERY_MESSAGE)) {
                            val parts = message.split(";")
                            if (parts.size == 4) {
                                val hostname = parts[1]
                                val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                                val port = parts[3].toIntOrNull() ?: continue
                                onDeviceFound(hostname, ServerInfo(remoteIp, isMulticast, port))
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) println("Listening Error: ${e.message}")
            } finally {
                try {
                    socket?.leaveGroup(InetAddress.getByName(MULTICAST_GROUP_IP))
                } catch (_: Exception) {}
                socket?.close()
            }
        }
    }

    fun endDeviceDiscovery() {
        listeningJob?.cancel()
    }

    // FIX BUG #2: Use MulticastSocket (not DatagramSocket) with TTL set,
    // so the announcement packets are correctly routed on the local network.
    fun startAnnouncingPresence(isMulticast: Boolean, port: Int) {
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch {
            val hostname = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "Desktop-PC" }
            val mode = if (isMulticast) "MULTICAST" else "UNICAST"
            val message = "$DISCOVERY_MESSAGE;$hostname;$mode;$port"
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)

            // Use MulticastSocket so the OS routes multicast packets correctly
            MulticastSocket().use { socket ->
                socket.timeToLive = 4 // Enough for local network segments
                while (isActive) {
                    try {
                        val bytes = message.toByteArray()
                        val packet = DatagramPacket(bytes, bytes.size, groupAddress, DISCOVERY_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        if (e !is CancellationException) println("Announcing error: ${e.message}")
                    }
                    delay(3000)
                }
            }
        }
    }

    fun stopAnnouncingPresence() {
        broadcastingJob?.cancel()
    }

    private fun CoroutineScope.launchMicReceiver(
        audioSettings: AudioSettings_V1, isMulticast: Boolean, micOutputMixerInfo: Mixer.Info, micPort: Int
    ) = launch {
        var socket: DatagramSocket? = null
        val micMixer = AudioSystem.getMixer(micOutputMixerInfo)
        val format = audioSettings.toAudioFormat()
        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
        if (!micMixer.isLineSupported(lineInfo)) return@launch

        val micOutputLine = micMixer.getLine(lineInfo) as SourceDataLine
        micOutputLine.open(format, audioSettings.bufferSize * 4)
        micOutputLine.start()

        try {
            val buffer = ByteArray(audioSettings.bufferSize * 2)
            val packet = DatagramPacket(buffer, buffer.size)

            socket = if (isMulticast) {
                MulticastSocket(micPort).apply { joinGroup(InetAddress.getByName(MULTICAST_GROUP_IP)) }
            } else {
                DatagramSocket(micPort)
            }

            while (isActive) {
                socket.receive(packet)
                if (packet.length > 0) micOutputLine.write(packet.data, 0, packet.length)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("Mic receiving error: ${e.message}")
        } finally {
            socket?.close()
            micOutputLine.drain(); micOutputLine.stop(); micOutputLine.close()
        }
    }

    private fun CoroutineScope.launchMicSender(
        audioSettings: AudioSettings_V1, serverInfo: ServerInfo, micInputMixerInfo: Mixer.Info, micPort: Int
    ) = launch {
        var socket: DatagramSocket? = null
        val micMixer = AudioSystem.getMixer(micInputMixerInfo)
        val format = audioSettings.toAudioFormat()
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
        if (!micMixer.isLineSupported(lineInfo)) return@launch

        val micInputLine = micMixer.getLine(lineInfo) as TargetDataLine
        micInputLine.open(format, audioSettings.bufferSize)
        micInputLine.start()

        try {
            socket = DatagramSocket()
            val destinationAddress = if (serverInfo.isMulticast) {
                InetAddress.getByName(MULTICAST_GROUP_IP)
            } else {
                InetAddress.getByName(serverInfo.ip)
            }
            val buffer = ByteArray(audioSettings.bufferSize)
            while (isActive) {
                val bytesRead = micInputLine.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val packet = DatagramPacket(buffer, bytesRead, destinationAddress, micPort)
                    socket.send(packet)
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("Mic sending error: ${e.message}")
        } finally {
            socket?.close()
            micInputLine.stop(); micInputLine.close()
        }
    }

    fun launchServerInstance(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        if (micOutputMixerInfo != null) {
            micReceiverJob = scope.launchMicReceiver(audioSettings, isMulticast, micOutputMixerInfo, micPort)
        }
        startAnnouncingPresence(isMulticast, port)

        streamingJob = scope.launch {
            try {
                val os = System.getProperty("os.name").lowercase()

                val grabberFormat: String
                val deviceName: String

                when {
                    os.contains("win") -> {
                        grabberFormat = "dshow"
                        deviceName = "audio=CABLE Output (VB-Audio Virtual Cable)"
                    }
                    os.contains("mac") -> {
                        grabberFormat = "avfoundation"
                        deviceName = "audio=BlackHole 2ch"
                    }
                    else -> {
                        grabberFormat = "pulse"
                        deviceName = "default"
                    }
                }

                serverGrabber = org.bytedeco.javacv.FFmpegFrameGrabber(deviceName).apply {
                    setFormat(grabberFormat)
                    sampleRate = audioSettings.sampleRate.toInt()
                    audioChannels = audioSettings.channels
                    sampleFormat = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                }

                // FIX BUG #1: The "driver missing" error is now surfaced via a specific
                // status key so the UI can show a proper banner with a download button.
                try {
                    serverGrabber?.start()
                    println("--- FFMPEG started successfully ---")
                } catch (e: Exception) {
                    // Signal to the UI that the driver is missing, not just a generic error
                    onStatusUpdate("error_virtual_driver_missing", emptyArray())
                    return@launch
                }

                if (isMulticast) {
                    onStatusUpdate("Streaming Multicast on Port %d...", arrayOf(port))

                    MulticastSocket().use { socket ->
                        socket.timeToLive = 4
                        val group = InetAddress.getByName(MULTICAST_GROUP_IP)

                        val maxBytesPerPacket = audioSettings.bufferSize
                        val maxShortsPerPacket = maxBytesPerPacket / 2
                        val chunkArray = ShortArray(maxShortsPerPacket)
                        val byteBuffer = java.nio.ByteBuffer.allocate(maxBytesPerPacket).apply {
                            order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        }

                        while (isActive) {
                            val frame = serverGrabber?.grabSamples()
                            if (frame != null && frame.samples != null) {
                                val shortBuffer = frame.samples[0] as java.nio.ShortBuffer
                                shortBuffer.position(0)

                                while (shortBuffer.hasRemaining()) {
                                    val shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
                                    shortBuffer.get(chunkArray, 0, shortsToRead)

                                    byteBuffer.clear()
                                    byteBuffer.asShortBuffer().put(chunkArray, 0, shortsToRead)
                                    val bytesToSend = shortsToRead * 2

                                    socket.send(DatagramPacket(byteBuffer.array(), bytesToSend, group, port))
                                }
                            }
                        }
                    }
                } else { // Unicast
                    onStatusUpdate("Waiting for Unicast Client on Port %d...", arrayOf(port))

                    val localAddress = InetSocketAddress("0.0.0.0", port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        val clientDatagram = socket.receive()
                        if (clientDatagram.packet.readText().trim() == CLIENT_HELLO_MESSAGE) {
                            val clientAddress = clientDatagram.address
                            onStatusUpdate("Client Connected: %s", arrayOf(clientAddress.toString()))
                            stopAnnouncingPresence()
                            socket.send(Datagram(buildPacket { writeText("HELLO_ACK") }, clientAddress))

                            val maxBytesPerPacket = audioSettings.bufferSize
                            val maxShortsPerPacket = maxBytesPerPacket / 2
                            val chunkArray = ShortArray(maxShortsPerPacket)
                            val byteBuffer = java.nio.ByteBuffer.allocate(maxBytesPerPacket).apply {
                                order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            }

                            while (isActive) {
                                val frame = serverGrabber?.grabSamples()
                                if (frame != null && frame.samples != null) {
                                    val shortBuffer = frame.samples[0] as java.nio.ShortBuffer
                                    shortBuffer.position(0)

                                    while (shortBuffer.hasRemaining()) {
                                        val shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
                                        shortBuffer.get(chunkArray, 0, shortsToRead)

                                        byteBuffer.clear()
                                        byteBuffer.asShortBuffer().put(chunkArray, 0, shortsToRead)
                                        val bytesToSend = shortsToRead * 2

                                        val packet = buildPacket { writeFully(byteBuffer.array(), 0, bytesToSend) }
                                        socket.send(Datagram(packet, clientAddress))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    t.printStackTrace()
                    onStatusUpdate("CRITICAL ERROR: %s", arrayOf(t.message ?: t.toString()))
                }
            } finally {
                stopAnnouncingPresence()
                serverGrabber?.stop()
                serverGrabber?.release()
                serverGrabber = null
            }
        }
    }

    fun launchClientInstance(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        selectedMixerInfo: Mixer.Info,
        sendMicrophone: Boolean,
        micInputMixerInfo: Mixer.Info?,
        micPort: Int,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        if (sendMicrophone && micInputMixerInfo != null) {
            // Note: launchMicSender, not launchMicReceiver — this is the client sending mic to server
            micReceiverJob = scope.launchMicSender(audioSettings, serverInfo, micInputMixerInfo, micPort)
        }
        streamingJob = scope.launch {
            var sourceDataLine: SourceDataLine? = null
            try {
                if (!serverInfo.isMulticast) { // Unicast
                    val remoteAddress = InetSocketAddress(serverInfo.ip, serverInfo.port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind().use { socket ->
                        onStatusUpdate("status_contacting_server", arrayOf(remoteAddress))
                        val helloPacket = buildPacket { writeText(CLIENT_HELLO_MESSAGE) }
                        socket.send(Datagram(helloPacket, remoteAddress))

                        onStatusUpdate("status_waiting_ack", emptyArray())
                        // FIX BUG #3: Increased timeout from 5s to 15s to give FFmpeg time to start
                        val ackDatagram = withTimeout(15000) { socket.receive() }
                        if (ackDatagram.packet.readText().trim() != "HELLO_ACK") {
                            onStatusUpdate("status_handshake_failed", emptyArray())
                            return@use
                        }

                        onStatusUpdate("status_connected_streaming_from", arrayOf(remoteAddress))
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        val buffer = ByteArray(audioSettings.bufferSize * 2)
                        while (isActive) {
                            val datagram = socket.receive()
                            val bytesRead = datagram.packet.readAvailable(buffer)
                            if (bytesRead > 0) sourceDataLine?.write(buffer, 0, bytesRead)
                        }
                    }
                } else { // Multicast
                    onStatusUpdate("status_joining_multicast", arrayOf(serverInfo.port))
                    val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                    MulticastSocket(serverInfo.port).use { socket ->
                        socket.joinGroup(groupAddress)
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        onStatusUpdate("status_multicast_streaming", arrayOf(serverInfo.port))
                        val buffer = ByteArray(audioSettings.bufferSize * 2)
                        val packet = DatagramPacket(buffer, buffer.size)
                        while (isActive) {
                            socket.receive(packet)
                            if (packet.length > 0) sourceDataLine?.write(packet.data, 0, packet.length)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                onStatusUpdate("status_server_no_response", emptyArray())
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    e.printStackTrace()
                    onStatusUpdate("Error: %s", arrayOf(e.message ?: e.toString()))
                }
            } finally {
                // FIX BUG #4: Don't touch serverGrabber in client mode — it's always null here
                // and releasing it would cause confusion if a server was somehow running.
                sourceDataLine?.drain()
                sourceDataLine?.stop()
                sourceDataLine?.close()
            }
        }
    }

    private fun prepareSourceDataLine(mixerInfo: Mixer.Info, audioSettings: AudioSettings_V1): SourceDataLine? {
        val mixer = AudioSystem.getMixer(mixerInfo)
        val format = audioSettings.toAudioFormat()
        val dataLineInfo = DataLine.Info(SourceDataLine::class.java, format)
        if (!mixer.isLineSupported(dataLineInfo)) return null

        val frameSize = format.frameSize
        val adjustedBufferSize = (audioSettings.bufferSize / frameSize) * frameSize
        val sourceDataLine = mixer.getLine(dataLineInfo) as SourceDataLine
        sourceDataLine.open(format, adjustedBufferSize * 4)
        return sourceDataLine
    }

    suspend fun stopCurrentStream() {
        stopAnnouncingPresence()
        streamingJob?.cancelAndJoin()
        micReceiverJob?.cancelAndJoin()

        serverGrabber?.stop()
        serverGrabber?.release()
        serverGrabber = null
        streamingJob = null
        micReceiverJob = null
    }

    fun terminateAllServices() {
        scope.cancel()
    }
}

fun main() = application {
    System.setProperty("java.net.preferIPv4Stack", "true")
    org.bytedeco.javacv.FFmpegLogCallback.set()
    org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all()
    val loadedSettings = SettingsRepository.loadSettings()
    var appSettings by remember { mutableStateOf(loadedSettings.app) }
    var audioSettings by remember { mutableStateOf(loadedSettings.audio) }
    var streamingPort by remember { mutableStateOf(loadedSettings.streamingPort) }
    var micPort by remember { mutableStateOf(loadedSettings.micPort) }

    LaunchedEffect(appSettings, audioSettings, streamingPort, micPort) {
        SettingsRepository.saveSettings(AllSettings(appSettings, audioSettings, streamingPort, micPort))
    }

    var showSettings by remember { mutableStateOf(false) }
    var isServer by remember { mutableStateOf(true) }
    val discoveredDevices = remember { mutableStateMapOf<String, ServerInfo>() }
    var connectionStatus by remember { mutableStateOf(Strings.get("status_inactive")) }
    var isStreaming by remember { mutableStateOf(false) }
    // State for the virtual driver banner — checked once and shown until dismissed
    var virtualDriverStatus by remember { mutableStateOf<VirtualDriverStatus>(VirtualDriverStatus.Ok) }
    val scope = rememberCoroutineScope()

    val outputDevices = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedOutputDevice by remember { mutableStateOf<Mixer.Info?>(null) }
    val inputDevices = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedInputDevice by remember { mutableStateOf<Mixer.Info?>(null) }
    var sendMicrophone by remember { mutableStateOf(false) }
    var selectedClientMic by remember { mutableStateOf<Mixer.Info?>(null) }
    var selectedServerMicOutput by remember { mutableStateOf<Mixer.Info?>(null) }
    var isMulticastMode by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        outputDevices.value = NetworkHandler_v1.findAvailableOutputMixers()
        inputDevices.value = NetworkHandler_v1.findAvailableInputMixers()
        selectedOutputDevice = outputDevices.value.firstOrNull()
        selectedInputDevice = inputDevices.value.firstOrNull()
        selectedClientMic = inputDevices.value.firstOrNull()
        selectedServerMicOutput = outputDevices.value.find { it.name.contains("CABLE Input", ignoreCase = true) }
            ?: outputDevices.value.firstOrNull()

        // Check for the virtual driver on startup and store status
        virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus()
    }

    val useDarkTheme = when (appSettings.theme) {
        Theme.Light -> false
        Theme.Dark -> true
        Theme.System -> isSystemInDarkTheme()
    }

    val windowState = rememberWindowState(size = DpSize(600.dp, 800.dp))

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        undecorated = true
    ) {
        MaterialTheme(colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()) {
            Column(Modifier.fillMaxSize()) {
                CustomTitleBar(
                    windowState = windowState,
                    onMinimize = { windowState.isMinimized = true },
                    onClose = ::exitApplication
                )
                val localIp = remember { NetworkHandler_v1.getLocalIpAddress() }

                Box(Modifier.fillMaxSize()) {
                    AppContent(
                        appSettings = appSettings,
                        isServer = isServer,
                        isStreaming = isStreaming,
                        connectionStatus = connectionStatus,
                        discoveredDevices = discoveredDevices,
                        isMulticastMode = isMulticastMode,
                        sendMicrophone = sendMicrophone,
                        outputDevices = outputDevices.value,
                        selectedOutputDevice = selectedOutputDevice,
                        inputDevices = inputDevices.value,
                        selectedInputDevice = null,
                        selectedClientMic = selectedClientMic,
                        selectedServerMicOutput = selectedServerMicOutput,
                        streamingPort = streamingPort,
                        localIp = localIp,
                        virtualDriverStatus = virtualDriverStatus, // PASS STATUS TO UI
                        onConnectManual = { ip ->
                            isStreaming = true
                            connectionStatus = "Connecting manually to $ip..."
                            val port = streamingPort.toIntOrNull() ?: 9090
                            val mic = micPort.toIntOrNull() ?: 9092
                            val manualServerInfo = ServerInfo(ip, false, port)
                            NetworkHandler_v1.endDeviceDiscovery()
                            NetworkHandler_v1.launchClientInstance(
                                audioSettings,
                                manualServerInfo,
                                selectedOutputDevice!!,
                                sendMicrophone,
                                selectedClientMic,
                                mic
                            ) { key, args ->
                                connectionStatus = if (args.isEmpty()) key else String.format(key, *args)
                            }
                        },
                        onModeChange = { isSrv ->
                            isServer = isSrv
                            if (!isSrv) {
                                scope.launch { NetworkHandler_v1.stopCurrentStream() }
                                isStreaming = false
                                connectionStatus = Strings.get("status_inactive")
                                discoveredDevices.clear()
                                NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                                    discoveredDevices[hostname] = serverInfo
                                }
                            } else {
                                NetworkHandler_v1.endDeviceDiscovery()
                                discoveredDevices.clear()
                            }
                        },
                        onStartStreaming = {
                            isStreaming = true
                            connectionStatus = "Starting Server..."
                            val port = streamingPort.toIntOrNull() ?: 9090
                            val mic = micPort.toIntOrNull() ?: 9092
                            NetworkHandler_v1.launchServerInstance(
                                audioSettings,
                                port,
                                isMulticastMode,
                                selectedServerMicOutput,
                                mic
                            ) { key, args ->
                                // If the virtual driver is missing, update UI status and flag it
                                if (key == "error_virtual_driver_missing") {
                                    isStreaming = false
                                    // Re-check so UI refreshes the banner
                                    virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus()
                                    connectionStatus = Strings.get("status_inactive")
                                } else {
                                    connectionStatus = if (args.isEmpty()) key else String.format(key, *args)
                                }
                            }
                        },
                        onStopStreaming = {
                            isStreaming = false
                            connectionStatus = Strings.get("status_inactive")
                            scope.launch { NetworkHandler_v1.stopCurrentStream() }
                        },
                        onConnectToServer = { serverInfo ->
                            isStreaming = true
                            val mic = micPort.toIntOrNull() ?: 9092
                            NetworkHandler_v1.endDeviceDiscovery()
                            NetworkHandler_v1.launchClientInstance(
                                audioSettings,
                                serverInfo,
                                selectedOutputDevice!!,
                                sendMicrophone,
                                selectedClientMic,
                                mic
                            ) { key, args -> connectionStatus = Strings.get(key, *args) }
                        },
                        onRefreshDevices = {
                            discoveredDevices.clear()
                            NetworkHandler_v1.endDeviceDiscovery()
                            NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                                discoveredDevices[hostname] = serverInfo
                            }
                        },
                        onMulticastModeChange = { isMulti -> isMulticastMode = isMulti },
                        onSendMicrophoneChange = { send ->
                            sendMicrophone = if (!appSettings.experimentalFeaturesEnabled) false else send
                        },
                        onSelectedOutputDeviceChange = { dev -> selectedOutputDevice = dev },
                        onSelectedInputDeviceChange = { dev -> selectedInputDevice = dev },
                        onSelectedClientMicChange = { dev -> selectedClientMic = dev },
                        onSelectedServerMicOutputChange = { dev -> selectedServerMicOutput = dev },
                        onOpenSettings = { showSettings = true }
                    )

                    SettingsScreen(
                        visible = showSettings,
                        appSettings = appSettings,
                        audioSettings = audioSettings,
                        streamingPort = streamingPort,
                        micPort = micPort,
                        onAppSettingsChange = { newSettings -> appSettings = newSettings },
                        onAudioSettingsChange = { settings -> audioSettings = settings },
                        onStreamingPortChange = { port -> streamingPort = port },
                        onMicPortChange = { port -> micPort = port },
                        onClose = { showSettings = false }
                    )
                }
            }
        }
    }
}

@Composable
fun WindowScope.CustomTitleBar(
    windowState: WindowState,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surface

    var preMaximizeSize by remember { mutableStateOf(windowState.size) }
    var preMaximizePosition by remember { mutableStateOf(windowState.position) }

    val maxBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val density = LocalDensity.current.density

    val maximizedWidth = (maxBounds.width / density).dp
    val maximizedHeight = (maxBounds.height / density).dp
    val maximizedPositionX = (maxBounds.x / density).dp
    val maximizedPositionY = (maxBounds.y / density).dp

    val isManuallyMaximized = windowState.size == DpSize(maximizedWidth, maximizedHeight)

    WindowDraggableArea {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(surfaceColor)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "WiFi Audio Streaming",
                style = MaterialTheme.typography.titleSmall,
                color = onSurfaceColor,
                modifier = Modifier.weight(1f)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Minimize, "Minimize", tint = onSurfaceColor)
                }

                IconButton(
                    onClick = {
                        if (isManuallyMaximized) {
                            windowState.size = preMaximizeSize
                            windowState.position = preMaximizePosition
                        } else {
                            preMaximizeSize = windowState.size
                            preMaximizePosition = windowState.position
                            windowState.size = DpSize(maximizedWidth, maximizedHeight)
                            windowState.position = WindowPosition(maximizedPositionX, maximizedPositionY)
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    val icon = if (isManuallyMaximized) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen
                    Icon(icon, "Maximize/Restore", tint = onSurfaceColor)
                }

                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = onSurfaceColor)
                }
            }
        }
    }
}