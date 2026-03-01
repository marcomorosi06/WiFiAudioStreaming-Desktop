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
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import javax.crypto.spec.SecretKeySpec
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
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.Color

object ThemeEngine {
    fun pickImageAndExtractColor(): Long? {
        try {
            val dialog = FileDialog(null as Frame?, "Select a Wallpaper", FileDialog.LOAD)
            dialog.file = "*.jpg;*.png;*.jpeg"
            dialog.isVisible = true

            if (dialog.directory == null || dialog.file == null) return null
            val file = File(dialog.directory, dialog.file)
            if (!file.exists()) return null

            val img = ImageIO.read(file) ?: return null

            val img1x1 = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
            val graphics = img1x1.createGraphics()
            graphics.drawImage(img.getScaledInstance(1, 1, java.awt.Image.SCALE_FAST), 0, 0, null)
            graphics.dispose()

            val rgb = img1x1.getRGB(0, 0)
            val awtColor = java.awt.Color(rgb)
            val composeColor = Color(awtColor.red, awtColor.green, awtColor.blue)

            return composeColor.value.toLong()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}

object MaterialYouGenerator {
    fun generateDynamicColorScheme(seedColor: Color, isDark: Boolean): ColorScheme {
        val hsb = FloatArray(3)
        java.awt.Color.RGBtoHSB(
            (seedColor.red * 255).toInt(),
            (seedColor.green * 255).toInt(),
            (seedColor.blue * 255).toInt(),
            hsb
        )
        val h = hsb[0]
        val s = hsb[1].coerceAtLeast(0.15f)
        val b = hsb[2]
        val bFactor = b.coerceIn(0.5f, 1.0f)
        val bgFactor = b.coerceIn(0.85f, 1.0f)

        fun fromHsb(hue: Float, sat: Float, bright: Float, factor: Float = 1f): Color {
            return Color(java.awt.Color.HSBtoRGB(hue % 1f, sat.coerceIn(0f, 1f), (bright * factor).coerceIn(0f, 1f)))
        }

        val tH = (h + 0.15f) % 1f

        if (!isDark) {
            return lightColorScheme(
                primary = fromHsb(h, s * 0.9f, 0.4f, bFactor),
                onPrimary = Color.White,
                primaryContainer = fromHsb(h, s * 0.4f, 0.9f, bFactor),
                onPrimaryContainer = fromHsb(h, s * 1.0f, 0.15f),
                secondary = fromHsb(h, s * 0.3f, 0.45f, bFactor),
                onSecondary = Color.White,
                secondaryContainer = fromHsb(h, s * 0.2f, 0.92f, bFactor),
                onSecondaryContainer = fromHsb(h, s * 0.5f, 0.15f),
                tertiary = fromHsb(tH, s * 0.4f, 0.45f, bFactor),
                onTertiary = Color.White,
                tertiaryContainer = fromHsb(tH, s * 0.25f, 0.92f, bFactor),
                onTertiaryContainer = fromHsb(tH, s * 0.6f, 0.15f),
                background = fromHsb(h, s * 0.05f, 0.98f, bgFactor),
                onBackground = fromHsb(h, s * 0.1f, 0.1f),
                surface = fromHsb(h, s * 0.05f, 0.98f, bgFactor),
                onSurface = fromHsb(h, s * 0.1f, 0.1f),
                surfaceVariant = fromHsb(h, s * 0.1f, 0.9f, bgFactor),
                onSurfaceVariant = fromHsb(h, s * 0.15f, 0.3f),
                surfaceContainerLowest = fromHsb(h, s * 0.02f, 1.0f, bgFactor),
                surfaceContainerLow = fromHsb(h, s * 0.05f, 0.96f, bgFactor),
                surfaceContainer = fromHsb(h, s * 0.08f, 0.94f, bgFactor),
                surfaceContainerHigh = fromHsb(h, s * 0.1f, 0.92f, bgFactor),
                surfaceContainerHighest = fromHsb(h, s * 0.12f, 0.90f, bgFactor),
                outline = fromHsb(h, s * 0.1f, 0.5f, bFactor),
                outlineVariant = fromHsb(h, s * 0.1f, 0.8f, bFactor)
            )
        } else {
            return darkColorScheme(
                primary = fromHsb(h, s * 0.7f, 0.85f, bFactor),
                onPrimary = fromHsb(h, s * 1.0f, 0.2f),
                primaryContainer = fromHsb(h, s * 0.8f, 0.3f, bFactor),
                onPrimaryContainer = fromHsb(h, s * 0.4f, 0.9f),
                secondary = fromHsb(h, s * 0.4f, 0.8f, bFactor),
                onSecondary = fromHsb(h, s * 0.6f, 0.2f),
                secondaryContainer = fromHsb(h, s * 0.4f, 0.3f, bFactor),
                onSecondaryContainer = fromHsb(h, s * 0.2f, 0.9f),
                tertiary = fromHsb(tH, s * 0.5f, 0.8f, bFactor),
                onTertiary = fromHsb(tH, s * 0.7f, 0.2f),
                tertiaryContainer = fromHsb(tH, s * 0.5f, 0.3f, bFactor),
                onTertiaryContainer = fromHsb(tH, s * 0.2f, 0.9f),
                background = fromHsb(h, s * 0.15f, 0.10f, bFactor),
                onBackground = fromHsb(h, s * 0.1f, 0.9f),
                surface = fromHsb(h, s * 0.15f, 0.10f, bFactor),
                onSurface = fromHsb(h, s * 0.1f, 0.9f),
                surfaceVariant = fromHsb(h, s * 0.20f, 0.25f, bFactor),
                onSurfaceVariant = fromHsb(h, s * 0.15f, 0.8f),
                surfaceContainerLowest = fromHsb(h, s * 0.15f, 0.05f, bFactor),
                surfaceContainerLow = fromHsb(h, s * 0.15f, 0.12f, bFactor),
                surfaceContainer = fromHsb(h, s * 0.15f, 0.15f, bFactor),
                surfaceContainerHigh = fromHsb(h, s * 0.15f, 0.18f, bFactor),
                surfaceContainerHighest = fromHsb(h, s * 0.15f, 0.22f, bFactor),
                outline = fromHsb(h, s * 0.15f, 0.6f, bFactor),
                outlineVariant = fromHsb(h, s * 0.15f, 0.3f, bFactor)
            )
        }
    }
}

enum class Theme { Light, Dark, System }
data class AppSettings(
    val theme: Theme = Theme.System,
    val experimentalFeaturesEnabled: Boolean = false,
    val hideWindowsPrivacyBanner: Boolean = false,
    val hideWindowsRoutingBanner: Boolean = false,
    val customThemeColor: Long? = null
)

data class ServerInfo(
    val ip: String,
    val isMulticast: Boolean,
    val port: Int,
    val isPasswordProtected: Boolean = false,
    val multicastGroupIp: String = "239.255.0.1"
)
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

sealed class VirtualDriverStatus {
    object Ok : VirtualDriverStatus()
    data class Missing(val driverName: String, val downloadUrl: String) : VirtualDriverStatus()
    data class LinuxActionRequired(val message: String, val commands: String) : VirtualDriverStatus()
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

    fun getActiveNetworkInterface(): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { iface ->
                iface.isUp &&
                        !iface.isLoopback &&
                        !iface.displayName.contains("Virtual", ignoreCase = true) &&
                        !iface.displayName.contains("VMware", ignoreCase = true) &&
                        !iface.displayName.contains("Hyper-V", ignoreCase = true) &&
                        !iface.displayName.contains("WSL", ignoreCase = true) &&
                        iface.inetAddresses.toList().any { addr ->
                            addr is java.net.Inet4Address && !addr.isLoopbackAddress
                        }
            }
        } catch (e: Exception) { null }
    }

