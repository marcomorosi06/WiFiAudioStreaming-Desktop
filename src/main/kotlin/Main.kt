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

object NetworkHandler_v1 {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamingJob: Job? = null
    private var listeningJob: Job? = null
    private var broadcastingJob: Job? = null
    private var serverAudioLine: TargetDataLine? = null
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

    // ... Le funzioni beginDeviceDiscovery, endDeviceDiscovery, etc. rimangono invariate ...
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
                socket?.leaveGroup(InetAddress.getByName(MULTICAST_GROUP_IP))
                socket?.close()
            }
        }
    }

    fun endDeviceDiscovery() {
        listeningJob?.cancel()
    }

    fun startAnnouncingPresence(isMulticast: Boolean, port: Int) {
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch {
            val hostname = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "Desktop-PC" }
            val mode = if (isMulticast) "MULTICAST" else "UNICAST"
            val message = "$DISCOVERY_MESSAGE;$hostname;$mode;$port"

            DatagramSocket().use { socket ->
                val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                while (isActive) {
                    val packet = DatagramPacket(message.toByteArray(), message.length, groupAddress, DISCOVERY_PORT)
                    socket.send(packet)
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
        selectedMixerInfo: Mixer.Info?,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit // <- CORREZIONE 1: Firma modificata
    ) {
        if (micOutputMixerInfo != null) {
            micReceiverJob = scope.launchMicReceiver(audioSettings, isMulticast, micOutputMixerInfo, micPort)
        }
        startAnnouncingPresence(isMulticast, port)
        streamingJob = scope.launch {
            var audioLine: TargetDataLine? = null
            try {
                val systemAudioDeviceInfo = selectedMixerInfo ?: run {
                    onStatusUpdate("status_error_no_device", emptyArray()) // <- CORREZIONE 2: Usa emptyArray()
                    return@launch
                }
                val audioMixer = AudioSystem.getMixer(systemAudioDeviceInfo)
                val format = audioSettings.toAudioFormat()
                val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(lineInfo)) {
                    onStatusUpdate("status_error_unsupported_format", emptyArray())
                    return@launch
                }
                val frameSize = format.frameSize
                val adjustedBufferSize = (audioSettings.bufferSize / frameSize) * frameSize
                if (adjustedBufferSize <= 0) {
                    onStatusUpdate("status_error_invalid_buffer", emptyArray())
                    return@launch
                }
                audioLine = audioMixer.getLine(lineInfo) as? TargetDataLine
                serverAudioLine = audioLine
                audioLine?.open(format, adjustedBufferSize)
                audioLine?.start()
                if (audioLine == null || !audioLine.isOpen) {
                    onStatusUpdate("status_error_critical_line", emptyArray())
                    serverAudioLine = null
                    return@launch
                }

                if (isMulticast) {
                    onStatusUpdate("status_multicast_streaming", arrayOf(port)) // <- CORREZIONE 3: Usa arrayOf()
                    MulticastSocket().use { socket ->
                        val group = InetAddress.getByName(MULTICAST_GROUP_IP)
                        val buffer = ByteArray(adjustedBufferSize)
                        while (isActive) {
                            val bytesRead = audioLine.read(buffer, 0, buffer.size)
                            if (bytesRead > 0) {
                                val packet = DatagramPacket(buffer, bytesRead, group, port)
                                socket.send(packet)
                            } else if (bytesRead < 0) break
                        }
                    }
                } else { // Unicast
                    onStatusUpdate("status_server_waiting", arrayOf(port))
                    val localAddress = InetSocketAddress("0.0.0.0", port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        val clientDatagram = socket.receive()
                        if (clientDatagram.packet.readText().trim() == CLIENT_HELLO_MESSAGE) {
                            val clientAddress = clientDatagram.address
                            onStatusUpdate("status_client_connected", arrayOf(clientAddress))
                            stopAnnouncingPresence()

                            val ackPacket = buildPacket { writeText("HELLO_ACK") }
                            socket.send(Datagram(ackPacket, clientAddress))

                            val buffer = ByteArray(adjustedBufferSize)
                            while (isActive) {
                                val bytesRead = audioLine.read(buffer, 0, buffer.size)
                                if (bytesRead > 0) {
                                    val packet = buildPacket { writeFully(buffer, 0, bytesRead) }
                                    socket.send(Datagram(packet, clientAddress))
                                } else if (bytesRead < 0) break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) onStatusUpdate("status_error_server", arrayOf(e.message ?: "Unknown"))
            } finally {
                stopAnnouncingPresence()
                onStatusUpdate("status_server_stopped", emptyArray())
                audioLine?.stop(); audioLine?.close()
                serverAudioLine = null
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
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit // <- CORREZIONE 1: Firma modificata
    ) {
        if (sendMicrophone && micInputMixerInfo != null) {
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
                        val ackDatagram = withTimeout(5000) { socket.receive() }
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
                if (e !is CancellationException) onStatusUpdate("status_error_client", arrayOf(e.message ?: "Unknown"))
            } finally {
                onStatusUpdate("status_streaming_ended", emptyArray())
                sourceDataLine?.drain(); sourceDataLine?.stop(); sourceDataLine?.close()
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
        serverAudioLine?.stop(); serverAudioLine?.close()
        streamingJob = null; micReceiverJob = null; serverAudioLine = null
    }

    fun terminateAllServices() {
        scope.cancel()
    }
}

/*

// --- UI (INVARIATA, INCLUSA PER COMPLETEZZA) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun LegacyAppUI() {
    var isServer by remember { mutableStateOf(true) }
    val discoveredDevices = remember { mutableStateMapOf<String, ServerInfo>() }
    var connectionStatus by remember { mutableStateOf("Inattivo") }
    var isStreaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var audioSettings by remember {
        mutableStateOf(AudioSettings_V1(sampleRate = 48000f, bitDepth = 16, channels = 2, bufferSize = 4096))
    }
    var streamingPort by remember { mutableStateOf("9090") }
    var micPort by remember { mutableStateOf("9092") }

    val outputDevices = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedOutputDevice by remember { mutableStateOf<Mixer.Info?>(null) }
    var outputMenuExpanded by remember { mutableStateOf(false) }

    val inputDevices = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedInputDevice by remember { mutableStateOf<Mixer.Info?>(null) }
    var inputMenuExpanded by remember { mutableStateOf(false) }

    var sendMicrophone by remember { mutableStateOf(false) }
    var selectedClientMic by remember { mutableStateOf<Mixer.Info?>(null) }
    var clientMicMenuExpanded by remember { mutableStateOf(false) }

    var selectedServerMicOutput by remember { mutableStateOf<Mixer.Info?>(null) }
    var serverMicOutputMenuExpanded by remember { mutableStateOf(false) }

    var isMulticastMode by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        outputDevices.value = NetworkHandler_v1.findAvailableOutputMixers()
        inputDevices.value = NetworkHandler_v1.findAvailableInputMixers()
        selectedOutputDevice = outputDevices.value.firstOrNull()
        selectedInputDevice = inputDevices.value.firstOrNull()
        selectedClientMic = inputDevices.value.firstOrNull()
        selectedServerMicOutput = outputDevices.value.find { it.name.contains("CABLE Input", ignoreCase = true) }
            ?: outputDevices.value.firstOrNull()
    }

    LaunchedEffect(isServer) {
        if (!isServer) {
            scope.launch { NetworkHandler_v1.stopCurrentStream() }
            isStreaming = false
            connectionStatus = "Inattivo"
            NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                discoveredDevices[hostname] = serverInfo
            }
        } else {
            NetworkHandler_v1.endDeviceDiscovery()
            discoveredDevices.clear()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("WiFi Audio Streamer (Desktop)", style = MaterialTheme.typography.headlineLarge)
                Text("Stato: $connectionStatus", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.height(60.dp))

                if (!isStreaming) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { isServer = false }, colors = if (!isServer) ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ButtonDefaults.textButtonColors(), modifier = Modifier.weight(1f)) { Text("Ricevi (Client)") }
                            TextButton(onClick = { isServer = true }, colors = if (isServer) ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ButtonDefaults.textButtonColors(), modifier = Modifier.weight(1f)) { Text("Invia (Server)") }
                        }
                    }
                    NetworkSettingsPanel(
                        port = streamingPort,
                        onPortChange = { streamingPort = it },
                        micPort = micPort,
                        onMicPortChange = { micPort = it },
                        enabled = !isStreaming
                    )
                    AudioSettingsPanel(settings = audioSettings, onSettingsChange = { newSettings -> audioSettings = newSettings })
                    if (isServer) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Impostazioni Server", style = MaterialTheme.typography.titleMedium)
                                Text("Sorgente Audio Principale (Input):", style = MaterialTheme.typography.titleSmall)
                                ExposedDropdownMenuBox(expanded = inputMenuExpanded, onExpandedChange = { inputMenuExpanded = !inputMenuExpanded }) {
                                    OutlinedTextField(value = selectedInputDevice?.name ?: "Nessun dispositivo", onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = inputMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                    ExposedDropdownMenu(expanded = inputMenuExpanded, onDismissRequest = { inputMenuExpanded = false }) {
                                        inputDevices.value.forEach { device -> DropdownMenuItem(text = { Text(device.name) }, onClick = { selectedInputDevice = device; inputMenuExpanded = false }) }
                                    }
                                }
                                Text("Output Microfoni Ricevuti (es. Virtual Cable):", style = MaterialTheme.typography.titleSmall)
                                ExposedDropdownMenuBox(expanded = serverMicOutputMenuExpanded, onExpandedChange = { serverMicOutputMenuExpanded = !serverMicOutputMenuExpanded }) {
                                    OutlinedTextField(value = selectedServerMicOutput?.name ?: "Nessun dispositivo", onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverMicOutputMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                    ExposedDropdownMenu(expanded = serverMicOutputMenuExpanded, onDismissRequest = { serverMicOutputMenuExpanded = false }) {
                                        outputDevices.value.forEach { device -> DropdownMenuItem(text = { Text(device.name) }, onClick = { selectedServerMicOutput = device; serverMicOutputMenuExpanded = false }) }
                                    }
                                }
                                Divider(Modifier.padding(vertical = 8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) { Text("Modalità Streaming", style = MaterialTheme.typography.bodyLarge); Text(if (isMulticastMode) "Multicast: Più client." else "Unicast: Singolo client.", style = MaterialTheme.typography.bodySmall) }
                                    Switch(checked = isMulticastMode, onCheckedChange = { isMulticastMode = it })
                                }
                            }
                        }
                    }
                    if (!isServer) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Impostazioni Client", style = MaterialTheme.typography.titleMedium)
                                Text("Dispositivo di Output (Audio dal Server):", style = MaterialTheme.typography.titleSmall)
                                ExposedDropdownMenuBox(expanded = outputMenuExpanded, onExpandedChange = { outputMenuExpanded = !outputMenuExpanded }) {
                                    OutlinedTextField(value = selectedOutputDevice?.name ?: "Nessun dispositivo", onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = outputMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                    ExposedDropdownMenu(expanded = outputMenuExpanded, onDismissRequest = { outputMenuExpanded = false }) {
                                        outputDevices.value.forEach { device -> DropdownMenuItem(text = { Text(device.name) }, onClick = { selectedOutputDevice = device; outputMenuExpanded = false }) }
                                    }
                                }
                                Divider(Modifier.padding(vertical = 8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Invia Microfono al Server", style = MaterialTheme.typography.bodyLarge)
                                    Switch(checked = sendMicrophone, onCheckedChange = { sendMicrophone = it })
                                }
                                if (sendMicrophone) {
                                    Text("Seleziona Microfono da Inviare:", style = MaterialTheme.typography.titleSmall)
                                    ExposedDropdownMenuBox(expanded = clientMicMenuExpanded, onExpandedChange = { clientMicMenuExpanded = !clientMicMenuExpanded }) {
                                        OutlinedTextField(value = selectedClientMic?.name ?: "Nessun microfono", onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientMicMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                        ExposedDropdownMenu(expanded = clientMicMenuExpanded, onDismissRequest = { clientMicMenuExpanded = false }) {
                                            inputDevices.value.forEach { device -> DropdownMenuItem(text = { Text(device.name) }, onClick = { selectedClientMic = device; clientMicMenuExpanded = false }) }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Text("Stream Attivi Trovati:", style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { discoveredDevices.clear() }) { Icon(Icons.Filled.Refresh, "Aggiorna lista") }
                        }
                        if (discoveredDevices.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Ricerca stream...") }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                items(discoveredDevices.toList()) { (_, serverInfo) ->
                                    val modeText = if (serverInfo.isMulticast) "Multicast" else "Unicast"
                                    val buttonText = "Ascolta da ${serverInfo.ip} ($modeText - Porta ${serverInfo.port})"
                                    Button(onClick = {
                                        isStreaming = true
                                        val micPortToUse = micPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 9092
                                        NetworkHandler_v1.launchClientInstance(
                                            audioSettings = audioSettings,
                                            serverInfo = serverInfo,
                                            selectedMixerInfo = selectedOutputDevice!!,
                                            sendMicrophone = sendMicrophone,
                                            micInputMixerInfo = selectedClientMic,
                                            micPort = micPortToUse
                                        ) { status -> connectionStatus = status }
                                    }, enabled = selectedOutputDevice != null && (!sendMicrophone || selectedClientMic != null)) { Text(buttonText) }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f, fill = false))

                if (isStreaming) {
                    Button(onClick = {
                        isStreaming = false
                        connectionStatus = "Inattivo"
                        scope.launch {
                            NetworkHandler_v1.stopCurrentStream()
                        }
                    }) { Text("Ferma Streaming") }
                } else {
                    if (isServer) {
                        Button(onClick = {
                            isStreaming = true
                            val portToUse = streamingPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 9090
                            val micPortToUse = micPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 9092
                            NetworkHandler_v1.launchServerInstance(
                                audioSettings,
                                portToUse,
                                isMulticastMode,
                                selectedInputDevice,
                                selectedServerMicOutput,
                                micPortToUse
                            ) { status -> connectionStatus = status }
                        }, enabled = selectedInputDevice != null && selectedServerMicOutput != null && (streamingPort.toIntOrNull() ?: 0) in 1024..65535 && (micPort.toIntOrNull() ?: 0) in 1024..65535) { Text("Avvia Server") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsPanel(
    settings: AudioSettings_V1,
    onSettingsChange: (AudioSettings_V1) -> Unit
) {
    var sampleRateMenuExpanded by remember { mutableStateOf(false) }
    var bitDepthMenuExpanded by remember { mutableStateOf(false) }

    val sampleRates = listOf(44100f, 48000f, 96000f)
    val bitDepths = listOf(8, 16)

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Impostazioni Audio", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = sampleRateMenuExpanded,
                    onExpandedChange = { sampleRateMenuExpanded = !sampleRateMenuExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = "${settings.sampleRate.toInt()} Hz", onValueChange = {}, readOnly = true,
                        label = { Text("Sample Rate") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateMenuExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = sampleRateMenuExpanded, onDismissRequest = { sampleRateMenuExpanded = false }) {
                        sampleRates.forEach { rate ->
                            DropdownMenuItem(
                                text = { Text("${rate.toInt()} Hz") },
                                onClick = {
                                    onSettingsChange(settings.copy(sampleRate = rate))
                                    sampleRateMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = bitDepthMenuExpanded,
                    onExpandedChange = { bitDepthMenuExpanded = !bitDepthMenuExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = "${settings.bitDepth}-bit", onValueChange = {}, readOnly = true,
                        label = { Text("Bit Depth") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bitDepthMenuExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = bitDepthMenuExpanded, onDismissRequest = { bitDepthMenuExpanded = false }) {
                        bitDepths.forEach { depth ->
                            DropdownMenuItem(
                                text = { Text("$depth-bit") },
                                onClick = {
                                    onSettingsChange(settings.copy(bitDepth = depth))
                                    bitDepthMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Column {
                Text("Canali", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                        onClick = { onSettingsChange(settings.copy(channels = 1)) },
                        selected = settings.channels == 1
                    ) { Text("Mono") }
                    SegmentedButton(
                        shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                        onClick = { onSettingsChange(settings.copy(channels = 2)) },
                        selected = settings.channels == 2
                    ) { Text("Stereo") }
                }
            }

            Column {
                Text("Dimensione Buffer: ${settings.bufferSize} byte", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Slider(
                    value = settings.bufferSize.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(bufferSize = it.roundToInt())) },
                    valueRange = 512f..8192f,
                    steps = ((8192f - 512f) / 256f).toInt() - 1
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsPanel(
    port: String,
    onPortChange: (String) -> Unit,
    micPort: String,
    onMicPortChange: (String) -> Unit,
    enabled: Boolean
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Impostazioni di Rete", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = port,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() } && newValue.length <= 5) {
                        onPortChange(newValue)
                    }
                },
                label = { Text("Porta Audio Principale (1024-65535)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            )
            OutlinedTextField(
                value = micPort,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() } && newValue.length <= 5) {
                        onMicPortChange(newValue)
                    }
                },
                label = { Text("Porta Microfono (1024-65535)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            )
        }
    }
}
*/

fun main() = application {
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
        selectedServerMicOutput = outputDevices.value.find { it.name.contains("CABLE Input", ignoreCase = true) } ?: outputDevices.value.firstOrNull()
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
                        selectedInputDevice = selectedInputDevice,
                        selectedClientMic = selectedClientMic,
                        selectedServerMicOutput = selectedServerMicOutput,
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
                            val port = streamingPort.toIntOrNull() ?: 9090
                            val mic = micPort.toIntOrNull() ?: 9092
                            NetworkHandler_v1.launchServerInstance(
                                audioSettings,
                                port,
                                isMulticastMode,
                                selectedInputDevice,
                                selectedServerMicOutput,
                                mic
                            ) { key, args -> connectionStatus = Strings.get(key, *args) }
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

    // Memorizziamo la dimensione e la posizione prima di massimizzare per poterle ripristinare
    var preMaximizeSize by remember { mutableStateOf(windowState.size) }
    var preMaximizePosition by remember { mutableStateOf(windowState.position) }

    // Otteniamo i limiti massimi della finestra che escludono la taskbar/dock
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
                            // Se è massimizzato, ripristina la dimensione e posizione precedenti
                            windowState.size = preMaximizeSize
                            windowState.position = preMaximizePosition
                        } else {
                            // Salva la dimensione e posizione attuali
                            preMaximizeSize = windowState.size
                            preMaximizePosition = windowState.position

                            // Massimizza manualmente usando i bordi calcolati
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