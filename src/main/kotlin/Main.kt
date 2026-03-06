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
    private const val MULTICAST_GROUP_IP = "239.255.0.1"
    private const val DISCOVERY_MESSAGE = "WIFI_AUDIO_STREAMER_DISCOVERY"

    private var originalLinuxSink: String? = null
    private var originalLinuxSource: String? = null

    private fun getPactlDefault(type: String): String? {
        // Prima prova con pactl info che è più affidabile di get-default-*
        try {
            val info = ProcessBuilder("pactl", "info").start()
            val infoOutput = info.inputStream.bufferedReader().readText()
            if (info.waitFor() == 0) {
                val prefix = if (type == "sink") "Default Sink: " else "Default Source: "
                val result = infoOutput.lines()
                    .find { it.trimStart().startsWith(prefix) }
                    ?.substringAfter(prefix)?.trim()
                if (!result.isNullOrEmpty()) return result
            }
        } catch (e: Exception) {}

        // Fallback con get-default-*
        try {
            val direct = ProcessBuilder("pactl", "get-default-$type").start()
            val directOutput = direct.inputStream.bufferedReader().readText().trim()
            if (direct.waitFor() == 0 && directOutput.isNotEmpty()) return directOutput
        } catch (e: Exception) {}

        return null
    }

    private fun routeLinuxAudioToVirtualCable() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return

        val currentSink = getPactlDefault("sink")
        val currentSource = getPactlDefault("source")

        // Salva SOLO se il sink attuale non è già il VirtualCable
        // (evita di sovrascrivere il valore salvato con "VirtualCable" stesso)
        if (currentSink != null && !currentSink.contains("VirtualCable", ignoreCase = true)) {
            originalLinuxSink = currentSink
        }
        if (currentSource != null && !currentSource.contains("VirtualCable", ignoreCase = true)) {
            originalLinuxSource = currentSource
        }

        println("--- Linux: Sink originale salvato: sink='$originalLinuxSink', source='$originalLinuxSource' ---")

        try {
            ProcessBuilder("pactl", "set-default-sink", "VirtualCable").start().waitFor()
            ProcessBuilder("pactl", "set-default-source", "VirtualCable.monitor").start().waitFor()
            println("--- Linux Audio instradato su VirtualCable ---")
        } catch (e: Exception) {
            println("--- Errore routing VirtualCable: ${e.message} ---")
        }
    }

    fun restoreLinuxAudioRouting() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return

        val sink = originalLinuxSink
        val source = originalLinuxSource

        println("--- Linux: Tentativo ripristino audio: sink='$sink', source='$source' ---")

        if (sink == null && source == null) {
            println("--- Linux: Nessun sink originale salvato, nulla da ripristinare ---")
            return
        }

        try {
            sink?.let {
                ProcessBuilder("pactl", "set-default-sink", it).start().waitFor()
                println("--- Linux: Sink ripristinato a '$it' ---")
            }
            source?.let {
                ProcessBuilder("pactl", "set-default-source", it).start().waitFor()
                println("--- Linux: Source ripristinato a '$it' ---")
            }
            // Azzera dopo il restore per non ripetere operazioni al secondo stop
            originalLinuxSink = null
            originalLinuxSource = null
        } catch (e: Exception) {
            println("--- Errore nel ripristino audio Linux: ${e.message} ---")
        }
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

            // Salva subito il sink/source reale PRIMA di fare qualsiasi modifica,
            // così anche se VirtualCable esiste già lo salviamo correttamente
            val currentSink = getPactlDefault("sink")
            val currentSource = getPactlDefault("source")
            if (currentSink != null && !currentSink.contains("VirtualCable", ignoreCase = true)) {
                originalLinuxSink = currentSink
            }
            if (currentSource != null && !currentSource.contains("VirtualCable", ignoreCase = true)) {
                originalLinuxSource = currentSource
            }
            println("--- Linux: Sink reale salvato all'avvio: sink='$originalLinuxSink', source='$originalLinuxSource' ---")

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

                val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
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

    fun startAnnouncingPresence(isMulticast: Boolean, port: Int) {
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch {
            val hostname = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "Desktop-PC" }
            val mode = if (isMulticast) "MULTICAST" else "UNICAST"
            val message = "$DISCOVERY_MESSAGE;$hostname;$mode;$port"
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)

            MulticastSocket().use { socket ->
                socket.timeToLive = 4
                getActiveNetworkInterface()?.let { socket.networkInterface = it }
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
                MulticastSocket(micPort).apply {
                    joinGroup(InetAddress.getByName(MULTICAST_GROUP_IP))
                    soTimeout = 1000 // <-- AGGIUNTO: Evita il blocco infinito
                }
            } else {
                DatagramSocket(micPort).apply {
                    soTimeout = 1000 // <-- AGGIUNTO: Evita il blocco infinito
                }
            }

            while (isActive) {
                try {
                    socket.receive(packet)
                    if (packet.length > 0) micOutputLine.write(packet.data, 0, packet.length)
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout normale, permette al while(isActive) di controllare se deve spegnersi
                    continue
                }
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
                deviceName = ":BlackHole 2ch"
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
                // FORZA IL TEMPO REALE SU MAC
                setOption("probesize", "32")
                setOption("analyzeduration", "0")
                setOption("fflags", "nobuffer")

                sampleRate = audioSettings.sampleRate.toInt()
                audioChannels = audioSettings.channels
                sampleFormat = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                start()
            }
        } catch (e: Exception) {
            println("=== FFMPEG PRE-WARM FAILED ===")
            e.printStackTrace()
            null
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
                if (isMulticast) {
                    // In Multicast il server trasmette sempre a prescindere da chi ascolta,
                    // quindi avviamo subito la cattura audio.
                    serverGrabber = buildAndStartGrabber(audioSettings)
                    if (serverGrabber == null) {
                        onStatusUpdate("error_virtual_driver_missing", emptyArray())
                        return@launch
                    }

                    onStatusUpdate("Streaming Multicast on Port %d...", arrayOf(port))

                    MulticastSocket().use { socket ->
                        socket.timeToLive = 4
                        getActiveNetworkInterface()?.let { socket.networkInterface = it }
                        val group = InetAddress.getByName(MULTICAST_GROUP_IP)

                        val maxBytesPerPacket = audioSettings.bufferSize
                        val maxShortsPerPacket = maxBytesPerPacket / 2
                        val chunkArray = ShortArray(maxShortsPerPacket)
                        val byteBuffer = java.nio.ByteBuffer.allocate(maxBytesPerPacket).apply {
                            order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        }

                        try {
                            while (isActive) {
                                val frame = serverGrabber?.grabSamples()
                                if (frame != null && frame.samples != null) {
                                    val shortBuffer = frame.samples[0] as java.nio.ShortBuffer
                                    shortBuffer.position(0)
                                    while (shortBuffer.hasRemaining()) {
                                        val shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
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
                                        val bytesToSend = shortsToRead * 2
                                        socket.send(DatagramPacket(byteBuffer.array(), bytesToSend, group, port))
                                    }
                                }
                            }
                        } finally {
                            try {
                                val byeBytes = "BYE".toByteArray()
                                socket.send(DatagramPacket(byeBytes, byeBytes.size, group, port))
                                println("--- Sent BYE to multicast group $MULTICAST_GROUP_IP:$port ---")
                            } catch (_: Exception) {}
                        }
                    }
                } else { // Unicast
                    val localAddress = InetSocketAddress("0.0.0.0", port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        while (isActive) {
                            startAnnouncingPresence(isMulticast = false, port = port)
                            onStatusUpdate("Waiting for Unicast Client on Port %d...", arrayOf(port))

                            val clientDatagram = socket.receive()
                            if (clientDatagram.packet.readText().trim() != CLIENT_HELLO_MESSAGE) continue

                            val clientAddress = clientDatagram.address
                            onStatusUpdate("Client Connected: %s", arrayOf(clientAddress.toString()))
                            stopAnnouncingPresence()

                            // --- AVVIO FFMPEG SOLO DOPO LA CONNESSIONE DEL CLIENT ---
                            // In questo modo i buffer Windows non si riempiono a vuoto nell'attesa.
                            serverGrabber = buildAndStartGrabber(audioSettings)
                            if (serverGrabber == null) {
                                onStatusUpdate("error_virtual_driver_missing", emptyArray())
                                break // Esce dal loop se manca il driver
                            }

                            socket.send(Datagram(buildPacket { writeText("HELLO_ACK") }, clientAddress))

                            val maxBytesPerPacket = audioSettings.bufferSize
                            val maxShortsPerPacket = maxBytesPerPacket / 2
                            val chunkArray = ShortArray(maxShortsPerPacket)
                            val byteBuffer = java.nio.ByteBuffer.allocate(maxBytesPerPacket).apply {
                                order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            }

                            val clientAlive = java.util.concurrent.atomic.AtomicBoolean(true)

                            val pingJob = launch {
                                var failures = 0
                                while (isActive && clientAlive.get()) {
                                    delay(1000)
                                    try {
                                        socket.send(Datagram(buildPacket { writeText("PING") }, clientAddress))
                                        failures = 0
                                    } catch (_: Exception) {
                                        failures++
                                        if (failures >= 3) {
                                            println("--- PING failed 3 times, client considered gone ---")
                                            clientAlive.set(false)
                                        }
                                    }
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
                                            val shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
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
                                            val bytesToSend = shortsToRead * 2
                                            val packet = buildPacket { writeFully(byteBuffer.array(), 0, bytesToSend) }
                                            try {
                                                socket.send(Datagram(packet, clientAddress))
                                            } catch (_: Exception) {
                                                clientAlive.set(false)
                                                break
                                            }
                                        }
                                    }
                                }
                            } finally {
                                pingJob.cancel()
                                if (clientAlive.get()) {
                                    try {
                                        socket.send(Datagram(buildPacket { writeText("BYE") }, clientAddress))
                                        println("--- Sent BYE to $clientAddress ---")
                                    } catch (_: Exception) {}
                                }

                                // --- CHIUDIAMO FFMPEG QUANDO IL CLIENT SI SCOLLEGA ---
                                // Così se torna in "Waiting for Unicast Client", smettiamo di registrare.
                                serverGrabber?.stop()
                                serverGrabber?.release()
                                serverGrabber = null
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    if (serverGrabber != null) {
                        println("=== CRITICAL SERVER ERROR ===")
                        t.printStackTrace()
                        onStatusUpdate("Error: %s", arrayOf(t.message ?: t.toString()))
                    }
                }
            } finally {
                stopAnnouncingPresence()
                serverGrabber?.stop()
                serverGrabber?.release()
                serverGrabber = null
                restoreLinuxAudioRouting()
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
            micReceiverJob = scope.launchMicSender(audioSettings, serverInfo, micInputMixerInfo, micPort)
        }
        streamingJob = scope.launch {
            var sourceDataLine: SourceDataLine? = null
            try {
                if (!serverInfo.isMulticast) { // Unicast
                    val remoteAddress = InetSocketAddress(serverInfo.ip, serverInfo.port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind().use { socket ->

                        // =========================================================
                        // FIX 1 — Apri la SourceDataLine PRIMA di mandare HELLO.
                        // Così quando arriva l'ACK la linea audio è già pronta
                        // e non si perde tempo ad inizializzarla dopo l'handshake.
                        // =========================================================
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        onStatusUpdate("status_contacting_server", arrayOf(remoteAddress))
                        val helloPacket = buildPacket { writeText(CLIENT_HELLO_MESSAGE) }
                        socket.send(Datagram(helloPacket, remoteAddress))

                        onStatusUpdate("status_waiting_ack", emptyArray())
                        val ackDatagram = withTimeout(15000) { socket.receive() }
                        if (ackDatagram.packet.readText().trim() != "HELLO_ACK") {
                            onStatusUpdate("status_handshake_failed", emptyArray())
                            return@use
                        }

                        onStatusUpdate("status_connected_streaming_from", arrayOf(remoteAddress))

                        val buffer = ByteArray(audioSettings.bufferSize * 2)
                        var lastPingReceived = System.currentTimeMillis()
                        val pingTimeoutMs = 3000L
                        val serverAlive = java.util.concurrent.atomic.AtomicBoolean(true)

                        // Watchdog: se non arriva PING per 3s il server è sparito
                        val watchdogJob = launch {
                            while (isActive && serverAlive.get()) {
                                delay(1000)
                                if (System.currentTimeMillis() - lastPingReceived > pingTimeoutMs) {
                                    println("--- Server timeout: no PING for ${pingTimeoutMs}ms ---")
                                    onStatusUpdate("status_server_disconnected", emptyArray())
                                    serverAlive.set(false)
                                    break
                                }
                            }
                        }

                        try {
                            while (isActive && serverAlive.get()) {
                                val datagram = socket.receive()
                                val bytes = ByteArray(datagram.packet.remaining.toInt())
                                datagram.packet.readFully(bytes)
                                val text = bytes.toString(Charsets.UTF_8).trim()

                                when (text) {
                                    "PING" -> lastPingReceived = System.currentTimeMillis()
                                    "BYE"  -> {
                                        println("--- Received BYE from server ---")
                                        onStatusUpdate("status_server_disconnected", emptyArray())
                                        serverAlive.set(false)
                                        break
                                    }
                                    else -> if (bytes.isNotEmpty()) sourceDataLine?.write(bytes, 0, bytes.size)
                                }
                            }
                        } finally {
                            watchdogJob.cancel()
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
                            if (packet.length == 3 && String(packet.data, 0, 3, Charsets.UTF_8) == "BYE") {
                                println("--- Received BYE from multicast server ---")
                                onStatusUpdate("status_server_disconnected", emptyArray())
                                break
                            }
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

        // Ferma la cattura per sbloccare eventuali chiamate bloccanti di grabSamples(),
        // ma NON chiamare release() e non settare serverGrabber a null qui!
        serverGrabber?.stop()

        // Ora il job può terminare. La memoria verrà liberata dal blocco finally
        // all'interno di launchServerInstance.
        streamingJob?.cancelAndJoin()
        micReceiverJob?.cancelAndJoin()

        streamingJob = null
        micReceiverJob = null
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
            scope.launch {
                // stopCurrentStream() ferma FFmpeg e alla fine chiama restoreLinuxAudioRouting()
                // È importante che FFmpeg sia già fermo quando si fa il restore,
                // altrimenti il sink torna subito su VirtualCable
                NetworkHandler_v1.stopCurrentStream()
                exitApplication()
            }
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
                            onConnectManual = { ip ->
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
                            onStartStreaming = {
                                isStreaming = true
                                connectionStatus = "Starting Server..."
                                val port = streamingPort.toIntOrNull() ?: 9090
                                val mic = micPort.toIntOrNull() ?: 9092
                                NetworkHandler_v1.launchServerInstance(
                                    audioSettings, port, isMulticastMode, selectedServerMicOutput, mic
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
                            onConnectToServer = { serverInfo ->
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