    @Volatile var currentServerVolume: Float = 1.0f

    fun setServerVolume(volume: Float) {
        currentServerVolume = volume
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamingJob: Job? = null
    private var listeningJob: Job? = null
    private var broadcastingJob: Job? = null
    private var serverGrabber: org.bytedeco.javacv.FFmpegFrameGrabber? = null
    private var micReceiverJob: Job? = null

    // =========================================================
    // FIX 2 — FFmpeg avviato in anticipo, indipendentemente dal client
    // Il grabber viene preparato non appena il server inizia,
    // così quando il client si connette i campioni sono già pronti.
    // =========================================================
    private var grabberPrewarmJob: Job? = null

    private const val DISCOVERY_PORT = 9091
    private const val CLIENT_HELLO_MESSAGE = "HELLO_FROM_CLIENT"
    private const val DISCOVERY_MESSAGE = "WIFI_AUDIO_STREAMER_DISCOVERY"
    // Canale discovery fisso — sempre 239.255.0.1
    private const val DISCOVERY_MULTICAST_IP = "239.255.0.1"
    // IP multicast di streaming: derivato dall'IP locale per ridurre collisioni.
    // L'utente può sovrascriverlo con customMulticastLastOctet.
    var customMulticastLastOctet: Int? = null

    private fun resolveStreamingMulticastIp(): String {
        customMulticastLastOctet?.let { return "239.255.0.$it" }
        return try {
            val localIp = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull()?.hostAddress
            if (localIp != null) {
                val parts = localIp.split(".")
                if (parts.size == 4) {
                    val o3 = parts[2].toIntOrNull() ?: 0
                    val o4 = parts[3].toIntOrNull() ?: 1
                    val derived = ((o3 xor o4) and 0xFF).let { if (it == 0) 1 else it }
                    "239.255.0.$derived"
                } else "239.255.0.1"
            } else "239.255.0.1"
        } catch (_: Exception) { "239.255.0.1" }
    }

    private var originalLinuxSink: String? = null
    private var originalLinuxSource: String? = null

    private fun getPactlDefault(type: String): String? {
        try {
            val direct = ProcessBuilder("pactl", "get-default-$type").start()
            val directOutput = direct.inputStream.bufferedReader().readText().trim()
            if (direct.waitFor() == 0 && directOutput.isNotEmpty()) return directOutput

            val info = ProcessBuilder("pactl", "info").start()
            val infoOutput = info.inputStream.bufferedReader().readText()
            if (info.waitFor() == 0) {
                val prefix = if (type == "sink") "Default Sink: " else "Default Source: "
                return infoOutput.lines().find { it.startsWith(prefix) }?.substringAfter(prefix)?.trim()
            }
        } catch (e: Exception) {}
        return null
    }

    private fun routeLinuxAudioToVirtualCable() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return

        val currentSink = getPactlDefault("sink")
        val currentSource = getPactlDefault("source")

        if (currentSink != null && currentSink != "VirtualCable") originalLinuxSink = currentSink
        if (currentSource != null && currentSource != "VirtualCable.monitor") originalLinuxSource = currentSource

        try {
            ProcessBuilder("pactl", "set-default-sink", "VirtualCable").start().waitFor()
            ProcessBuilder("pactl", "set-default-source", "VirtualCable.monitor").start().waitFor()
            println("--- Linux Audio instradato su VirtualCable ---")
        } catch (e: Exception) {}
    }

    fun restoreLinuxAudioRouting() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return

        try {
            originalLinuxSink?.let { ProcessBuilder("pactl", "set-default-sink", it).start().waitFor() }
            originalLinuxSource?.let { ProcessBuilder("pactl", "set-default-source", it).start().waitFor() }
            println("--- Linux Audio ripristinato alle casse originali ---")
        } catch (e: Exception) {}
    }

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

    fun checkVirtualDriverStatus(): VirtualDriverStatus {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val isInstalled = AudioSystem.getMixerInfo().any { it.name.contains("CABLE Output", ignoreCase = true) }
                if (isInstalled) VirtualDriverStatus.Ok else VirtualDriverStatus.Missing("VB-Audio Virtual Cable", "https://vb-audio.com/Cable/")
            }
            os.contains("mac") -> {
                val isInstalled = AudioSystem.getMixerInfo().any { it.name.contains("BlackHole", ignoreCase = true) }
                if (isInstalled) VirtualDriverStatus.Ok else VirtualDriverStatus.Missing("BlackHole 2ch", "https://existential.audio/blackhole/")
            }
            else -> {
                try {
                    val checkPactl = ProcessBuilder("which", "pactl").start()
                    if (checkPactl.waitFor() != 0) {
                        return VirtualDriverStatus.LinuxActionRequired(
                            message = "PulseAudio utilities are missing. The app cannot route system audio automatically.",
                            commands = "sudo apt update && sudo apt install pulseaudio-utils"
                        )
                    }
                    val checkSink = ProcessBuilder("sh", "-c", "pactl list short sinks | grep VirtualCable").start()
                    if (checkSink.waitFor() != 0) {
                        ProcessBuilder("pactl", "load-module", "module-null-sink", "sink_name=VirtualCable", "sink_properties=device.description=VirtualCable").start().waitFor()
                    }
                    VirtualDriverStatus.Ok
                } catch (e: Exception) {
                    VirtualDriverStatus.Ok
                }
            }
        }
    }

    fun setupLinuxVirtualCable() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return

        try {
            val checkPactl = ProcessBuilder("which", "pactl").start()
            if (checkPactl.waitFor() != 0) {
                println("Comando 'pactl' non trovato. Impossibile creare il cavo virtuale in automatico.")
                return
            }

            val checkSink = ProcessBuilder("sh", "-c", "pactl list short sinks | grep VirtualCable").start()
            if (checkSink.waitFor() == 0) {
                println("--- Linux: VirtualCable rilevato e già operativo ---")
                return
            }

            println("--- Linux: Creazione VirtualCable in corso... ---")
            val createSink = ProcessBuilder(
                "pactl", "load-module", "module-null-sink",
                "sink_name=VirtualCable",
                "sink_properties=device.description=VirtualCable"
            ).start()

            if (createSink.waitFor() == 0) {
                println("--- Linux: VirtualCable creato con successo! ---")
            } else {
                println("--- Linux: Impossibile creare il VirtualCable ---")
            }
        } catch (e: Exception) {
            println("Errore durante il setup audio adattivo su Linux: ${e.message}")
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

                val groupAddress = InetAddress.getByName(DISCOVERY_MULTICAST_IP)
                socket = MulticastSocket(DISCOVERY_PORT).apply {
                    getActiveNetworkInterface()?.let { networkInterface = it }
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
                            if (parts.size >= 4) {
                                val hostname = parts[1]
                                val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                                val port = parts[3].toIntOrNull() ?: continue
                                val isPasswordProtected = parts.getOrNull(4)?.equals("LOCKED", ignoreCase = true) == true
                                val multicastGroupIp = parts.getOrNull(5)?.takeIf { it.startsWith("239.") }
                                    ?: "239.255.0.1"
                                onDeviceFound(hostname, ServerInfo(remoteIp, isMulticast, port, isPasswordProtected, multicastGroupIp))
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
                    socket?.leaveGroup(InetAddress.getByName(DISCOVERY_MULTICAST_IP))
                } catch (_: Exception) {}
                socket?.close()
            }
        }
    }

    fun endDeviceDiscovery() {
        listeningJob?.cancel()
    }

    fun startAnnouncingPresence(isMulticast: Boolean, port: Int, isPasswordProtected: Boolean = false) {
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch {
            val hostname = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "Desktop-PC" }
            val mode = if (isMulticast) "MULTICAST" else "UNICAST"
            val locked = if (isPasswordProtected) "LOCKED" else "OPEN"
            val streamingMulticastIp = resolveStreamingMulticastIp()
            val message = "$DISCOVERY_MESSAGE;$hostname;$mode;$port;$locked;$streamingMulticastIp"
            // Discovery sempre su canale fisso 239.255.0.1
            val discoveryGroup = InetAddress.getByName(DISCOVERY_MULTICAST_IP)

            MulticastSocket().use { socket ->
                socket.timeToLive = 4
                getActiveNetworkInterface()?.let { socket.networkInterface = it }
                while (isActive) {
                    try {
                        val bytes = message.toByteArray()
                        val packet = DatagramPacket(bytes, bytes.size, discoveryGroup, DISCOVERY_PORT)
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
                MulticastSocket(micPort).apply { joinGroup(InetAddress.getByName(DISCOVERY_MULTICAST_IP)) }
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
                InetAddress.getByName(DISCOVERY_MULTICAST_IP)
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

    // =========================================================
    // FIX 2 — Prepara e avvia FFmpeg in anticipo rispetto al client.
    // Chiamato all'inizio di launchServerInstance, prima ancora di
    // aspettare qualsiasi connessione in unicast.
    // =========================================================
    private fun buildAndStartGrabber(audioSettings: AudioSettings_V1): org.bytedeco.javacv.FFmpegFrameGrabber? {
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
                grabberFormat = "alsa"
                deviceName = "default"
            }
        }

        return try {
            routeLinuxAudioToVirtualCable()
            org.bytedeco.javacv.FFmpegFrameGrabber(deviceName).apply {
                setFormat(grabberFormat)
                sampleRate = audioSettings.sampleRate.toInt()
                audioChannels = audioSettings.channels
                sampleFormat = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                start()
            }.also {
                println("--- FFMPEG pre-warmed and started successfully ---")
            }
        } catch (e: Exception) {
            println("=== FFMPEG PRE-WARM FAILED ===")
            e.printStackTrace()
            null
        }
    }

    // =========================================================
    // SRP-6a helpers — Desktop (usa CryptoManagerDesktop)
    // =========================================================

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0)
        return ByteArray(length / 2) { i -> Integer.parseInt(substring(i * 2, i * 2 + 2), 16).toByte() }
    }
    private fun authPort(streamingPort: Int) = streamingPort + 1

    private suspend fun serverSrpHandshake(
        socket: io.ktor.network.sockets.BoundDatagramSocket,
        clientAddress: io.ktor.network.sockets.SocketAddress,
        onAuthFailed: (String) -> Unit
    ): SecretKeySpec? {
        return try {
            val aMsg = withTimeout(10_000) { socket.receive() }.packet.readText().trim()
            if (!aMsg.startsWith("SRP_A:")) return null
            val clientA = java.math.BigInteger(aMsg.removePrefix("SRP_A:"), 16)

            val session = CryptoManagerDesktop.createServerSession() ?: run {
                onAuthFailed("No password configured on server")
                return null
            }
            if (session.computeSessionKey(clientA) == null) {
                onAuthFailed("Invalid client public key A")
                return null
            }
            socket.send(io.ktor.network.sockets.Datagram(
                io.ktor.utils.io.core.buildPacket { writeText("SRP_SB:${session.salt.toHex()}:${session.serverPublicKey.toString(16)}") },
                clientAddress
            ))

            val m1Msg = withTimeout(10_000) { socket.receive() }.packet.readText().trim()
            if (!m1Msg.startsWith("SRP_M1:")) return null
            val clientM1 = m1Msg.removePrefix("SRP_M1:").hexToBytes()

            if (!session.verifyClientProof(clientA, clientM1)) {
                socket.send(io.ktor.network.sockets.Datagram(
                    io.ktor.utils.io.core.buildPacket { writeText("AUTH_DENIED") }, clientAddress
                ))
                onAuthFailed("Wrong password from $clientAddress")
                return null
            }
            val serverM2 = session.computeServerProof(clientA, clientM1)
            socket.send(io.ktor.network.sockets.Datagram(
                io.ktor.utils.io.core.buildPacket { writeText("SRP_M2:${serverM2.toHex()}") }, clientAddress
            ))
            session.getAesKey()
        } catch (e: Exception) {
            if (e !is CancellationException) println("SRP server error: ${e.message}")
            null
        }
    }

    private suspend fun clientSrpHandshake(
        password: String,
        socket: io.ktor.network.sockets.BoundDatagramSocket,
        serverAddress: io.ktor.network.sockets.SocketAddress,
        onAuthDenied: () -> Unit
    ): SecretKeySpec? {
        return try {
            val session = CryptoManagerDesktop.ClientSession(password)
            socket.send(io.ktor.network.sockets.Datagram(
                io.ktor.utils.io.core.buildPacket { writeText("SRP_A:${session.clientPublicKey.toString(16)}") },
                serverAddress
            ))
            val sbMsg = withTimeout(10_000) { socket.receive() }.packet.readText().trim()
            if (!sbMsg.startsWith("SRP_SB:")) return null
            val parts = sbMsg.removePrefix("SRP_SB:").split(":")
            if (parts.size != 2) return null
            val salt = parts[0].hexToBytes()
            val serverB = java.math.BigInteger(parts[1], 16)
            if (session.computeSessionKey(salt, serverB) == null) return null

            val clientM1 = session.computeClientProof(serverB)
            socket.send(io.ktor.network.sockets.Datagram(
                io.ktor.utils.io.core.buildPacket { writeText("SRP_M1:${clientM1.toHex()}") },
                serverAddress
            ))
            val replyMsg = withTimeout(10_000) { socket.receive() }.packet.readText().trim()
            if (replyMsg == "AUTH_DENIED") { onAuthDenied(); return null }
            if (!replyMsg.startsWith("SRP_M2:")) return null
            val serverM2 = replyMsg.removePrefix("SRP_M2:").hexToBytes()
            if (!session.verifyServerProof(serverM2, serverB, clientM1)) {
                println("SRP: server proof INVALID — possible rogue server!")
                return null
            }
            session.getAesKey()
        } catch (e: Exception) {
            if (e !is CancellationException) println("SRP client error: ${e.message}")
            null
        }
    }

    fun launchServerInstance(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        isPasswordProtected: Boolean = false,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        if (micOutputMixerInfo != null) {
            micReceiverJob = scope.launchMicReceiver(audioSettings, isMulticast, micOutputMixerInfo, micPort)
        }
        startAnnouncingPresence(isMulticast, port, isPasswordProtected)

        streamingJob = scope.launch {
            try {
                // =========================================================
                // FIX 2 — FFmpeg parte subito, prima di aspettare il client.
                // =========================================================
                serverGrabber = buildAndStartGrabber(audioSettings)
                if (serverGrabber == null) {
                    onStatusUpdate("error_virtual_driver_missing", emptyArray())
                    return@launch
                }

                if (isMulticast) {
                    onStatusUpdate("Streaming Multicast on Port %d...", arrayOf(port))

                    MulticastSocket().use { socket ->
                        socket.timeToLive = 4
                        getActiveNetworkInterface()?.let { socket.networkInterface = it }
                        // IP di streaming derivato dall'IP locale (non hardcoded)
                        val streamingIp = resolveStreamingMulticastIp()
                        val group = InetAddress.getByName(streamingIp)

                        val maxBytesPerPacket = audioSettings.bufferSize
                        val maxShortsPerPacket = maxBytesPerPacket / 2
                        val chunkArray = ShortArray(maxShortsPerPacket)
                        val byteBuffer = java.nio.ByteBuffer.allocate(maxBytesPerPacket).apply { order(java.nio.ByteOrder.LITTLE_ENDIAN) }

                        // Multicast con password: CipherSession condivisa
                        var multicastCipherSession: CryptoManagerDesktop.CipherSession? = null
                        if (isPasswordProtected) {
                            val sharedAesKey = SecretKeySpec(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }, "AES")
                            multicastCipherSession = CryptoManagerDesktop.createCipherSession(sharedAesKey)
                            val sharedKeyBytes = sharedAesKey.encoded
                            val sessionPrefix = multicastCipherSession.getSessionPrefix()

                            val authAddr = io.ktor.network.sockets.InetSocketAddress("0.0.0.0", authPort(port))
                            val authKtorSocket = aSocket(SelectorManager(Dispatchers.IO)).udp().bind(authAddr) { reuseAddress = true }
                            launch {
                                while (isActive) {
                                    try {
                                        val helloD = authKtorSocket.receive()
                                        if (helloD.packet.readText().trim() != "HELLO_FROM_CLIENT") continue
                                        val clientAddr = helloD.address
                                        launch {
                                            val srpKey = serverSrpHandshake(authKtorSocket, clientAddr) { msg ->
                                                onStatusUpdate("Auth failed: %s", arrayOf(msg))
                                            }
                                            if (srpKey != null) {
                                                val srpSession = CryptoManagerDesktop.createCipherSession(srpKey)
                                                val encKey = srpSession.encrypt(sharedKeyBytes)
                                                val payload = ByteArray(4 + encKey.size + 4)
                                                payload[0] = (encKey.size shr 24).toByte()
                                                payload[1] = (encKey.size shr 16).toByte()
                                                payload[2] = (encKey.size shr 8).toByte()
                                                payload[3] = encKey.size.toByte()
                                                System.arraycopy(encKey, 0, payload, 4, encKey.size)
                                                System.arraycopy(sessionPrefix, 0, payload, 4 + encKey.size, 4)
                                                authKtorSocket.send(io.ktor.network.sockets.Datagram(
                                                    io.ktor.utils.io.core.buildPacket { writeFully(payload) }, clientAddr
                                                ))
                                                println("AUTH OK (multicast): sent session to $clientAddr")
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                                authKtorSocket.close()
                            }
                        }

                        val plainSessionMulticast = if (!isPasswordProtected) CryptoManagerDesktop.createPlainSession() else null

                        try {
                            while (isActive) {
                                val frame = serverGrabber?.grabSamples()
                                if (frame != null && frame.samples != null) {
                                    val shortBuffer = frame.samples[0] as java.nio.ShortBuffer
                                    shortBuffer.position(0)
                                    while (shortBuffer.hasRemaining()) {
                                        var shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
                                        val channels = audioSettings.channels
                                        shortsToRead = (shortsToRead / channels) * channels
                                        if (shortsToRead <= 0) break
                                        shortBuffer.get(chunkArray, 0, shortsToRead)
                                        val vol = currentServerVolume
                                        if (vol != 1.0f) {
                                            for (i in 0 until shortsToRead) {
                                                var sample = (chunkArray[i] * vol).toInt()
                                                sample = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                                chunkArray[i] = sample.toShort()
                                            }
                                        }
                                        byteBuffer.clear()
                                        byteBuffer.asShortBuffer().put(chunkArray, 0, shortsToRead)
                                        val raw = byteBuffer.array().copyOf(shortsToRead * 2)
                                        val payload = when {
                                            multicastCipherSession != null -> multicastCipherSession.encrypt(raw)
                                            else -> plainSessionMulticast!!.wrap(raw)
                                        }
                                        socket.send(DatagramPacket(payload, payload.size, group, port))
                                    }
                                }
                            }
                        } finally {
                            try {
                                val byeBytes = "BYE".toByteArray()
                                socket.send(DatagramPacket(byeBytes, byeBytes.size, group, port))
                                println("--- Sent BYE to multicast group ---")
                            } catch (_: Exception) {}
                        }
                    }
                } else { // Unicast
                    val localAddress = io.ktor.network.sockets.InetSocketAddress("0.0.0.0", port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        while (isActive) {
                            startAnnouncingPresence(isMulticast = false, port = port, isPasswordProtected)
                            onStatusUpdate("Waiting for Unicast Client on Port %d...", arrayOf(port))

                            val clientDatagram = socket.receive()
                            if (clientDatagram.packet.readText().trim() != CLIENT_HELLO_MESSAGE) continue

                            val clientAddress = clientDatagram.address
                            onStatusUpdate("Client Connected: %s", arrayOf(clientAddress.toString()))
                            stopAnnouncingPresence()

                            // SRP Auth → CipherSession oppure PlainSession
                            var cipherSession: CryptoManagerDesktop.CipherSession? = null
                            var plainSession: CryptoManagerDesktop.PlainSession? = null
                            if (isPasswordProtected) {
                                val aesKey = serverSrpHandshake(socket, clientAddress) { msg ->
                                    onStatusUpdate("Auth failed: %s", arrayOf(msg))
                                }
                                if (aesKey == null) continue
                                cipherSession = CryptoManagerDesktop.createCipherSession(aesKey)
                                socket.send(Datagram(buildPacket { writeFully(cipherSession.getSessionPrefix()) }, clientAddress))
                            } else {
                                socket.send(Datagram(buildPacket { writeText("AUTH_OK") }, clientAddress))
                                plainSession = CryptoManagerDesktop.createPlainSession()
                            }

                            // Drain FFmpeg
                            serverGrabber?.let { grabber ->
                                var staleFrames = 0
                                val drainDeadlineMs = 100L
                                val maxDrainMs = 2000L
                                val overallDeadline = System.currentTimeMillis() + maxDrainMs
                                var lastFrameTime = System.currentTimeMillis()
                                withContext(Dispatchers.IO) {
                                    while (System.currentTimeMillis() < overallDeadline) {
                                        if (System.currentTimeMillis() - lastFrameTime > drainDeadlineMs) break
                                        try {
                                            withTimeout(drainDeadlineMs) {
                                                val staleFrame = withContext(Dispatchers.IO) { grabber.grabSamples() }
                                                if (staleFrame?.samples != null) { staleFrames++; lastFrameTime = System.currentTimeMillis() }
                                            }
                                        } catch (_: TimeoutCancellationException) { break }
                                    }
                                }
                                println("--- Drained $staleFrames stale FFmpeg frames ---")
                            }

                            socket.send(Datagram(buildPacket { writeText("HELLO_ACK") }, clientAddress))

                            val maxBytesPerPacket = audioSettings.bufferSize
                            val maxShortsPerPacket = maxBytesPerPacket / 2
                            val chunkArray = ShortArray(maxShortsPerPacket)
                            val byteBuffer = java.nio.ByteBuffer.allocate(maxBytesPerPacket).apply { order(java.nio.ByteOrder.LITTLE_ENDIAN) }

                            val clientAlive = java.util.concurrent.atomic.AtomicBoolean(true)
                            val pingJob = launch {
                                var failures = 0
                                while (isActive && clientAlive.get()) {
                                    delay(1000)
                                    try { socket.send(Datagram(buildPacket { writeText("PING") }, clientAddress)); failures = 0 }
                                    catch (_: Exception) { if (++failures >= 3) { clientAlive.set(false) } }
                                }
                            }

                            try {
                                while (isActive && clientAlive.get()) {
                                    val frame = serverGrabber?.grabSamples()
                                    if (!clientAlive.get()) break
                                    if (frame != null && frame.samples != null) {
                                        val shortBuffer = frame.samples[0] as java.nio.ShortBuffer
                                        shortBuffer.position(0)
                                        while (shortBuffer.hasRemaining()) {
                                            var shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
                                            val channels = audioSettings.channels
                                            shortsToRead = (shortsToRead / channels) * channels
                                            if (shortsToRead <= 0) break
                                            shortBuffer.get(chunkArray, 0, shortsToRead)
                                            val vol = currentServerVolume
                                            if (vol != 1.0f) {
                                                for (i in 0 until shortsToRead) {
                                                    var sample = (chunkArray[i] * vol).toInt()
                                                    sample = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                                    chunkArray[i] = sample.toShort()
                                                }
                                            }
                                            byteBuffer.clear()
                                            byteBuffer.asShortBuffer().put(chunkArray, 0, shortsToRead)
                                            val raw = byteBuffer.array().copyOf(shortsToRead * 2)
                                            val payload = when {
                                                cipherSession != null -> cipherSession.encrypt(raw)
                                                plainSession != null  -> plainSession.wrap(raw)
                                                else -> raw
                                            }
                                            try {
                                                socket.send(Datagram(buildPacket { writeFully(payload) }, clientAddress))
                                            } catch (_: Exception) { clientAlive.set(false); break }
                                        }
                                    }
                                }
                            } finally {
                                pingJob.cancel()
                                if (clientAlive.get()) {
                                    try { socket.send(Datagram(buildPacket { writeText("BYE") }, clientAddress)) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    println("=== CRITICAL SERVER ERROR ===")
                    t.printStackTrace()
                    onStatusUpdate("Error: %s", arrayOf(t.message ?: t.toString()))
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
        password: String? = null,
        onAuthDenied: (() -> Unit)? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        if (sendMicrophone && micInputMixerInfo != null) {
            micReceiverJob = scope.launchMicSender(audioSettings, serverInfo, micInputMixerInfo, micPort)
        }
        streamingJob = scope.launch {
            var sourceDataLine: SourceDataLine? = null
            try {
                if (!serverInfo.isMulticast) { // Unicast
                    val remoteAddress = io.ktor.network.sockets.InetSocketAddress(serverInfo.ip, serverInfo.port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind().use { socket ->

                        // FIX 1: SourceDataLine aperta prima dell'handshake
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        onStatusUpdate("status_contacting_server", arrayOf(remoteAddress))
                        socket.send(Datagram(buildPacket { writeText(CLIENT_HELLO_MESSAGE) }, remoteAddress))

                        // SRP Auth → CipherSession oppure PlainSession
                        var cipherSession: CryptoManagerDesktop.CipherSession? = null
                        var plainSession: CryptoManagerDesktop.PlainSession? = null
                        if (serverInfo.isPasswordProtected) {
                            val pwd = password
                            if (pwd.isNullOrEmpty()) { onStatusUpdate("status_auth_required", emptyArray()); return@use }
                            onStatusUpdate("status_authenticating", emptyArray())
                            val aesKey = clientSrpHandshake(pwd, socket, remoteAddress) {
                                onStatusUpdate("status_auth_denied", emptyArray()); onAuthDenied?.invoke()
                            }
                            if (aesKey == null) return@use
                            // Ricevi sessionPrefix (4 byte) dal server
                            val prefixBytes = withTimeout(5_000) { socket.receive() }.let {
                                val b = ByteArray(it.packet.remaining.toInt()); it.packet.readFully(b); b
                            }
                            if (prefixBytes.size != 4) { onStatusUpdate("status_handshake_failed", emptyArray()); return@use }
                            cipherSession = CryptoManagerDesktop.createCipherSession(aesKey).createPeerSession(prefixBytes)
                        } else {
                            val authMsg = withTimeout(10_000) { socket.receive() }.packet.readText().trim()
                            if (authMsg != "AUTH_OK") { onStatusUpdate("status_handshake_failed", emptyArray()); return@use }
                            plainSession = CryptoManagerDesktop.createPlainSession()
                        }

                        onStatusUpdate("status_waiting_ack", emptyArray())
                        val ackMsg = withTimeout(15_000) { socket.receive() }.packet.readText().trim()
                        if (ackMsg != "HELLO_ACK") { onStatusUpdate("status_handshake_failed", emptyArray()); return@use }

                        onStatusUpdate("status_connected_streaming_from", arrayOf(remoteAddress))

                        var lastPingReceived = System.currentTimeMillis()
                        val pingTimeoutMs = 3000L
                        val serverAlive = java.util.concurrent.atomic.AtomicBoolean(true)

                        val watchdogJob = launch {
                            while (isActive && serverAlive.get()) {
                                delay(1000)
                                if (System.currentTimeMillis() - lastPingReceived > pingTimeoutMs) {
                                    onStatusUpdate("status_server_disconnected", emptyArray())
                                    serverAlive.set(false)
                                }
                            }
                        }

                        try {
                            while (isActive && serverAlive.get()) {
                                val rawBytes = socket.receive().let { val b = ByteArray(it.packet.remaining.toInt()); it.packet.readFully(b); b }
                                val text = rawBytes.toString(Charsets.UTF_8).trim()
                                when (text) {
                                    "PING" -> lastPingReceived = System.currentTimeMillis()
                                    "BYE"  -> { onStatusUpdate("status_server_disconnected", emptyArray()); serverAlive.set(false) }
                                    else   -> {
                                        // receive() gestisce il jitter buffer e ritorna frame in ordine
                                        val frames = cipherSession?.receive(rawBytes)
                                            ?: plainSession?.receive(rawBytes)
                                            ?: listOf(rawBytes)
                                        for (audio in frames) {
                                            if (audio.isNotEmpty()) sourceDataLine?.write(audio, 0, audio.size)
                                        }
                                    }
                                }
                            }
                        } finally {
                            watchdogJob.cancel()
                        }
                    }
                } else { // Multicast
                    // Auth unicast su porta+1 prima di unirsi al gruppo
                    var cipherSession: CryptoManagerDesktop.CipherSession? = null
                    if (serverInfo.isPasswordProtected) {
                        val pwd = password
                        if (pwd.isNullOrEmpty()) { onStatusUpdate("status_auth_required", emptyArray()); return@launch }
                        onStatusUpdate("status_authenticating", emptyArray())
                        val authSocket = aSocket(SelectorManager(Dispatchers.IO)).udp().bind()
                        val authAddress = io.ktor.network.sockets.InetSocketAddress(serverInfo.ip, authPort(serverInfo.port))
                        try {
                            authSocket.send(Datagram(buildPacket { writeText(CLIENT_HELLO_MESSAGE) }, authAddress))
                            val srpKey = clientSrpHandshake(pwd, authSocket, authAddress) {
                                onStatusUpdate("status_auth_denied", emptyArray()); onAuthDenied?.invoke()
                            }
                            if (srpKey == null) return@launch

                            // Ricevi [encKeyLen 4B][encKey][sessionPrefix 4B] dal server
                            val payload = withTimeout(10_000) { authSocket.receive() }.let {
                                val b = ByteArray(it.packet.remaining.toInt()); it.packet.readFully(b); b
                            }
                            val encKeyLen = ((payload[0].toInt() and 0xFF) shl 24) or
                                    ((payload[1].toInt() and 0xFF) shl 16) or
                                    ((payload[2].toInt() and 0xFF) shl 8)  or
                                    (payload[3].toInt() and 0xFF)
                            if (payload.size < 4 + encKeyLen + 4) { onStatusUpdate("status_auth_denied", emptyArray()); return@launch }
                            val srpSession = CryptoManagerDesktop.createCipherSession(srpKey)
                            val sharedKeyBytes = srpSession.decrypt(payload.copyOfRange(4, 4 + encKeyLen)) ?: run {
                                onStatusUpdate("status_auth_denied", emptyArray()); return@launch
                            }
                            val sessionPrefix = payload.copyOfRange(4 + encKeyLen, 4 + encKeyLen + 4)
                            val sharedAesKey = SecretKeySpec(sharedKeyBytes, "AES")
                            cipherSession = CryptoManagerDesktop.createCipherSession(sharedAesKey).createPeerSession(sessionPrefix)
                        } finally {
                            authSocket.close()
                        }
                    }

                    onStatusUpdate("status_joining_multicast", arrayOf(serverInfo.port))
                    val groupAddress = InetAddress.getByName(DISCOVERY_MULTICAST_IP)
                    MulticastSocket(serverInfo.port).use { socket ->
                        socket.joinGroup(groupAddress)
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        onStatusUpdate("status_multicast_streaming", arrayOf(serverInfo.port))
                        // Buffer: audio + SEQ (8B) + IV (12B) + GCM tag (16B)
                        val buf = ByteArray(audioSettings.bufferSize * 2 + 8 + 12 + 16)
                        val packet = DatagramPacket(buf, buf.size)
                        val plainSession = if (cipherSession == null) CryptoManagerDesktop.createPlainSession() else null

                        while (isActive) {
                            socket.receive(packet)
                            val rawBytes = packet.data.copyOf(packet.length)
                            if (packet.length == 3 && String(rawBytes, Charsets.UTF_8) == "BYE") {
                                onStatusUpdate("status_server_disconnected", emptyArray()); break
                            }
                            val frames = cipherSession?.receive(rawBytes)
                                ?: plainSession?.receive(rawBytes)
                                ?: listOf(rawBytes)
                            for (audio in frames) {
                                if (audio.isNotEmpty()) sourceDataLine?.write(audio, 0, audio.size)
                            }
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
                sourceDataLine?.drain()
                sourceDataLine?.stop()
                sourceDataLine?.close()
            }
        }
    }

    // =========================================================
    // FIX 3 — Buffer ridotto da *4 a *2 per abbassare la latenza
    // strutturale della SourceDataLine in modalità unicast.
    // Il valore *4 aggiungeva fino a (bufferSize*4) / (sampleRate*channels*2)
    // secondi di ritardo fisso prima che il primo campione fosse riprodotto.
    // *2 mantiene abbastanza headroom per assorbire jitter di rete
    // senza introdurre latenza eccessiva.
    // =========================================================
    private fun prepareSourceDataLine(mixerInfo: Mixer.Info, audioSettings: AudioSettings_V1): SourceDataLine? {
        val mixer = AudioSystem.getMixer(mixerInfo)
        val format = audioSettings.toAudioFormat()
        val dataLineInfo = DataLine.Info(SourceDataLine::class.java, format)
        if (!mixer.isLineSupported(dataLineInfo)) return null

        val frameSize = format.frameSize
        val adjustedBufferSize = (audioSettings.bufferSize / frameSize) * frameSize
        val sourceDataLine = mixer.getLine(dataLineInfo) as SourceDataLine

        // FIX 3: Ridotto da *4 a *2 — meno buffer = meno latenza strutturale
        sourceDataLine.open(format, adjustedBufferSize * 2)
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

        restoreLinuxAudioRouting()
    }

    fun terminateAllServices() {
        scope.cancel()
    }
}

fun main() = application {
    System.setProperty("java.net.preferIPv4Stack", "true")
    NetworkHandler_v1.setupLinuxVirtualCable()
    org.bytedeco.javacv.FFmpegLogCallback.set()
    org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all()
    val loadedSettings = SettingsRepository.loadSettings()
    var appSettings by remember { mutableStateOf(loadedSettings.app) }
    var audioSettings by remember { mutableStateOf(loadedSettings.audio) }
    var streamingPort by remember { mutableStateOf(loadedSettings.streamingPort) }
    var micPort by remember { mutableStateOf(loadedSettings.micPort) }

    val isWindowsOS = remember { System.getProperty("os.name").lowercase().contains("win") }
    var serverVolume by remember { mutableStateOf(1f) }

    LaunchedEffect(serverVolume) {
        NetworkHandler_v1.setServerVolume(serverVolume)
    }

    LaunchedEffect(appSettings, audioSettings, streamingPort, micPort) {
        SettingsRepository.saveSettings(AllSettings(appSettings, audioSettings, streamingPort, micPort))
    }

    var showSettings by remember { mutableStateOf(false) }
    var isServer by remember { mutableStateOf(true) }
    val discoveredDevices = remember { mutableStateMapOf<String, ServerInfo>() }
    var connectionStatus by remember { mutableStateOf(Strings.get("status_inactive")) }
    var isStreaming by remember { mutableStateOf(false) }
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
        virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus()
    }

    val useDarkTheme = when (appSettings.theme) {
        Theme.Light -> false
        Theme.Dark -> true
        Theme.System -> isSystemInDarkTheme()
    }

    val windowState = rememberWindowState(size = DpSize(600.dp, 800.dp))

    Window(
        onCloseRequest = {
            NetworkHandler_v1.restoreLinuxAudioRouting()
            exitApplication()
        },
        state = windowState,
        undecorated = true
    ) {
        val customColor = appSettings.customThemeColor?.toULong()?.let { Color(it) }
        val currentColorScheme = if (customColor != null) {
            MaterialYouGenerator.generateDynamicColorScheme(customColor, useDarkTheme)
        } else {
            if (useDarkTheme) darkColorScheme() else lightColorScheme()
        }

        MaterialTheme(colorScheme = currentColorScheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                            serverVolume = serverVolume,
                            onServerVolumeChange = { serverVolume = it },
                            isWindowsOS = isWindowsOS,
                            virtualDriverStatus = virtualDriverStatus,
                            onDismissPrivacyBanner = { dontShowAgain ->
                                if (dontShowAgain) appSettings = appSettings.copy(hideWindowsPrivacyBanner = true)
                            },
                            onDismissRoutingBanner = { dontShowAgain ->
                                if (dontShowAgain) appSettings = appSettings.copy(hideWindowsRoutingBanner = true)
                            },
                            onConnectManual = { ip, pwd ->
                                isStreaming = true
                                connectionStatus = "Connecting manually to $ip..."
                                val port = streamingPort.toIntOrNull() ?: 9090
                                val mic = micPort.toIntOrNull() ?: 9092
                                val manualServerInfo = ServerInfo(ip, false, port)
                                NetworkHandler_v1.endDeviceDiscovery()
                                NetworkHandler_v1.launchClientInstance(
                                    audioSettings, manualServerInfo, selectedOutputDevice!!,
                                    sendMicrophone, selectedClientMic, mic
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
                            onStartStreaming = { isProtected ->
                                isStreaming = true
                                connectionStatus = "Starting Server..."
                                val port = streamingPort.toIntOrNull() ?: 9090
                                val mic = micPort.toIntOrNull() ?: 9092

                                // Aggiunto "isProtected" come sesto parametro
                                NetworkHandler_v1.launchServerInstance(
                                    audioSettings, port, isMulticastMode, selectedServerMicOutput, mic, isProtected
                                ) { key, args ->
                                    if (key == "error_virtual_driver_missing") {
                                        isStreaming = false
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
                            onConnectToServer = { serverInfo, pwd ->
                                isStreaming = true
                                val mic = micPort.toIntOrNull() ?: 9092
                                NetworkHandler_v1.endDeviceDiscovery()
                                NetworkHandler_v1.launchClientInstance(
                                    audioSettings, serverInfo, selectedOutputDevice!!,
                                    sendMicrophone, selectedClientMic, mic
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
                            onClose = { showSettings = false },
                            onCustomColorChange = { color -> appSettings = appSettings.copy(customThemeColor = color) }
                        )
                    }
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