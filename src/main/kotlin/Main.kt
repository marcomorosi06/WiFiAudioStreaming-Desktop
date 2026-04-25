/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

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
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.collectAsState
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

// ─────────────────────────────────────────────────────────────────────────────
// Theme
// ─────────────────────────────────────────────────────────────────────────────

object ThemeEngine {
    fun pickImageAndExtractColor(): Long? {
        return try {
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
            Color(awtColor.red, awtColor.green, awtColor.blue).value.toLong()
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
        val h       = hsb[0]
        val s       = hsb[1].coerceAtLeast(0.15f)
        val b       = hsb[2]
        val bFactor  = b.coerceIn(0.5f, 1.0f)
        val bgFactor = b.coerceIn(0.85f, 1.0f)
        val tH = (h + 0.15f) % 1f

        fun fromHsb(hue: Float, sat: Float, bright: Float, factor: Float = 1f): Color =
            Color(java.awt.Color.HSBtoRGB(hue % 1f, sat.coerceIn(0f, 1f), (bright * factor).coerceIn(0f, 1f)))

        return if (!isDark) lightColorScheme(
            primary                = fromHsb(h,  s * 0.9f,  0.4f,  bFactor),
            onPrimary              = Color.White,
            primaryContainer       = fromHsb(h,  s * 0.4f,  0.9f,  bFactor),
            onPrimaryContainer     = fromHsb(h,  s * 1.0f,  0.15f),
            secondary              = fromHsb(h,  s * 0.3f,  0.45f, bFactor),
            onSecondary            = Color.White,
            secondaryContainer     = fromHsb(h,  s * 0.2f,  0.92f, bFactor),
            onSecondaryContainer   = fromHsb(h,  s * 0.5f,  0.15f),
            tertiary               = fromHsb(tH, s * 0.4f,  0.45f, bFactor),
            onTertiary             = Color.White,
            tertiaryContainer      = fromHsb(tH, s * 0.25f, 0.92f, bFactor),
            onTertiaryContainer    = fromHsb(tH, s * 0.6f,  0.15f),
            background             = fromHsb(h,  s * 0.05f, 0.98f, bgFactor),
            onBackground           = fromHsb(h,  s * 0.1f,  0.1f),
            surface                = fromHsb(h,  s * 0.05f, 0.98f, bgFactor),
            onSurface              = fromHsb(h,  s * 0.1f,  0.1f),
            surfaceVariant         = fromHsb(h,  s * 0.1f,  0.9f,  bgFactor),
            onSurfaceVariant       = fromHsb(h,  s * 0.15f, 0.3f),
            surfaceContainerLowest = fromHsb(h,  s * 0.02f, 1.0f,  bgFactor),
            surfaceContainerLow    = fromHsb(h,  s * 0.05f, 0.96f, bgFactor),
            surfaceContainer       = fromHsb(h,  s * 0.08f, 0.94f, bgFactor),
            surfaceContainerHigh   = fromHsb(h,  s * 0.1f,  0.92f, bgFactor),
            surfaceContainerHighest= fromHsb(h,  s * 0.12f, 0.90f, bgFactor),
            outline                = fromHsb(h,  s * 0.1f,  0.5f,  bFactor),
            outlineVariant         = fromHsb(h,  s * 0.1f,  0.8f,  bFactor)
        ) else darkColorScheme(
            primary                = fromHsb(h,  s * 0.7f,  0.85f, bFactor),
            onPrimary              = fromHsb(h,  s * 1.0f,  0.2f),
            primaryContainer       = fromHsb(h,  s * 0.8f,  0.3f,  bFactor),
            onPrimaryContainer     = fromHsb(h,  s * 0.4f,  0.9f),
            secondary              = fromHsb(h,  s * 0.4f,  0.8f,  bFactor),
            onSecondary            = fromHsb(h,  s * 0.6f,  0.2f),
            secondaryContainer     = fromHsb(h,  s * 0.4f,  0.3f,  bFactor),
            onSecondaryContainer   = fromHsb(h,  s * 0.2f,  0.9f),
            tertiary               = fromHsb(tH, s * 0.5f,  0.8f,  bFactor),
            onTertiary             = fromHsb(tH, s * 0.7f,  0.2f),
            tertiaryContainer      = fromHsb(tH, s * 0.5f,  0.3f,  bFactor),
            onTertiaryContainer    = fromHsb(tH, s * 0.2f,  0.9f),
            background             = fromHsb(h,  s * 0.15f, 0.10f, bFactor),
            onBackground           = fromHsb(h,  s * 0.1f,  0.9f),
            surface                = fromHsb(h,  s * 0.15f, 0.10f, bFactor),
            onSurface              = fromHsb(h,  s * 0.1f,  0.9f),
            surfaceVariant         = fromHsb(h,  s * 0.20f, 0.25f, bFactor),
            onSurfaceVariant       = fromHsb(h,  s * 0.15f, 0.8f),
            surfaceContainerLowest = fromHsb(h,  s * 0.15f, 0.05f, bFactor),
            surfaceContainerLow    = fromHsb(h,  s * 0.15f, 0.12f, bFactor),
            surfaceContainer       = fromHsb(h,  s * 0.15f, 0.15f, bFactor),
            surfaceContainerHigh   = fromHsb(h,  s * 0.15f, 0.18f, bFactor),
            surfaceContainerHighest= fromHsb(h,  s * 0.15f, 0.22f, bFactor),
            outline                = fromHsb(h,  s * 0.15f, 0.6f,  bFactor),
            outlineVariant         = fromHsb(h,  s * 0.15f, 0.3f,  bFactor)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

enum class Theme { Light, Dark, System }

/**
 * I protocolli che il server può offrire in parallelo.
 * WFAS è sempre attivo (è il protocollo nativo).
 * RTP e HTTP sono opzionali e configurabili nelle impostazioni.
 */
enum class StreamingProtocol { WFAS, RTP, HTTP }

/**
 * Capacità dichiarate dal server nel beacon di discovery.
 * Il client usa questa struttura per scegliere il protocollo migliore.
 */
data class ServerCapabilities(
    val protocols: Set<StreamingProtocol>,
    val httpPort: Int?,
    val safariMode: Boolean = false
)

/**
 * Informazioni di un server scoperto via discovery.
 * [capabilities] è null per server legacy che supportano solo WFAS.
 */
data class ServerInfo(
    val ip: String,
    val isMulticast: Boolean,
    val port: Int,
    val capabilities: ServerCapabilities? = null,
    val lastSeen: Long = System.currentTimeMillis()
)

data class AudioSettings_V1(
    val sampleRate: Float,
    val bitDepth: Int,
    val channels: Int,
    val bufferSize: Int
) {
    fun toAudioFormat(): AudioFormat = AudioFormat(sampleRate, bitDepth, channels, true, false)
}

sealed class VirtualDriverStatus {
    object Ok : VirtualDriverStatus()
    data class Missing(val driverName: String, val downloadUrl: String) : VirtualDriverStatus()
    data class LinuxActionRequired(val message: String, val commands: String) : VirtualDriverStatus()
}

object AutostartManager {
    fun getExecutablePath(): String {
        val os = System.getProperty("os.name").lowercase()

        if (os.contains("linux")) {
            val debPath = File("/opt/wifi-audio-streaming/bin/wifi-audio-streaming")
            if (debPath.exists() && debPath.canExecute()) {
                return debPath.absolutePath
            }
        }

        val jpackagePath = System.getProperty("jpackage.app-path")
        if (!jpackagePath.isNullOrEmpty()) return jpackagePath

        val processPath = ProcessHandle.current().info().command().orElse("")
        if (processPath.isNotEmpty() && !processPath.contains("java.exe") && !processPath.contains("javaw.exe") && !processPath.endsWith("java")) {
            return processPath
        }

        return try {
            val uri = AutostartManager::class.java.protectionDomain.codeSource.location.toURI()
            val path = File(uri).absolutePath
            val isWin = os.contains("win")

            if (path.endsWith(".jar")) {
                val javaCmd = if (isWin) "javaw" else "java"
                "$javaCmd -jar \"$path\""
            } else {
                path
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun toggleAutostart(enable: Boolean): String? {
        val os = System.getProperty("os.name").lowercase()
        val exePath = getExecutablePath()
        if (exePath.isEmpty()) return "Error: Executable path not found."

        val appName = "WiFiAudioStreamer"

        return try {
            if (os.contains("win")) {
                val regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                val command = if (enable) {
                    arrayOf("reg", "add", regKey, "/v", appName, "/t", "REG_SZ", "/d", "\"$exePath\"", "/f")
                } else {
                    arrayOf("reg", "delete", regKey, "/v", appName, "/f")
                }

                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) "Registry Error: $output" else null
            } else if (os.contains("mac")) {
                val plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n<dict>\n<key>Label</key>\n<string>com.wifiaudiostreaming</string>\n<key>ProgramArguments</key>\n<array><string>$exePath</string></array>\n<key>RunAtLoad</key>\n<true/>\n</dict>\n</plist>"
                val dir = File(System.getProperty("user.home"), "Library/LaunchAgents")
                val file = File(dir, "com.wifiaudiostreaming.plist")
                if (enable) {
                    dir.mkdirs()
                    file.writeText(plist)
                } else {
                    if (file.exists()) file.delete()
                }
                null
            } else {
                val desktopContent = "[Desktop Entry]\nType=Application\nExec=\"$exePath\"\nHidden=false\nNoDisplay=false\nTerminal=false\nX-GNOME-Autostart-enabled=true\nName=WiFi Audio Streamer\nComment=Start WiFi Audio Streamer on login"
                val dir = File(System.getProperty("user.home"), ".config/autostart")
                val file = File(dir, "wifiaudiostreaming.desktop")
                if (enable) {
                    dir.mkdirs()
                    file.writeText(desktopContent)
                    "Autostart ENABLED (Linux). Path: $exePath"
                } else {
                    if (file.exists()) file.delete()
                    "Autostart DISABLED (Linux)."
                }
            }
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NetworkHandler
// ─────────────────────────────────────────────────────────────────────────────

object NetworkHandler_v1 {

    // ── Constants ──────────────────────────────────────────────────────────────
    private const val DISCOVERY_PORT       = 9091
    private const val CLIENT_HELLO_MESSAGE = "HELLO_FROM_CLIENT"
    private const val MULTICAST_GROUP_IP   = "239.255.0.1"
    private const val DISCOVERY_MESSAGE    = "WIFI_AUDIO_STREAMER_DISCOVERY"
    private const val DISCOVERY_VERSION    = 2

    // ── Network helpers ────────────────────────────────────────────────────────
    fun getLocalIpAddress(): String = try {
        java.net.DatagramSocket().use { s ->
            s.connect(java.net.InetAddress.getByName("8.8.8.8"), 10002)
            s.localAddress.hostAddress
        }
    } catch (_: Exception) { "127.0.0.1" }

    fun getActiveNetworkInterface(preferredName: String = "Auto"): NetworkInterface? = try {
        val allInterfaces = NetworkInterface.getNetworkInterfaces().toList()

        if (preferredName != "Auto") {
            allInterfaces.firstOrNull { it.displayName == preferredName || it.name == preferredName }
        } else {
            allInterfaces.firstOrNull { iface ->
                iface.isUp && !iface.isLoopback &&
                        !iface.displayName.contains("Virtual",  ignoreCase = true) &&
                        !iface.displayName.contains("VMware",   ignoreCase = true) &&
                        !iface.displayName.contains("Hyper-V",  ignoreCase = true) &&
                        !iface.displayName.contains("WSL",      ignoreCase = true) &&
                        iface.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
            }
        }
    } catch (_: Exception) { null }

    suspend fun probeIsMulticast(ip: String, port: Int): Boolean {
        return try {
            var isUnicast = false
            withTimeout(1000) {
                aSocket(SelectorManager(Dispatchers.IO)).udp().bind().use { sock ->
                    val remoteAddress = InetSocketAddress(ip, port)
                    // Bussa alla porta con una richiesta speciale
                    sock.send(Datagram(buildPacket { writeText("MODE_PROBE") }, remoteAddress))
                    val ack = sock.receive()
                    if (ack.packet.readText().trim() == "UNICAST") {
                        isUnicast = true
                    }
                }
            }
            !isUnicast
        } catch (e: Exception) {
            true // Se va in timeout, il server non ascolta in unicast -> è in Multicast
        }
    }

    suspend fun pingServerUnicast(ip: String, port: Int): Boolean {
        return try {
            var online = false
            withTimeout(1000) {
                aSocket(SelectorManager(Dispatchers.IO)).udp().bind().use { sock ->
                    val remoteAddress = InetSocketAddress(ip, port)
                    sock.send(Datagram(buildPacket { writeText("MODE_PROBE") }, remoteAddress))
                    val ack = sock.receive()
                    if (ack.packet.readText().trim() == "UNICAST") {
                        online = true
                    }
                }
            }
            online
        } catch (e: Exception) {
            false
        }
    }

    @Volatile var currentServerVolume: Float = 1.0f
        private set

    fun setServerVolume(volume: Float) { currentServerVolume = volume }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isMicMuted = MutableStateFlow(false)

    private var streamingJob:    Job? = null
    private var listeningJob:    Job? = null
    private var broadcastingJob: Job? = null
    private var micReceiverJob:  Job? = null
    private var localMicMixJob:  Job? = null
    private var httpServerJob:   Job? = null
    private var rtpJob:          Job? = null

    private val lifecycleMutex = Mutex()
    private val lifecycleGeneration = java.util.concurrent.atomic.AtomicLong(0)

    // ── AudioEngine (motore nativo JNI) ───────────────────────────────────────
    @Volatile private var serverEngine: AudioEngine? = null

    // ── FFmpeg grabber (motore legacy) ────────────────────────────────────────
    @Volatile private var serverGrabber: org.bytedeco.javacv.FFmpegFrameGrabber? = null

    // ── Protocollo audio: header 10 byte preposto a ogni pacchetto PCM ────────
    // Layout (Big-Endian): [magic:2=0x5746][version:1=0x01][flags:1][seqNum:2][samplePos:4]
    // flags bit0=silence. I pacchetti control (PING/BYE/HELLO) iniziano con ASCII
    // e non matchano mai il magic 0x57 0x46, quindi la distinzione è inequivoca.
    private val AUDIO_MAGIC_0: Byte = 0x57   // 'W'
    private val AUDIO_MAGIC_1: Byte = 0x46   // 'F'
    private val AUDIO_VERSION: Byte = 0x01
    private val AUDIO_HEADER_SIZE = 10

    @Volatile private var audioSeqNum:   Int  = 0
    @Volatile private var audioSamplePos: Long = 0L

    // ── Sidecar PCM queues ─────────────────────────────────────────────────────
    // Riempite dal loop del grabber, svuotate dagli encoder. offer() non-bloccante:
    // se la coda è piena il frame viene scartato per non accumulare latenza.
    @Volatile private var aacPcmQueue:  java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null
    @Volatile private var opusPcmQueue: java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null
    @Volatile private var rtpPcmQueue:  java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null

    // ── Linux audio routing ────────────────────────────────────────────────────
    private var originalLinuxSink:   String? = null
    private var originalLinuxSource: String? = null

    private fun getPactlDefault(type: String): String? {
        try {
            val info = ProcessBuilder("pactl", "info").start()
            val out  = info.inputStream.bufferedReader().readText()
            if (info.waitFor() == 0) {
                val prefix = if (type == "sink") "Default Sink: " else "Default Source: "
                val result = out.lines().find { it.trimStart().startsWith(prefix) }
                    ?.substringAfter(prefix)?.trim()
                if (!result.isNullOrEmpty()) return result
            }
        } catch (_: Exception) {}
        return try {
            val p = ProcessBuilder("pactl", "get-default-$type").start()
            val o = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && o.isNotEmpty()) o else null
        } catch (_: Exception) { null }
    }

    private fun routeLinuxAudioToVirtualCable() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        getPactlDefault("sink").takeIf { it != null && !it.contains("VirtualCable", true) }
            ?.also { originalLinuxSink = it }
        getPactlDefault("source").takeIf { it != null && !it.contains("VirtualCable", true) }
            ?.also { originalLinuxSource = it }
        println("--- Linux: saved sink='$originalLinuxSink' source='$originalLinuxSource' ---")
        runCatching { ProcessBuilder("pactl", "set-default-sink",   "VirtualCable").start().waitFor() }
        runCatching { ProcessBuilder("pactl", "set-default-source", "VirtualCable.monitor").start().waitFor() }
        println("--- Linux: audio routed to VirtualCable ---")
    }

    fun restoreLinuxAudioRouting() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val sink   = originalLinuxSink   ?: run { println("--- Linux: nothing to restore ---"); return }
        val source = originalLinuxSource
        runCatching { ProcessBuilder("pactl", "set-default-sink", sink).start().waitFor() }
        source?.let { runCatching { ProcessBuilder("pactl", "set-default-source", it).start().waitFor() } }
        println("--- Linux: audio restored to sink='$sink' source='$source' ---")
        originalLinuxSink   = null
        originalLinuxSource = null
    }

    fun setupLinuxVirtualCable(useNativeEngine: Boolean) {
        if (useNativeEngine) {
            println("--- Linux: AudioEngine nativo attivo, VirtualCable non richiesto ---")
            return
        }
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        try {
            if (ProcessBuilder("which", "pactl").start().waitFor() != 0) {
                println("'pactl' not found — cannot create virtual cable automatically.")
                return
            }
            getPactlDefault("sink").takeIf { it != null && !it.contains("VirtualCable", true) }
                ?.also { originalLinuxSink = it }
            getPactlDefault("source").takeIf { it != null && !it.contains("VirtualCable", true) }
                ?.also { originalLinuxSource = it }
            println("--- Linux: real sink saved at startup: '$originalLinuxSink' ---")

            val check = ProcessBuilder("sh", "-c", "pactl list short sinks | grep VirtualCable").start()
            if (check.waitFor() == 0) { println("--- Linux: VirtualCable already active ---"); return }

            println("--- Linux: creating VirtualCable... ---")
            val create = ProcessBuilder(
                "pactl", "load-module", "module-null-sink",
                "sink_name=VirtualCable",
                "sink_properties=device.description=VirtualCable"
            ).start()
            if (create.waitFor() == 0) println("--- Linux: VirtualCable created ---")
            else println("--- Linux: failed to create VirtualCable ---")
        } catch (e: Exception) {
            println("Linux audio setup error: ${e.message}")
        }
    }

    // ── Device enumeration ────────────────────────────────────────────────────
    fun findAvailableOutputMixers(): List<Mixer.Info> = AudioSystem.getMixerInfo().filter { info ->
        !info.name.startsWith("Port", ignoreCase = true) &&
                AudioSystem.getMixer(info).isLineSupported(Line.Info(SourceDataLine::class.java))
    }

    fun findAvailableInputMixers(): List<Mixer.Info> = AudioSystem.getMixerInfo().filter { info ->
        !info.name.startsWith("Port", ignoreCase = true) &&
                AudioSystem.getMixer(info).isLineSupported(Line.Info(TargetDataLine::class.java))
    }

    fun checkVirtualDriverStatus(useNativeEngine: Boolean = true): VirtualDriverStatus {
        val os = System.getProperty("os.name").lowercase()

        if (useNativeEngine) {
            val loadErr = AudioEngine.getLoadError()
            if (loadErr != null) {
                return when {
                    os.contains("linux") -> VirtualDriverStatus.LinuxActionRequired(
                        "Impossibile caricare il motore audio nativo.\n$loadErr",
                        "Ricompila con: ./gradlew copyNativeLib"
                    )
                    else -> VirtualDriverStatus.Missing(
                        "Motore audio nativo",
                        "Ricompila con: ./gradlew copyNativeLib — $loadErr"
                    )
                }
            }
            if (os.contains("mac")) {
                val macVersion = System.getProperty("os.version") ?: "0.0"
                val major = macVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0
                if (major < 13) {
                    return VirtualDriverStatus.LinuxActionRequired(
                        "ScreenCaptureKit richiede macOS 12.3 o superiore (versione corrente: $macVersion). " +
                                "Aggiorna macOS oppure installa BlackHole 2ch come fallback.",
                        ""
                    )
                }
            }
            return VirtualDriverStatus.Ok
        }

        return when {
            os.contains("win") -> {
                val ok = AudioSystem.getMixerInfo().any { it.name.contains("CABLE Output", ignoreCase = true) }
                if (ok) VirtualDriverStatus.Ok
                else VirtualDriverStatus.Missing("VB-Audio Virtual Cable", "https://vb-audio.com/Cable/")
            }
            os.contains("mac") -> {
                val ok = AudioSystem.getMixerInfo().any { it.name.contains("BlackHole", ignoreCase = true) }
                if (ok) VirtualDriverStatus.Ok
                else VirtualDriverStatus.Missing("BlackHole 2ch", "https://existential.audio/blackhole/")
            }
            else -> try {
                if (ProcessBuilder("which", "pactl").start().waitFor() != 0)
                    return VirtualDriverStatus.LinuxActionRequired(
                        "PulseAudio utilities are missing.",
                        "sudo apt update && sudo apt install pulseaudio-utils"
                    )
                val s = ProcessBuilder("sh", "-c", "pactl list short sinks | grep VirtualCable").start()
                if (s.waitFor() != 0)
                    ProcessBuilder("pactl", "load-module", "module-null-sink",
                        "sink_name=VirtualCable",
                        "sink_properties=device.description=VirtualCable").start().waitFor()
                VirtualDriverStatus.Ok
            } catch (_: Exception) { VirtualDriverStatus.Ok }
        }
    }

    // ── Discovery ──────────────────────────────────────────────────────────────
    fun beginDeviceDiscovery(onDeviceFound: (hostname: String, serverInfo: ServerInfo) -> Unit) {
        if (listeningJob?.isActive == true) return
        listeningJob = scope.launch {
            val localIps = NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet()
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
            var socket: MulticastSocket? = null
            try {
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
                        val message  = String(packet.data, 0, packet.length).trim()

                        if (remoteIp in localIps || !message.startsWith(DISCOVERY_MESSAGE)) continue

                        val parts = message.split(";")
                        if (parts.size < 4) continue
                        val hostname = parts[1]

                        if (message.contains("BYE")) {
                            // Se riceve BYE, notifica la rimozione (passando null o gestendo la mappa)
                            // Nota: per semplicità passiamo un segnale al chiamante
                            onDeviceFound(hostname, ServerInfo("", false, 0, null, 0L))
                            continue
                        }

                        val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                        val port        = parts[3].toIntOrNull() ?: continue

                        val capabilities = if (parts.size >= 5) {
                            val protoStr = parts.firstOrNull { it.startsWith("protocols=") }
                                ?.removePrefix("protocols=") ?: "WFAS"
                            val protocols = protoStr.split(",").mapNotNull { token ->
                                runCatching { StreamingProtocol.valueOf(token.trim()) }.getOrNull()
                            }.toSet().ifEmpty { setOf(StreamingProtocol.WFAS) }
                            val httpPort = parts.firstOrNull { it.startsWith("http_port=") }
                                ?.removePrefix("http_port=")?.toIntOrNull()
                            ServerCapabilities(protocols, httpPort)
                        } else {
                            ServerCapabilities(setOf(StreamingProtocol.WFAS), null)
                        }
                        onDeviceFound(hostname, ServerInfo(remoteIp, isMulticast, port, capabilities, System.currentTimeMillis()))
                    } catch (_: java.net.SocketTimeoutException) { continue }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) println("Discovery listening error: ${e.message}")
            } finally {
                runCatching { socket?.leaveGroup(groupAddress) }
                socket?.close()
            }
        }
    }

    fun endDeviceDiscovery() { listeningJob?.cancel() }

    fun startAnnouncingPresence(isMulticast: Boolean, port: Int, capabilities: ServerCapabilities) {
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch {
            val hostname     = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop-PC")
            val mode         = if (isMulticast) "MULTICAST" else "UNICAST"
            val protocolsStr = capabilities.protocols.joinToString(",") { it.name }
            val httpPortStr  = capabilities.httpPort?.let { ";http_port=$it" } ?: ""
            val message      = "$DISCOVERY_MESSAGE;$hostname;$mode;$port;protocols=$protocolsStr$httpPortStr"
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
            MulticastSocket().use { socket ->
                socket.timeToLive = 4
                getActiveNetworkInterface()?.let { socket.networkInterface = it }
                while (isActive) {
                    runCatching {
                        val bytes = message.toByteArray()
                        socket.send(DatagramPacket(bytes, bytes.size, groupAddress, DISCOVERY_PORT))
                    }
                    delay(3000)
                }
            }
        }
    }

    fun stopAnnouncingPresence() {
        val job = broadcastingJob
        broadcastingJob = null
        scope.launch {
            job?.cancelAndJoin()
            // Invia un ultimo pacchetto di discovery con flag BYE
            val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop-PC")
            val message = "$DISCOVERY_MESSAGE;$hostname;BYE;0"
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
            MulticastSocket().use { socket ->
                val bytes = message.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, groupAddress, DISCOVERY_PORT))
            }
        }
    }

    // ── Microphone receiver (server side) ─────────────────────────────────────
    private fun CoroutineScope.launchMicReceiver(
        audioSettings: AudioSettings_V1,
        isMulticast: Boolean,
        routingMode: MicRoutingMode,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        micMixInputInfo: Mixer.Info? = null
    ) = launch {
        if (routingMode == MicRoutingMode.OFF) return@launch

        val osName = System.getProperty("os.name").lowercase()
        val isLinux   = osName.contains("linux")
        val isWindows = osName.contains("win")

        var line: SourceDataLine? = null
        var nativeVirtualSinkActive = false
        var wasapiSinkEngine: AudioEngine? = null
        var mixActive = false
        var muteCollectorJob: Job? = null

        try {
            when (routingMode) {
                MicRoutingMode.VIRTUAL_MIC -> {
                    if (isLinux) {
                        val engine = AudioEngine(
                            sampleRate = audioSettings.sampleRate.toInt(),
                            channels   = audioSettings.channels,
                            bufferFrames = audioSettings.bufferSize / (audioSettings.channels * 2)
                        )
                        val created = engine.createVirtualSink(
                            audioSettings.sampleRate.toInt(),
                            audioSettings.channels
                        )
                        if (!created) {
                            println("[MicReceiver] createVirtualSink fallita: ${engine.lastError}")
                            return@launch
                        }
                        activeVirtualSinkEngine = engine
                        nativeVirtualSinkActive = true
                        println("[MicReceiver] Virtual sink attivo: ${engine.virtualSinkName()}")
                    } else if (isWindows) {
                        val engine = AudioEngine(
                            sampleRate   = audioSettings.sampleRate.toInt(),
                            channels     = audioSettings.channels,
                            bufferFrames = audioSettings.bufferSize / (audioSettings.channels * 2)
                        )
                        val hint = micOutputMixerInfo?.name
                        val opened = engine.micSinkOpen(hint, audioSettings.sampleRate.toInt(), audioSettings.channels)
                        if (!opened) {
                            println("[MicReceiver] WASAPI micSinkOpen fallita: ${engine.lastError}")
                            val outputs = runCatching { findAvailableOutputMixers() }.getOrDefault(emptyList())
                            val fallback = micOutputMixerInfo
                                ?: VirtualMicAutodetect.detectManualCable(outputs)?.mixerInfo
                                ?: run {
                                    println("[MicReceiver] Nessun cable selezionato/rilevato.")
                                    return@launch
                                }
                            val mixer = runCatching { AudioSystem.getMixer(fallback) }.getOrNull() ?: return@launch
                            val format   = audioSettings.toAudioFormat()
                            val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
                            if (!mixer.isLineSupported(lineInfo)) return@launch
                            line = (mixer.getLine(lineInfo) as SourceDataLine).also {
                                it.open(format, audioSettings.bufferSize * 8)
                                it.start()
                            }
                            println("[MicReceiver] Fallback SourceDataLine su '${fallback.name}'")
                        } else {
                            wasapiSinkEngine = engine
                            println("[MicReceiver] WASAPI sink attivo su '${engine.micSinkDeviceName()}'")
                        }
                    } else {
                        val outputs = runCatching { findAvailableOutputMixers() }.getOrDefault(emptyList())
                        val mixerInfo = micOutputMixerInfo
                            ?: VirtualMicAutodetect.detectManualCable(outputs)?.mixerInfo
                            ?: run {
                                println("[MicReceiver] VIRTUAL_MIC su ${osName}: nessun cable selezionato/rilevato.")
                                return@launch
                            }
                        println("[MicReceiver] Apertura SourceDataLine verso '${mixerInfo.name}'")
                        val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: run {
                            println("[MicReceiver] getMixer() fallita per ${mixerInfo.name}")
                            return@launch
                        }
                        val format   = audioSettings.toAudioFormat()
                        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
                        if (!mixer.isLineSupported(lineInfo)) {
                            println("[MicReceiver] Mixer ${mixerInfo.name} non supporta il formato ${format.sampleRate}Hz/${format.channels}ch/${format.sampleSizeInBits}bit.")
                            return@launch
                        }
                        line = (mixer.getLine(lineInfo) as SourceDataLine).also {
                            it.open(format, audioSettings.bufferSize * 8)
                            it.start()
                        }
                        println("[MicReceiver] SourceDataLine aperta: buffer=${line?.bufferSize} bytes, format=$format")
                    }
                }
                MicRoutingMode.MIX_INTO_STREAM -> {
                    mixActive = true
                    while (isActive && serverEngine == null) {
                        delay(100)
                    }
                    serverEngine?.setMicMixEnabled(true)
                    serverEngine?.setMicMixVolume(if (isMicMuted.value) 0f else 1.0f)
                    muteCollectorJob = launch {
                        isMicMuted.collect { muted ->
                            runCatching { serverEngine?.setMicMixVolume(if (muted) 0f else 1.0f) }
                        }
                    }
                }
                MicRoutingMode.OFF -> return@launch
            }

            var socket: DatagramSocket? = null
            try {
                val buf    = ByteArray(65536)
                val packet = DatagramPacket(buf, buf.size)
                socket = DatagramSocket(micPort).apply {
                    soTimeout = 1000
                    receiveBufferSize = 1 shl 20
                }
                println("[MicReceiver] In ascolto su porta $micPort (mode=$routingMode) rcvbuf=${socket.receiveBufferSize}")

                val reusableShorts = ShortArray(buf.size / 2)
                var totalPackets = 0L
                var totalBytes   = 0L

                while (isActive) {
                    try {
                        packet.setData(buf, 0, buf.size)
                        socket.receive(packet)
                        val len = packet.length
                        if (len <= 0) continue

                        totalPackets++
                        totalBytes += len
                        if (totalPackets == 1L || totalPackets % 200L == 0L) {
                            println("[MicReceiver] pkt #$totalPackets from ${packet.address.hostAddress}:${packet.port} len=$len totalBytes=$totalBytes")
                        }

                        when (routingMode) {
                            MicRoutingMode.VIRTUAL_MIC -> {
                                if (nativeVirtualSinkActive) {
                                    val engine = activeVirtualSinkEngine ?: continue
                                    val numShorts = len / 2
                                    val bb = java.nio.ByteBuffer.wrap(packet.data, 0, len)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    var i = 0
                                    while (bb.remaining() >= 2 && i < reusableShorts.size) {
                                        reusableShorts[i++] = bb.short
                                    }
                                    engine.writeToVirtualSink(reusableShorts, numShorts)
                                } else if (wasapiSinkEngine != null) {
                                    val numShorts = len / 2
                                    val bb = java.nio.ByteBuffer.wrap(packet.data, 0, len)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    var i = 0
                                    while (bb.remaining() >= 2 && i < reusableShorts.size) {
                                        reusableShorts[i++] = bb.short
                                    }
                                    wasapiSinkEngine.micSinkWrite(reusableShorts, numShorts)
                                } else {
                                    val currentLine = line
                                    if (currentLine != null) {
                                        val evenLen = len - (len % 2)
                                        currentLine.write(packet.data, 0, evenLen)
                                    } else {
                                        if (totalPackets == 1L) println("[MicReceiver] ERRORE: SourceDataLine null, mixer non selezionato")
                                    }
                                }
                            }
                            MicRoutingMode.MIX_INTO_STREAM -> {
                                val engine = serverEngine ?: continue
                                val numShorts = len / 2
                                val bb = java.nio.ByteBuffer.wrap(packet.data, 0, len)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                var i = 0
                                while (bb.remaining() >= 2 && i < reusableShorts.size) {
                                    reusableShorts[i++] = bb.short
                                }
                                engine.pushMicPcm(reusableShorts, numShorts)
                            }
                            MicRoutingMode.OFF -> break
                        }
                    } catch (_: java.net.SocketTimeoutException) { continue }
                }
            } finally {
                socket?.close()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("[MicReceiver] errore: ${e.message}")
        } finally {
            muteCollectorJob?.cancel()
            if (mixActive && localMicMixJob?.isActive != true) {
                serverEngine?.setMicMixEnabled(false)
                serverEngine?.setMicMixVolume(1.0f)
            }
            if (nativeVirtualSinkActive) {
                activeVirtualSinkEngine?.destroyVirtualSink()
                activeVirtualSinkEngine = null
            }
            wasapiSinkEngine?.let {
                runCatching { it.micSinkClose() }
            }
            line?.let {
                runCatching { it.drain() }
                runCatching { it.stop() }
                runCatching { it.close() }
            }
        }
    }

    @Volatile private var activeVirtualSinkEngine: AudioEngine? = null

    // ── Local microphone mix-into-stream capture (server side) ────────────────
    private fun CoroutineScope.launchLocalMicMix(
        audioSettings: AudioSettings_V1,
        micMixInputInfo: Mixer.Info
    ) = launch(Dispatchers.IO) {
        var localMicLine: TargetDataLine? = null
        var muteCollectorJob: Job? = null
        try {
            while (isActive && serverEngine == null) {
                delay(100)
            }
            val engine = serverEngine ?: run {
                println("[LocalMicMix] serverEngine non disponibile, mix microfono saltato")
                return@launch
            }
            engine.setMicMixEnabled(true)
            engine.setMicMixVolume(if (isMicMuted.value) 0f else 1.0f)

            muteCollectorJob = launch {
                isMicMuted.collect { muted ->
                    runCatching { serverEngine?.setMicMixVolume(if (muted) 0f else 1.0f) }
                }
            }

            val mixer = runCatching { AudioSystem.getMixer(micMixInputInfo) }.getOrNull() ?: run {
                println("[LocalMicMix] Impossibile ottenere il mixer ${micMixInputInfo.name}")
                return@launch
            }
            val streamSr  = audioSettings.sampleRate
            val streamBd  = audioSettings.bitDepth
            val streamCh  = audioSettings.channels

            val candidates = mutableListOf<AudioFormat>()
            candidates += AudioFormat(streamSr, streamBd, streamCh, true, false)
            if (streamCh != 1) candidates += AudioFormat(streamSr, streamBd, 1, true, false)
            for (sr in floatArrayOf(48000f, 44100f, 32000f, 16000f)) {
                if (sr == streamSr) continue
                candidates += AudioFormat(sr, streamBd, streamCh, true, false)
                candidates += AudioFormat(sr, streamBd, 1, true, false)
            }

            var chosen: AudioFormat? = null
            for (fmt in candidates) {
                val info = DataLine.Info(TargetDataLine::class.java, fmt)
                if (!mixer.isLineSupported(info)) continue
                runCatching {
                    val l = (mixer.getLine(info) as TargetDataLine).apply {
                        open(fmt, audioSettings.bufferSize * 4)
                        start()
                    }
                    localMicLine = l
                    chosen = fmt
                }
                if (chosen != null) break
            }
            if (localMicLine == null || chosen == null) {
                println("[LocalMicMix] Nessun formato supportato dal device ${micMixInputInfo.name}")
                return@launch
            }
            println("[LocalMicMix] Capture aperta su ${micMixInputInfo.name} fmt=$chosen target=${streamSr}Hz/${streamCh}ch")

            val inSr      = chosen!!.sampleRate
            val inCh      = chosen!!.channels
            val needsUpmix   = inCh == 1 && streamCh == 2
            val needsDownmix = inCh == 2 && streamCh == 1
            val needsResample = inSr != streamSr

            val readBuf = ByteArray(audioSettings.bufferSize)

            while (isActive) {
                val n = localMicLine!!.read(readBuf, 0, readBuf.size)
                if (n <= 0) continue
                val bb = java.nio.ByteBuffer.wrap(readBuf, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val numInShorts = n / 2
                val inShorts = ShortArray(numInShorts)
                var idx = 0
                while (bb.remaining() >= 2 && idx < numInShorts) inShorts[idx++] = bb.short

                var procShorts: ShortArray = inShorts
                var procCh = inCh
                if (needsDownmix) {
                    val mono = ShortArray(numInShorts / 2)
                    var j = 0
                    var k = 0
                    while (k + 1 < numInShorts) {
                        mono[j++] = ((inShorts[k].toInt() + inShorts[k + 1].toInt()) / 2).toShort()
                        k += 2
                    }
                    procShorts = mono.copyOf(j)
                    procCh = 1
                } else if (needsUpmix) {
                    val stereo = ShortArray(numInShorts * 2)
                    var j = 0
                    for (s in inShorts) {
                        stereo[j++] = s
                        stereo[j++] = s
                    }
                    procShorts = stereo
                    procCh = 2
                }

                val outShorts: ShortArray = if (!needsResample) {
                    procShorts
                } else {
                    val ratio = streamSr.toDouble() / inSr.toDouble()
                    val inFrames = procShorts.size / procCh
                    val approxOutFrames = (inFrames * ratio).toInt() + 2
                    val out = ShortArray(approxOutFrames * procCh)
                    var outPos = 0
                    var srcFrame = 0.0
                    while (srcFrame < inFrames - 1 && outPos + procCh <= out.size) {
                        val base = srcFrame.toInt()
                        val frac = (srcFrame - base).toFloat()
                        for (c in 0 until procCh) {
                            val a = procShorts[base * procCh + c].toInt()
                            val b = procShorts[(base + 1) * procCh + c].toInt()
                            val v = (a + (b - a) * frac).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            out[outPos++] = v.toShort()
                        }
                        srcFrame += 1.0 / ratio
                    }
                    if (outPos < out.size) out.copyOf(outPos) else out
                }

                serverEngine?.pushMicPcm(outShorts, outShorts.size)
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            println("[LocalMicMix] errore: ${e.message}")
        } finally {
            muteCollectorJob?.cancel()
            runCatching { localMicLine?.stop() }
            runCatching { localMicLine?.close() }
            if (micReceiverJob?.isActive != true) {
                runCatching { serverEngine?.setMicMixEnabled(false) }
                runCatching { serverEngine?.setMicMixVolume(1.0f) }
            }
        }
    }

    // ── Microphone sender (client side) ───────────────────────────────────────
    private fun CoroutineScope.launchMicSender(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        micInputMixerInfo: Mixer.Info,
        micPort: Int
    ) = launch {
        val mixer    = AudioSystem.getMixer(micInputMixerInfo)
        val format   = audioSettings.toAudioFormat()
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
        if (!mixer.isLineSupported(lineInfo)) return@launch

        val line = (mixer.getLine(lineInfo) as TargetDataLine).also {
            it.open(format, audioSettings.bufferSize)
            it.start()
        }
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            val dest = if (serverInfo.isMulticast) InetAddress.getByName(MULTICAST_GROUP_IP)
            else InetAddress.getByName(serverInfo.ip)
            val buf = ByteArray(audioSettings.bufferSize)
            val silenceBuf = ByteArray(audioSettings.bufferSize)
            while (isActive) {
                val n = line.read(buf, 0, buf.size)
                if (n > 0) {
                    val dataToSend = if (isMicMuted.value) silenceBuf else buf
                    socket.send(DatagramPacket(dataToSend, n, dest, micPort))
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("Mic sender error: ${e.message}")
        } finally {
            socket?.close()
            line.stop(); line.close()
        }
    }

    // ── AudioEngine factory ────────────────────────────────────────────────────
    private fun buildAndStartEngine(audioSettings: AudioSettings_V1): AudioEngine? {
        val targetLatencyMs = 10
        val bufFrames = (audioSettings.sampleRate.toInt() * targetLatencyMs / 1000)
            .coerceAtLeast(256)
        val engine = AudioEngine(
            sampleRate   = audioSettings.sampleRate.toInt(),
            channels     = audioSettings.channels,
            bufferFrames = bufFrames
        )
        return if (engine.start()) {
            engine
        } else {
            println("=== AudioEngine start failed: ${engine.lastError} ===")
            null
        }
    }

    // ── FFmpeg grabber factory (motore legacy) ─────────────────────────────────
    private fun buildAndStartGrabber(audioSettings: AudioSettings_V1): org.bytedeco.javacv.FFmpegFrameGrabber? {
        val os = System.getProperty("os.name").lowercase()
        val (grabberFormat, deviceName) = when {
            os.contains("win") -> "dshow"        to "audio=CABLE Output (VB-Audio Virtual Cable)"
            os.contains("mac") -> "avfoundation" to ":BlackHole 2ch"
            else               -> "alsa"         to "default"
        }
        return try {
            routeLinuxAudioToVirtualCable()
            org.bytedeco.javacv.FFmpegFrameGrabber(deviceName).apply {
                setFormat(grabberFormat)
                setOption("probesize",         "32")
                setOption("analyzeduration",   "0")
                setOption("fflags",            "nobuffer")
                setOption("thread_queue_size", "4096")
                if (os.contains("win")) {
                    val rtbuf = (audioSettings.sampleRate.toInt() * audioSettings.channels * 2 * 2).toString()
                    setOption("rtbufsize", rtbuf)
                }
                if (os.contains("mac")) setOption("avioflags", "direct")
                sampleRate    = audioSettings.sampleRate.toInt()
                audioChannels = audioSettings.channels
                sampleFormat  = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                start()
            }
        } catch (e: Exception) {
            println("=== FFmpeg grabber start failed: ${e.message} ===")
            null
        }
    }

    private suspend fun processGrabberFrame(
        frame: org.bytedeco.javacv.Frame,
        chunkArray: ShortArray,
        byteBuffer: java.nio.ByteBuffer,
        maxShortsPerPacket: Int,
        onChunk: suspend (bytesToSend: Int) -> Unit
    ) {
        if (frame.samples == null) return
        val shortBuffer = frame.samples[0] as java.nio.ShortBuffer
        shortBuffer.position(0)
        while (shortBuffer.hasRemaining()) {
            val shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
            shortBuffer.get(chunkArray, 0, shortsToRead)
            val vol = currentServerVolume
            if (vol != 1.0f) {
                for (i in 0 until shortsToRead) {
                    chunkArray[i] = (chunkArray[i] * vol).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }
            byteBuffer.clear()
            byteBuffer.asShortBuffer().put(chunkArray, 0, shortsToRead)
            onChunk(shortsToRead * 2)
        }
    }

    // ── HTTP server (AAC chunked + Opus WebSocket) ─────────────────────────────
    private fun CoroutineScope.launchHttpServer(
        audioSettings: AudioSettings_V1,
        httpPort: Int,
        safariMode: Boolean,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) = launch {
        val aacQueue  = java.util.concurrent.ArrayBlockingQueue<ByteArray>(64)
        val opusQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(64)
        aacPcmQueue  = aacQueue
        opusPcmQueue = opusQueue

        val aacClients  = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()
        val opusClients = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()

        // Init segment WebM (EBML header + Tracks element) — deve essere inviato a ogni
        // nuovo client prima di qualsiasi cluster, altrimenti MSE invalida il SourceBuffer.
        // Viene catturato dal readerJob riconoscendo il marker del primo Cluster (0x1F43B675).
        val webmInitSegment = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)        // Client che si connettono dopo che l'init segment è già stato emesso:
        // rimangono in questa lista finché non ricevono l'init segment, poi vengono
        // promossi in opusClients per ricevere i cluster normali.
        val pendingOpusClients = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()

        // ── HTML player page ───────────────────────────────────────────────────
        val htmlPage = buildString {
            append("<!DOCTYPE html><html lang=\"en\"><head>")
            append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>WiFi Audio Streaming</title><style>")
            // Nuovo CSS unificato
            append(":root { --bg: #0f0f0f; --surface: #1e1e1e; --primary: #BB86FC; --text: #e0e0e0; --text-mut: #888; }")
            append("*{box-sizing:border-box;margin:0;padding:0}")
            append("body{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;background:var(--bg);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:var(--text);padding:20px;}")
            append(".card{background:var(--surface);padding:32px;border:1px solid #2a2a2a;border-radius:28px;text-align:center;max-width:400px;width:100%;box-shadow:0 8px 32px rgba(0,0,0,0.5);}")
            append(".icon{font-size:48px;margin-bottom:12px}")
            append("h1{font-size:22px;font-weight:600;margin-bottom:8px;color:#fff}")
            append(".sub{font-size:14px;color:var(--text-mut);margin-bottom:24px}")
            append(".dot{display:inline-block;width:10px;height:10px;border-radius:50%;background:#22c55e;margin-right:8px;animation:pulse 1.5s ease-in-out infinite}")
            append("@keyframes pulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.5;transform:scale(.85)}}")
            append(".status{font-size:14px;color:#22c55e;display:flex;align-items:center;justify-content:center;margin-bottom:16px;}")
            append(".warn{font-size:12px;color:#888;margin-top:12px} .error{color:#ef4444} audio{display:none;width:100%;border-radius:50px;}")
            append(".links{display:flex;flex-direction:column;gap:10px;margin-top:24px;}")
            append(".links a{text-decoration:none;color:var(--text);background:rgba(255,255,255,0.05);padding:14px;border-radius:16px;font-size:14px;transition:background 0.2s;border:1px solid rgba(255,255,255,0.05);font-weight:500;}")
            append(".links a:hover{background:rgba(255,255,255,0.1);}")
            append(".kofi{margin-top:24px;padding-top:20px;border-top:1px solid rgba(255,255,255,0.05);}")
            append(".kofi a{color:#FF5E5B;text-decoration:none;font-weight:bold;font-size:15px;}")
            append("</style></head><body>")

            // Struttura HTML
            append("<div class=\"card\">")
            append("<div class=\"icon\">🎧</div>")
            append("<h1>WiFi Audio Streaming</h1>")
            append("<p class=\"sub\" id=\"codec-label\">Connecting&hellip;</p>")
            append("<p class=\"status\" id=\"st\"><span class=\"dot\"></span>Live</p>")

            // Link e Ko-fi
            append("<div class=\"links\">")
            append("<a href=\"https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop\" target=\"_blank\">💻 Get Desktop App (GitHub)</a>")
            append("<a href=\"https://github.com/marcomorosi06/WiFiAudioStreaming-Android\" target=\"_blank\">📱 Get Android App (GitHub)</a>")
            append("<a href=\"https://apt.izzysoft.de/fdroid/index/apk/com.cuscus.wifiaudiostreaming\" target=\"_blank\">📲 Get Android App (IzzyOnDroid)</a>")
            append("</div>")

            append("<div class=\"kofi\">")
            append("<a href=\"https://ko-fi.com/marcomorosi06\" target=\"_blank\">☕ Support me on Ko-fi</a>")
            append("</div>")

            append("</div>")

            // Elemento audio nascosto e inizio JS
            append("<audio id=\"a\" autoplay playsinline></audio>")
            append("<script>")
            // ── Costanti ──────────────────────────────────────────────────────
            // Rileva Safari (incluso iOS WebKit) — usa AAC chunked HTTP + MSE
            // Tutti gli altri browser (Chrome/Firefox/Edge) usano Opus via WS + MSE
            append("const isSafari=/^((?!chrome|android).)*safari/i.test(navigator.userAgent)")
            append("  ||/iPad|iPhone|iPod/.test(navigator.userAgent);")
            append("const a=document.getElementById('a'),st=document.getElementById('st'),lbl=document.getElementById('codec-label');")

            // ── Helpers UI ────────────────────────────────────────────────────
            append("function setLive(){st.innerHTML='<span class=\"dot\"></span>Live';st.className='status';}")
            append("function setErr(msg){st.textContent=msg;st.className='status error';}")
            append("a.addEventListener('playing',setLive);")
            append("a.addEventListener('waiting',()=>{st.textContent='Buffering\u2026';});")
            append("a.addEventListener('error',()=>setErr('Playback error'));")

            // ── Funzione play con fallback tap-to-play (policy autoplay) ──────
            append("function tryPlay(){a.play().catch(()=>{")
            append("  const b=document.createElement('button');")
            append("  b.textContent='\u25b6\ufe0f Tap to listen';")
            append("  b.style.cssText='margin-top:20px;padding:12px 28px;border:none;border-radius:50px;background:#fff;color:#000;font-size:16px;cursor:pointer';")
            append("  b.onclick=()=>{a.play();b.remove();};")
            append("  document.querySelector('.card').appendChild(b);")
            append("});}")

            // ── MSE helper: appende chunk in coda evitando overlap ────────────
            // sourceBuffer.updating=true significa che non possiamo appendere ancora.
            // Usiamo una coda interna per serializzare gli append.
            append("function makeMSEAppender(sb){")
            append("  const q=[];let busy=false;")
            append("  sb.addEventListener('updateend',()=>{busy=false;drain();});")
            append("  function drain(){if(busy||!q.length)return;busy=true;sb.appendBuffer(q.shift());}")
            append("  return function(buf){q.push(buf);drain();};")
            append("}")

            // ── Chrome/Firefox/Edge: Opus WebM via WebSocket + MSE ───────────
            // WebM/Opus: Chrome Android richiede WebM (non OGG) per MSE.
            // Il server invia prima l'init segment (EBML+Tracks) poi i cluster.
            append("if(!isSafari){")
            append("  lbl.textContent='Opus \u2022 WebM \u2022';")
            append("  const MIME='audio/webm; codecs=opus';")
            // Se MSE non supporta WebM/Opus, fallback a decodeAudioData
            // (Firefox Android, browser obsoleti)
            append("  if(typeof MediaSource==='undefined'||!MediaSource.isTypeSupported(MIME)){")
            append("    lbl.textContent='Opus \u2022 fallback';")
            append("    const ac=new AudioContext();")
            append("    const ws2=new WebSocket('ws://'+location.host+'/stream/opus');")
            append("    ws2.binaryType='arraybuffer';")
            append("    ws2.onopen=()=>setLive();")
            // Accumula fino a 200KB poi decodifica — gestisce l'init segment automaticamente
            append("    let fbBuf=new Uint8Array(0);")
            append("    ws2.onmessage=async e=>{")
            append("      if(ac.state==='suspended')await ac.resume();")
            append("      const d=new Uint8Array(e.data);")
            append("      const t=new Uint8Array(fbBuf.length+d.length);")
            append("      t.set(fbBuf);t.set(d,fbBuf.length);fbBuf=t;")
            append("      if(fbBuf.length>8192){")
            append("        try{const b=await ac.decodeAudioData(fbBuf.buffer.slice(0));")
            append("          const s=ac.createBufferSource();s.buffer=b;s.connect(ac.destination);s.start();")
            append("        }catch(_){}fbBuf=new Uint8Array(0);}")
            append("    };")
            append("    ws2.onclose=()=>setErr('Disconnected');")
            append("  }else{")
            append("    const ms=new MediaSource();")
            append("    a.src=URL.createObjectURL(ms);")
            append("    ms.addEventListener('sourceopen',()=>{")
            append("      let sb;")
            append("      try{sb=ms.addSourceBuffer(MIME);}catch(e){setErr('MSE error: '+e.message);return;}")
            append("      sb.mode='sequence';")
            append("      const app=makeMSEAppender(sb);")
            // Gestione errori MSE: se il SourceBuffer viene rimosso, lo segnaliamo
            append("      sb.addEventListener('error',e=>setErr('Buffer error'));")
            append("      ms.addEventListener('error',e=>setErr('MediaSource error'));")
            append("      const ws=new WebSocket('ws://'+location.host+'/stream/opus');")
            append("      ws.binaryType='arraybuffer';")
            append("      ws.onopen=()=>setLive();")
            append("      ws.onmessage=e=>{if(ms.readyState==='open'&&sb.updating===false||true)app(e.data);};")
            append("      ws.onclose=()=>setErr('Disconnected');")
            append("      ws.onerror=()=>setErr('WS error');")
            append("      tryPlay();")
            append("    });")
            append("  }")

            // ── Safari/iOS: AAC via fetch streaming + MSE ─────────────────────
            // Safari supporta MSE con 'audio/aac' o 'audio/mp4; codecs=mp4a.40.2'.
            // Usiamo fetch con ReadableStream per leggere il chunked HTTP e
            // passare i chunk direttamente al SourceBuffer — più affidabile del
            // vecchio elemento <source> che su iOS non avvia lo stream in background.
            append("}else{")
            append("  lbl.textContent='AAC \u2022 ADTS';")
            append("  const mime='audio/aac';")
            append("  if(!MediaSource.isTypeSupported(mime)){")
            // Fallback estremo per Safari vecchi: elemento audio diretto
            append("    const s=document.createElement('source');s.src='/stream/aac';s.type=mime;")
            append("    a.appendChild(s);a.load();tryPlay();")
            append("  }else{")
            append("    const ms=new MediaSource();")
            append("    a.src=URL.createObjectURL(ms);")
            append("    ms.addEventListener('sourceopen',async()=>{")
            append("      const sb=ms.addSourceBuffer(mime);")
            append("      sb.mode='sequence';")
            append("      const append=makeMSEAppender(sb);")
            append("      try{")
            append("        const resp=await fetch('/stream/aac');")
            append("        const reader=resp.body.getReader();")
            append("        setLive(); tryPlay();")
            append("        while(true){")
            append("          const{done,value}=await reader.read();")
            append("          if(done)break;")
            append("          append(value.buffer);")
            append("        }")
            append("      }catch(e){setErr('Stream error: '+e.message);}")
            append("    });")
            append("  }")
            append("}")
            append("</script></body></html>")
        }
        val htmlBytes = htmlPage.toByteArray(Charsets.UTF_8)

        // ── WebSocket helpers ──────────────────────────────────────────────────
        fun sendWsFrame(out: java.io.OutputStream, payload: ByteArray) {
            val len = payload.size
            when {
                len < 126   -> out.write(byteArrayOf(0x82.toByte(), len.toByte()))
                len < 65536 -> out.write(byteArrayOf(0x82.toByte(), 126.toByte(),
                    (len shr 8).toByte(), (len and 0xFF).toByte()))
                else        -> out.write(byteArrayOf(0x82.toByte(), 127.toByte(),
                    0, 0, 0, 0,
                    (len shr 24).toByte(), (len shr 16).toByte(),
                    (len shr 8).toByte(), (len and 0xFF).toByte()))
            }
            out.write(payload)
            out.flush()
        }

        fun broadcastChunked(
            clients: java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>,
            data: ByteArray, isWebSocket: Boolean
        ) {
            if (clients.isEmpty()) return
            val dead = mutableListOf<java.io.OutputStream>()
            for (out in clients) {
                try {
                    if (isWebSocket) {
                        sendWsFrame(out, data)
                    } else {
                        val header = "${data.size.toString(16)}\r\n".toByteArray()
                        out.write(header); out.write(data); out.write("\r\n".toByteArray())
                        out.flush()
                    }
                } catch (_: Exception) { dead.add(out) }
            }
            clients.removeAll(dead)
        }

        // ── HTTP acceptor ──────────────────────────────────────────────────────
        val acceptorJob = launch(Dispatchers.IO) {
            val serverSocket = java.net.ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(httpPort))
                soTimeout = 500
            }
            // Chiusura sincrona nel finally — garantisce che la porta sia libera
            // prima che cancelAndJoin() ritorni al chiamante (stopCurrentStream).
            // invokeOnCompletion era asincrono e causava BindException al riavvio.
            try {
                println("--- HTTP server on port $httpPort (safariMode=$safariMode) ---")
                while (isActive) {
                    val sock = try { serverSocket.accept() }
                    catch (_: java.net.SocketTimeoutException) { continue }
                    catch (e: Exception) {
                        if (e !is CancellationException && !serverSocket.isClosed)
                            println("HTTP accept: ${e.message}")
                        break
                    }
                    launch(Dispatchers.IO) {
                        try {
                            val br = sock.getInputStream().bufferedReader()
                            val requestLine = br.readLine() ?: return@launch
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = br.readLine() ?: break
                                if (line.isEmpty()) break
                                val colon = line.indexOf(':')
                                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                                    line.substring(colon + 1).trim()
                            }
                            val path = requestLine.split(" ").getOrNull(1) ?: "/"
                            val out  = sock.getOutputStream()
                            when {
                                path == "/stream/aac" -> {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/aac\r\nTransfer-Encoding: chunked\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                    out.flush()
                                    aacClients.add(out)
                                    println("--- /stream/aac client: ${sock.inetAddress} ---")
                                    while (isActive && !sock.isClosed) delay(500)
                                }
                                path == "/stream/opus" && headers["upgrade"]?.lowercase() == "websocket" -> {
                                    val key    = headers["sec-websocket-key"] ?: return@launch
                                    val accept = java.util.Base64.getEncoder().encodeToString(
                                        java.security.MessageDigest.getInstance("SHA-1")
                                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                                    )
                                    out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                                    out.flush()
                                    println("--- /stream/opus WS client: ${sock.inetAddress} ---")
                                    // Se l'init segment WebM è già disponibile, invialo subito
                                    // al nuovo client e aggiungilo alla lista attiva.
                                    // Se non è ancora disponibile (encoder non ancora partito)
                                    // lo mettiamo in pending: il readerJob lo promuoverà.
                                    val initBytes = webmInitSegment.get()
                                    if (initBytes != null) {
                                        try { sendWsFrame(out, initBytes); opusClients.add(out) }
                                        catch (_: Exception) { /* client già morto */ }
                                    } else {
                                        pendingOpusClients.add(out)
                                    }
                                    sock.soTimeout = 100
                                    val inBuf = ByteArray(256)
                                    while (isActive && !sock.isClosed) {
                                        try { sock.getInputStream().read(inBuf) } catch (_: java.net.SocketTimeoutException) {}
                                        delay(500)
                                    }
                                }
                                else -> {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${htmlBytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    out.write(htmlBytes)
                                    out.flush()
                                    sock.close()
                                }
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) println("HTTP handler: ${e.message}")
                        } finally {
                            runCatching { sock.close() }
                        }
                    }
                }
            } finally {
                runCatching { serverSocket.close() }
            }
        }

        // ── AAC encoder ────────────────────────────────────────────────────────
        val aacEncoderJob = launch(Dispatchers.IO) {
            val codec = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder(
                org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
            ) ?: run { println("AAC codec not found"); return@launch }

            val ctx = org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3(codec)
            ctx.bit_rate(192_000)
            ctx.sample_rate(audioSettings.sampleRate.toInt())
            org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default(ctx.ch_layout(), audioSettings.channels)
            ctx.sample_fmt(org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP)
            ctx.flags(ctx.flags() or org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY)
            ctx.profile(1)
            org.bytedeco.ffmpeg.global.avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)

            val frameSize      = ctx.frame_size().let { if (it <= 0) 1024 else it }
            val nCh            = audioSettings.channels
            val samplesPerFrame = frameSize * nCh

            val avFrame = org.bytedeco.ffmpeg.global.avutil.av_frame_alloc().also {
                it.format(ctx.sample_fmt())
                it.ch_layout(ctx.ch_layout())
                it.sample_rate(ctx.sample_rate())
                it.nb_samples(frameSize)
                org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer(it, 0)
            }
            val pkt    = org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc()
            val accumS16 = java.nio.ShortBuffer.allocate(samplesPerFrame * 4)
            var pts    = 0L

            fun encodeAac() {
                org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable(avFrame)
                accumS16.flip()
                for (ch in 0 until nCh) {
                    val planeBuf = avFrame.data(ch).limit(frameSize.toLong() * 4)
                        .asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val tmp = accumS16.duplicate().also { it.position(ch) }
                    for (i in 0 until frameSize) {
                        val s = if (tmp.hasRemaining()) tmp.get() else 0
                        if (nCh > 1 && tmp.hasRemaining()) tmp.get()
                        planeBuf.put(s / 32768f)
                    }
                }
                repeat(samplesPerFrame) { if (accumS16.hasRemaining()) accumS16.get() }
                accumS16.clear()
                avFrame.pts(pts); pts += frameSize.toLong()
                if (org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame(ctx, avFrame) >= 0) {
                    while (org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet(ctx, pkt) >= 0) {
                        val aac = ByteArray(pkt.size()).also { pkt.data().get(it) }
                        org.bytedeco.ffmpeg.global.avcodec.av_packet_unref(pkt)
                        broadcastChunked(aacClients, aac, false)
                    }
                }
            }

            try {
                while (isActive) {
                    val pcm = aacQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (aacClients.isEmpty()) continue
                    val inc = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (inc.hasRemaining()) {
                        accumS16.put(inc.get())
                        if (accumS16.position() >= samplesPerFrame) encodeAac()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) println("AAC encoder error: ${e.message}")
            } finally {
                runCatching { org.bytedeco.ffmpeg.global.avutil.av_frame_free(avFrame) }
                runCatching { org.bytedeco.ffmpeg.global.avcodec.av_packet_free(pkt) }
                runCatching { org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context(ctx) }
            }
        }

        // ── Opus/OGG encoder ───────────────────────────────────────────────────
        val opusEncoderJob = launch(Dispatchers.IO) {
            val opusSampleRate = audioSettings.sampleRate.toInt()
            val nCh            = audioSettings.channels

            val pipeOut = java.io.PipedOutputStream()
            val pipeIn  = java.io.PipedInputStream(pipeOut, 131072)

            // WebM/Opus: unico container accettato da Chrome Android in MSE.
            // OGG/Opus fallisce silenziosamente su Chrome mobile con MSE.
            val recorder = org.bytedeco.javacv.FFmpegFrameRecorder(pipeOut, nCh).apply {
                audioCodec   = org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
                sampleRate   = opusSampleRate
                audioBitrate = 96_000
                audioQuality = 0.0
                setAudioOption("application",    "lowdelay")
                setAudioOption("frame_duration", "20")
                format = "webm"
                start()
            }

            val readerJob = launch(Dispatchers.IO) {
                // Accumulator per l'init segment WebM.
                // Un file WebM inizia con EBML header (0x1A45DFA3) seguito dall'elemento
                // Segment (0x18538067) che contiene SeekHead, Info e Tracks.
                // Il primo Cluster inizia con il tag 0x1F43B675 — tutto ciò che precede
                // il primo Cluster è l'init segment che MSE deve ricevere prima di qualsiasi dato.
                val initBuf = java.io.ByteArrayOutputStream()
                var initCaptured = false
                // Marker del Cluster WebM in big-endian (4 byte)
                val clusterMarker = byteArrayOf(0x1F.toByte(), 0x43.toByte(), 0xB6.toByte(), 0x75.toByte())

                fun ByteArray.indexOfSequence(seq: ByteArray, fromIndex: Int = 0): Int {
                    outer@ for (i in fromIndex..size - seq.size) {
                        for (j in seq.indices) if (this[i + j] != seq[j]) continue@outer
                        return i
                    }
                    return -1
                }

                val readBuf = ByteArray(8192)
                try {
                    while (isActive) {
                        val n = pipeIn.read(readBuf)
                        if (n <= 0) continue
                        val chunk = readBuf.copyOf(n)

                        if (!initCaptured) {
                            // Accumula finché non troviamo il primo Cluster
                            initBuf.write(chunk)
                            val accumulated = initBuf.toByteArray()
                            val clusterIdx = accumulated.indexOfSequence(clusterMarker)
                            if (clusterIdx >= 0) {
                                // Tutto ciò che precede il primo Cluster = init segment
                                val initSegment = accumulated.copyOf(clusterIdx)
                                webmInitSegment.set(initSegment)
                                initCaptured = true

                                // Promuovi i client pending: manda loro l'init segment
                                val toPromote = pendingOpusClients.toList()
                                pendingOpusClients.clear()
                                for (out in toPromote) {
                                    try { sendWsFrame(out, initSegment); opusClients.add(out) }
                                    catch (_: Exception) {}
                                }

                                // Il resto dell'accumulated (dal Cluster in poi) è il primo cluster
                                val firstCluster = accumulated.copyOfRange(clusterIdx, accumulated.size)
                                if (firstCluster.isNotEmpty())
                                    broadcastChunked(opusClients, firstCluster, true)

                                initBuf.reset() // libera memoria
                            }
                            // Se non abbiamo ancora trovato il Cluster, continuiamo ad accumulare
                        } else {
                            // Init segment già catturato: controlla se nuovi client pending
                            // si sono connessi nel frattempo (race window molto stretta)
                            val initBytes = webmInitSegment.get()
                            if (initBytes != null) {
                                val toPromote = pendingOpusClients.toList()
                                if (toPromote.isNotEmpty()) {
                                    pendingOpusClients.clear()
                                    for (out in toPromote) {
                                        try { sendWsFrame(out, initBytes); opusClients.add(out) }
                                        catch (_: Exception) {}
                                    }
                                }
                            }
                            broadcastChunked(opusClients, chunk, true)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException && e !is java.io.IOException)
                        println("Opus pipe reader error: ${e.message}")
                } finally {
                    runCatching { pipeIn.close() }
                }
            }

            try {
                while (isActive) {
                    val pcm = opusQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (opusClients.isEmpty() && pendingOpusClients.isEmpty()) continue

                    // FIX: copia i campioni in uno ShortArray owned — swresample accede alla memoria
                    // nativa anche dopo che record() ritorna, quindi NON passare il ByteArray della coda.
                    val nShorts = pcm.size / 2
                    val shorts  = ShortArray(nShorts)
                    java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                    val frame = org.bytedeco.javacv.Frame().also {
                        it.sampleRate    = opusSampleRate
                        it.audioChannels = nCh
                        it.samples       = arrayOf(java.nio.ShortBuffer.wrap(shorts))
                    }
                    try { recorder.record(frame) } catch (e: Exception) {
                        if (e !is CancellationException) println("Opus record error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) println("Opus encoder error: ${e.message}")
            } finally {
                // Order matters: stop recorder (flushes internal buffers into pipe) → close pipe
                // (unblocks reader) → cancel reader → release native resources.
                runCatching { recorder.stop() }
                runCatching { pipeOut.close() }
                readerJob.cancel(); readerJob.join()
                runCatching { recorder.release() }
            }
        }

        try { acceptorJob.join() } finally {
            acceptorJob.cancel()
            aacEncoderJob.cancel()
            opusEncoderJob.cancel()
            aacPcmQueue  = null
            opusPcmQueue = null
        }
    }

    // ── RTP sidecar ───────────────────────────────────────────────────────────
    private fun CoroutineScope.launchRtpSidecar(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean
    ) = launch(Dispatchers.IO) {
        val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(25)
        rtpPcmQueue = queue

        var sequenceNumber = (Math.random() * 65535).toInt()
        var rtpTimestamp   = (Math.random() * Int.MAX_VALUE).toLong()
        val ssrc           = (Math.random() * Int.MAX_VALUE).toLong()
        val timestampIncrement = (audioSettings.bufferSize / 2 / audioSettings.channels).toLong()

        val socket: DatagramSocket = if (isMulticast) {
            MulticastSocket().apply {
                timeToLive = 4
                getActiveNetworkInterface()?.let { networkInterface = it }
            }
        } else DatagramSocket()

        val destAddress = if (isMulticast) InetAddress.getByName(MULTICAST_GROUP_IP)
        else InetAddress.getByName(getLocalIpAddress())

        println("--- RTP sidecar started on port $port (multicast=$isMulticast) ---")
        try {
            while (isActive) {
                val pcmLeBytes = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue

                // Dimensione massima sicura per evitare la frammentazione IP (MTU 1500)
                val maxPayloadSize = 1400
                var offset = 0

                while (offset < pcmLeBytes.size) {
                    val chunkSize = minOf(maxPayloadSize, pcmLeBytes.size - offset)
                    val rtpPacket = ByteArray(12 + chunkSize)
                    val buf = java.nio.ByteBuffer.wrap(rtpPacket).order(java.nio.ByteOrder.BIG_ENDIAN)

                    // Header RTP
                    buf.put(0x80.toByte())
                    buf.put(96.toByte())
                    buf.putShort((sequenceNumber and 0xFFFF).toShort())
                    buf.putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
                    buf.putInt((ssrc and 0xFFFFFFFFL).toInt())

                    // Convertiamo da Little Endian (FFmpeg) a Big Endian (Network standard)
                    val leBuf = java.nio.ByteBuffer.wrap(pcmLeBytes, offset, chunkSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val beBuf = buf.asShortBuffer()
                    while (leBuf.hasRemaining()) beBuf.put(leBuf.get())

                    runCatching { socket.send(DatagramPacket(rtpPacket, rtpPacket.size, destAddress, port)) }

                    // Aggiorniamo i contatori in modo millimetrico per il prossimo pacchetto
                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    val samplesInChunk = chunkSize / 2 / audioSettings.channels // 2 byte per sample (16-bit)
                    rtpTimestamp += samplesInChunk
                    offset += chunkSize
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("RTP sidecar error: ${e.message}")
        } finally {
            socket.close()
            rtpPcmQueue = null
            println("--- RTP sidecar stopped ---")
        }
    }

    // ── Sidecar distribution ──────────────────────────────────────────────────
    // FIX: RTP ora riceve anch'esso una copia distinta del buffer, eliminando la
    // potenziale data race con il loop del grabber che potrebbe riusare byteBuffer.
    private fun distributeToSidecars(pcmBytes: ByteArray) {
        rtpPcmQueue?.let  { if (it.remainingCapacity() > 0) it.offer(pcmBytes.copyOf()) }
        aacPcmQueue?.let  { if (it.remainingCapacity() > 0) it.offer(pcmBytes.copyOf()) }
        opusPcmQueue?.let { if (it.remainingCapacity() > 0) it.offer(pcmBytes.copyOf()) }
    }

    private suspend fun processEngineFrame(
        samples: ShortArray,
        chunkArray: ShortArray,
        byteBuffer: java.nio.ByteBuffer,
        maxShortsPerPacket: Int,
        channels: Int,
        onChunk: suspend (bytesToSend: Int) -> Unit
    ) {
        if (samples.isEmpty()) {
            byteBuffer.clear()
            byteBuffer.order(java.nio.ByteOrder.BIG_ENDIAN)
            byteBuffer.put(AUDIO_MAGIC_0)
            byteBuffer.put(AUDIO_MAGIC_1)
            byteBuffer.put(AUDIO_VERSION)
            byteBuffer.put(0x01.toByte())
            byteBuffer.putShort((audioSeqNum and 0xFFFF).toShort())
            byteBuffer.putInt((audioSamplePos and 0xFFFFFFFFL).toInt())
            audioSeqNum = (audioSeqNum + 1) and 0xFFFF
            onChunk(AUDIO_HEADER_SIZE)
            return
        }

        var offset = 0
        while (offset < samples.size) {
            var shortsToRead = minOf(samples.size - offset, maxShortsPerPacket)
            shortsToRead -= (shortsToRead % channels)
            if (shortsToRead <= 0) break

            for (i in 0 until shortsToRead) chunkArray[i] = samples[offset + i]

            val vol = currentServerVolume
            if (vol != 1.0f) {
                for (i in 0 until shortsToRead) {
                    chunkArray[i] = (chunkArray[i] * vol).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }

            byteBuffer.clear()
            byteBuffer.order(java.nio.ByteOrder.BIG_ENDIAN)
            byteBuffer.put(AUDIO_MAGIC_0)
            byteBuffer.put(AUDIO_MAGIC_1)
            byteBuffer.put(AUDIO_VERSION)
            byteBuffer.put(0x00.toByte())
            byteBuffer.putShort((audioSeqNum and 0xFFFF).toShort())
            byteBuffer.putInt((audioSamplePos and 0xFFFFFFFFL).toInt())

            val payloadBytes = byteBuffer.array()
            var bytePos = AUDIO_HEADER_SIZE
            for (i in 0 until shortsToRead) {
                val sample = chunkArray[i].toInt()
                payloadBytes[bytePos++] = (sample and 0xFF).toByte()
                payloadBytes[bytePos++] = ((sample shr 8) and 0xFF).toByte()
            }

            audioSeqNum    = (audioSeqNum + 1) and 0xFFFF
            audioSamplePos += (shortsToRead / channels).toLong()
            offset         += shortsToRead

            onChunk(AUDIO_HEADER_SIZE + (shortsToRead * 2))
        }
    }

    // ── Server ─────────────────────────────────────────────────────────────────
    fun launchServerInstance(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean,
        capabilities: ServerCapabilities,
        micRoutingMode: MicRoutingMode,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        rtpPort: Int,
        useNativeEngine: Boolean = true,
        micMixInputInfo: Mixer.Info? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        val myGen = lifecycleGeneration.incrementAndGet()
        scope.launch {
            lifecycleMutex.withLock {
                if (myGen < lifecycleGeneration.get()) return@withLock
                stopCurrentStreamLocked()
                if (myGen < lifecycleGeneration.get()) return@withLock
                startServerInstanceLocked(
                    audioSettings, port, isMulticast, capabilities,
                    micRoutingMode, micOutputMixerInfo, micPort, rtpPort,
                    useNativeEngine, micMixInputInfo, onStatusUpdate
                )
            }
        }
    }

    private fun startServerInstanceLocked(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean,
        capabilities: ServerCapabilities,
        micRoutingMode: MicRoutingMode,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        rtpPort: Int,
        useNativeEngine: Boolean = true,
        micMixInputInfo: Mixer.Info? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        if (micRoutingMode != MicRoutingMode.OFF && !isMulticast) {
            micReceiverJob = scope.launchMicReceiver(
                audioSettings, false, micRoutingMode, micOutputMixerInfo, micPort, micMixInputInfo
            )
        }

        if (micRoutingMode == MicRoutingMode.MIX_INTO_STREAM && micMixInputInfo != null) {
            localMicMixJob = scope.launchLocalMicMix(audioSettings, micMixInputInfo)
        }

        startAnnouncingPresence(isMulticast, port, capabilities)

        streamingJob = scope.launch(Dispatchers.IO) {
            try {
                if (isMulticast) {
                    if (useNativeEngine) {
                        serverEngine = buildAndStartEngine(audioSettings)
                        if (serverEngine == null) {
                            onStatusUpdate("error_virtual_driver_missing", emptyArray())
                            return@launch
                        }
                    } else {
                        serverGrabber = buildAndStartGrabber(audioSettings)
                        if (serverGrabber == null) {
                            onStatusUpdate("error_virtual_driver_missing", emptyArray())
                            return@launch
                        }
                    }

                    onStatusUpdate("Streaming Multicast on %s:%d...", arrayOf(MULTICAST_GROUP_IP, port))

                    val nCh               = audioSettings.channels
                    val maxBytesPerPacket = if (useNativeEngine) {
                        1400 - (1400 % (nCh * 2))
                    } else {
                        audioSettings.bufferSize
                    }
                    val maxShortsPerPacket = maxBytesPerPacket / 2
                    val packetBuf          = ByteArray(maxBytesPerPacket)
                    val chunkArray         = ShortArray(maxShortsPerPacket)
                    val byteBuffer         = java.nio.ByteBuffer.allocate(maxBytesPerPacket)
                        .apply { if (!useNativeEngine) order(java.nio.ByteOrder.LITTLE_ENDIAN) }

                    try {
                        MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                            socket.reuseAddress = true
                            socket.bind(java.net.InetSocketAddress(0))
                            socket.timeToLive = 4
                            getActiveNetworkInterface()?.let { socket.networkInterface = it }
                            val group = InetAddress.getByName(MULTICAST_GROUP_IP)

                            while (isActive) {
                                if (useNativeEngine) {
                                    val engine      = serverEngine ?: break
                                    val ownedShorts = engine.readFrame() ?: break
                                    if (ownedShorts.isEmpty()) continue

                                    val vol = currentServerVolume
                                    if (vol != 1.0f) {
                                        for (i in ownedShorts.indices) {
                                            ownedShorts[i] = (ownedShorts[i] * vol).toInt()
                                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                                .toShort()
                                        }
                                    }

                                    var offset = 0
                                    while (offset < ownedShorts.size) {
                                        var shortsThisPacket = minOf(ownedShorts.size - offset, maxShortsPerPacket)
                                        shortsThisPacket -= (shortsThisPacket % nCh)
                                        if (shortsThisPacket <= 0) break

                                        var bytePos = 0
                                        for (i in 0 until shortsThisPacket) {
                                            val s = ownedShorts[offset + i].toInt()
                                            packetBuf[bytePos++] = (s and 0xFF).toByte()
                                            packetBuf[bytePos++] = ((s shr 8) and 0xFF).toByte()
                                        }

                                        val totalBytes = shortsThisPacket * 2
                                        runCatching { socket.send(DatagramPacket(packetBuf, totalBytes, group, port)) }
                                        if (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null)
                                            distributeToSidecars(packetBuf.copyOf(totalBytes))
                                        offset += shortsThisPacket
                                    }
                                } else {
                                    val grabber = serverGrabber ?: break
                                    val frame = try { grabber.grabSamples() }
                                    catch (e: org.bytedeco.javacv.FFmpegFrameGrabber.Exception) {
                                        println("Grabber exception (multicast): ${e.message}")
                                        break
                                    }
                                    if (frame != null) {
                                        processGrabberFrame(frame, chunkArray, byteBuffer, maxShortsPerPacket) { bytesToSend ->
                                            socket.send(DatagramPacket(byteBuffer.array(), bytesToSend, group, port))
                                            if (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null)
                                                distributeToSidecars(byteBuffer.array().copyOf(bytesToSend))
                                        }
                                    }
                                }
                            }

                            withContext(NonCancellable) {
                                runCatching {
                                    val bye = "BYE".toByteArray()
                                    repeat(3) {
                                        socket.send(DatagramPacket(bye, bye.size, group, port))
                                    }
                                    println("--- Sent BYE to multicast group ---")
                                }
                            }
                        }
                    } finally {
                        if (useNativeEngine) {
                            val engineToStop = serverEngine
                            serverEngine = null
                            runCatching { engineToStop?.stop() }
                        } else {
                            val grabberToStop = serverGrabber
                            serverGrabber = null
                            runCatching { grabberToStop?.stop() }
                            runCatching { grabberToStop?.release() }
                        }
                    }

                } else { // Unicast
                    val localAddress = InetSocketAddress("0.0.0.0", port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        while (isActive) {
                            startAnnouncingPresence(isMulticast = false, port = port, capabilities = capabilities)
                            onStatusUpdate("Waiting for Unicast Client on Port %d...", arrayOf(port))

                            val clientDatagram = socket.receive()
                            val msg = clientDatagram.packet.readText().trim()
                            val clientAddress = clientDatagram.address

                            if (msg == "MODE_PROBE") {
                                socket.send(Datagram(buildPacket { writeText("UNICAST") }, clientAddress))
                                continue
                            }

                            if (msg != CLIENT_HELLO_MESSAGE) continue

                            onStatusUpdate("Client Connected: %s", arrayOf(clientAddress.toString()))
                            stopAnnouncingPresence()

                            if (useNativeEngine) {
                                serverEngine = buildAndStartEngine(audioSettings)
                                if (serverEngine == null) {
                                    onStatusUpdate("error_virtual_driver_missing", emptyArray())
                                    break
                                }
                            } else {
                                serverGrabber = buildAndStartGrabber(audioSettings)
                                if (serverGrabber == null) {
                                    onStatusUpdate("error_virtual_driver_missing", emptyArray())
                                    break
                                }
                            }

                            socket.send(Datagram(buildPacket { writeText("HELLO_ACK") }, clientAddress))

                            val clientAlive = java.util.concurrent.atomic.AtomicBoolean(true)

                            val pingJob = launch {
                                var failures = 0
                                while (isActive && clientAlive.get()) {
                                    delay(1000)
                                    try {
                                        socket.send(Datagram(buildPacket { writeText("PING") }, clientAddress))
                                        failures = 0
                                    } catch (_: Exception) {
                                        if (++failures >= 3) {
                                            println("--- PING failed 3 times, client considered gone ---")
                                            clientAlive.set(false)
                                        }
                                    }
                                }
                            }

                            val clientByeReceiverJob = launch {
                                try {
                                    while (isActive && clientAlive.get()) {
                                        val datagram = socket.receive()
                                        val msg = datagram.packet.readText().trim()
                                        if (msg == "CLIENT_BYE") {
                                            println("--- Received CLIENT_BYE from $clientAddress ---")
                                            clientAlive.set(false)
                                            if (useNativeEngine) {
                                                runCatching { serverEngine?.stop() }
                                            } else {
                                                runCatching { serverGrabber?.stop() }
                                            }
                                            break
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            try {
                                if (useNativeEngine) {
                                    val safeMtuSize = 1400
                                    var maxShortsPerPacket = (safeMtuSize - AUDIO_HEADER_SIZE) / 2
                                    maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                    val exactPayloadBytes = AUDIO_HEADER_SIZE + (maxShortsPerPacket * 2)
                                    val chunkArray = ShortArray(maxShortsPerPacket)
                                    val byteBuffer = java.nio.ByteBuffer.allocate(exactPayloadBytes)
                                    if (audioSettings.channels == 2 && maxShortsPerPacket % 2 != 0) maxShortsPerPacket -= 1
                                    val packetArray = ByteArray(exactPayloadBytes)
                                    audioSeqNum    = 0
                                    audioSamplePos = 0L

                                    while (isActive && clientAlive.get()) {
                                        val engine = serverEngine ?: break
                                        val samples = engine.readFrame() ?: break
                                        if (!clientAlive.get()) break
                                        if (samples.isEmpty()) continue
                                        processEngineFrame(samples, chunkArray, byteBuffer, maxShortsPerPacket, audioSettings.channels) { bytesToSend ->
                                            byteBuffer.array().copyInto(packetArray, 0, 0, bytesToSend)
                                            val packet = buildPacket { writeFully(packetArray, 0, bytesToSend) }
                                            try {
                                                socket.send(Datagram(packet, clientAddress))
                                            } catch (_: Exception) {
                                                clientAlive.set(false)
                                            }
                                            if (clientAlive.get() &&
                                                (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null))
                                                distributeToSidecars(byteBuffer.array().copyOfRange(AUDIO_HEADER_SIZE, bytesToSend))
                                        }
                                    }
                                } else {
                                    val safeMtuSize = 1400
                                    var maxShortsPerPacket = (safeMtuSize - AUDIO_HEADER_SIZE) / 2
                                    maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                    val exactPayloadBytes  = AUDIO_HEADER_SIZE + (maxShortsPerPacket * 2)
                                    val chunkArray         = ShortArray(maxShortsPerPacket)
                                    val byteBuffer         = java.nio.ByteBuffer.allocate(maxShortsPerPacket * 2)
                                        .apply { order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                                    val packetArray        = ByteArray(exactPayloadBytes)
                                    var legacySeq          = 0

                                    while (isActive && clientAlive.get()) {
                                        val grabber = serverGrabber ?: break
                                        val frame = try { grabber.grabSamples() }
                                        catch (e: org.bytedeco.javacv.FFmpegFrameGrabber.Exception) {
                                            println("Grabber exception (unicast): ${e.message}")
                                            break
                                        }
                                        if (!clientAlive.get()) break
                                        if (frame != null) {
                                            processGrabberFrame(frame, chunkArray, byteBuffer, maxShortsPerPacket) { bytesToSend ->
                                                packetArray[0] = AUDIO_MAGIC_0
                                                packetArray[1] = AUDIO_MAGIC_1
                                                packetArray[2] = AUDIO_VERSION
                                                packetArray[3] = 0x00
                                                packetArray[4] = ((legacySeq shr 8) and 0xFF).toByte()
                                                packetArray[5] = (legacySeq and 0xFF).toByte()
                                                packetArray[6] = 0; packetArray[7] = 0; packetArray[8] = 0; packetArray[9] = 0
                                                byteBuffer.array().copyInto(packetArray, AUDIO_HEADER_SIZE, 0, bytesToSend)
                                                legacySeq = (legacySeq + 1) and 0xFFFF
                                                val totalBytes = AUDIO_HEADER_SIZE + bytesToSend
                                                val packet = buildPacket { writeFully(packetArray, 0, totalBytes) }
                                                try {
                                                    socket.send(Datagram(packet, clientAddress))
                                                } catch (_: Exception) {
                                                    clientAlive.set(false)
                                                }
                                                if (clientAlive.get() &&
                                                    (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null))
                                                    distributeToSidecars(byteBuffer.array().copyOf(bytesToSend))
                                            }
                                        }
                                    }
                                }
                            } finally {
                                clientByeReceiverJob.cancel()
                                pingJob.cancel()
                                if (clientAlive.get()) {
                                    withContext(NonCancellable) {
                                        runCatching {
                                            socket.send(Datagram(buildPacket { writeText("BYE") }, clientAddress))
                                            println("--- Sent BYE to $clientAddress ---")
                                        }
                                    }
                                }
                                if (useNativeEngine) {
                                    val engineToStop = serverEngine
                                    serverEngine = null
                                    runCatching { engineToStop?.stop() }
                                } else {
                                    val grabberToStop = serverGrabber
                                    serverGrabber = null
                                    delay(50)
                                    runCatching { grabberToStop?.stop() }
                                    runCatching { grabberToStop?.release() }
                                }
                            }
                            if (!clientAlive.get() && isActive) {
                                onStatusUpdate("status_client_disconnected", emptyArray())
                                break
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
                if (useNativeEngine) {
                    val engineToStop = serverEngine
                    serverEngine = null
                    runCatching { engineToStop?.stop() }
                } else {
                    val grabberToStop = serverGrabber
                    serverGrabber = null
                    runCatching { grabberToStop?.stop() }
                    runCatching { grabberToStop?.release() }
                    restoreLinuxAudioRouting()
                }
            }
        }
    }

    // ── Client ─────────────────────────────────────────────────────────────────
    fun launchClientInstance(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        selectedMixerInfo: Mixer.Info,
        sendMicrophone: Boolean,
        micInputMixerInfo: Mixer.Info?,
        micPort: Int,
        connectionSoundEnabled: Boolean = true,
        disconnectionSoundEnabled: Boolean = true,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        // Protocol selection: WFAS > RTP > HTTP
        val caps         = serverInfo.capabilities
        val wfasAvailable = caps == null || StreamingProtocol.WFAS in caps.protocols
        val httpFallback  = caps != null && !wfasAvailable &&
                StreamingProtocol.HTTP in caps.protocols && caps.httpPort != null

        if (httpFallback) {
            val url = "http://${serverInfo.ip}:${caps!!.httpPort}"
            onStatusUpdate("status_opening_browser", arrayOf(url))
            runCatching { openUrl(url) }
                .onFailure { e ->
                    println("Cannot open browser: ${e.message}")
                    onStatusUpdate("status_error_client", arrayOf(e.message ?: "browser unavailable"))
                }
            return
        }

        if (sendMicrophone && micInputMixerInfo != null)
            micReceiverJob = scope.launchMicSender(audioSettings, serverInfo, micInputMixerInfo, micPort)

        streamingJob = scope.launch {
            var sourceDataLine: SourceDataLine? = null
            try {
                if (!serverInfo.isMulticast) { // Unicast
                    val remoteAddress = InetSocketAddress(serverInfo.ip, serverInfo.port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind().use { socket ->
                        // FIX (latenza): apri la SourceDataLine PRIMA di mandare HELLO —
                        // quando arriva l'ACK la linea è già pronta, nessuna inizializzazione on-hot-path.
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        onStatusUpdate("status_contacting_server", arrayOf(remoteAddress))
                        socket.send(Datagram(buildPacket { writeText(CLIENT_HELLO_MESSAGE) }, remoteAddress))

                        onStatusUpdate("status_waiting_ack", emptyArray())
                        val ackDatagram = withTimeout(15000) { socket.receive() }
                        if (ackDatagram.packet.readText().trim() != "HELLO_ACK") {
                            onStatusUpdate("status_handshake_failed", emptyArray())
                            return@use
                        }
                        onStatusUpdate("status_connected_streaming_from", arrayOf(remoteAddress))
                        if (connectionSoundEnabled) playConnectionSound()

                        var lastPingReceived = System.currentTimeMillis()
                        val pingTimeoutMs    = 3000L
                        val serverAlive      = java.util.concurrent.atomic.AtomicBoolean(true)

                        val watchdogJob = launch {
                            while (isActive && serverAlive.get()) {
                                delay(1000)
                                if (System.currentTimeMillis() - lastPingReceived > pingTimeoutMs) {
                                    println("--- Server timeout: no PING for ${pingTimeoutMs}ms ---")
                                    onStatusUpdate("status_server_disconnected", emptyArray())
                                    if (disconnectionSoundEnabled) playDisconnectionSound()
                                    serverAlive.set(false)
                                }
                            }
                        }

                        try {
                            while (isActive && serverAlive.get()) {
                                val datagram = socket.receive()
                                val bytes    = ByteArray(datagram.packet.remaining.toInt())
                                datagram.packet.readFully(bytes)

                                if (bytes.size >= AUDIO_HEADER_SIZE && bytes[0] == AUDIO_MAGIC_0 && bytes[1] == AUDIO_MAGIC_1) {
                                    sourceDataLine?.write(bytes, AUDIO_HEADER_SIZE, bytes.size - AUDIO_HEADER_SIZE)
                                } else {
                                    val text = bytes.toString(Charsets.UTF_8).trim()
                                    when (text) {
                                        "PING" -> lastPingReceived = System.currentTimeMillis()
                                        "BYE"  -> {
                                            println("--- Received BYE from server ---")
                                            onStatusUpdate("status_server_disconnected", emptyArray())
                                            if (disconnectionSoundEnabled) playDisconnectionSound()
                                            serverAlive.set(false)
                                        }
                                    }
                                }
                            }
                        } finally {
                            watchdogJob.cancel()
                            withContext(NonCancellable) {
                                runCatching {
                                    socket.send(Datagram(buildPacket { writeText("CLIENT_BYE") }, remoteAddress))
                                }
                            }
                        }
                    }
                } else { // Multicast
                    onStatusUpdate("status_joining_multicast", arrayOf(serverInfo.port))
                    val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                    MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                        socket.reuseAddress = true
                        socket.bind(java.net.InetSocketAddress(serverInfo.port))
                        getActiveNetworkInterface()?.let { socket.networkInterface = it }
                        socket.joinGroup(groupAddress)
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()
                        onStatusUpdate("status_multicast_streaming", arrayOf(serverInfo.port))
                        if (connectionSoundEnabled) playConnectionSound()
                        socket.soTimeout = 2000
                        val buf    = ByteArray(audioSettings.bufferSize * 2)
                        val packet = DatagramPacket(buf, buf.size)
                        while (isActive) {
                            try {
                                socket.receive(packet)
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            }
                            if (packet.length >= AUDIO_HEADER_SIZE && packet.data[0] == AUDIO_MAGIC_0 && packet.data[1] == AUDIO_MAGIC_1) {
                                sourceDataLine?.write(packet.data, AUDIO_HEADER_SIZE, packet.length - AUDIO_HEADER_SIZE)
                            } else if (packet.length == 3 && String(packet.data, 0, 3, Charsets.UTF_8) == "BYE") {
                                println("--- Received BYE from multicast server ---")
                                onStatusUpdate("status_server_disconnected", emptyArray())
                                if (disconnectionSoundEnabled) playDisconnectionSound()
                                break
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
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

    private fun prepareSourceDataLine(mixerInfo: Mixer.Info, audioSettings: AudioSettings_V1): SourceDataLine? {
        val mixer        = AudioSystem.getMixer(mixerInfo)
        val format       = audioSettings.toAudioFormat()
        val dataLineInfo = DataLine.Info(SourceDataLine::class.java, format)

        if (!mixer.isLineSupported(dataLineInfo)) return null

        val frameSize         = format.frameSize
        val adjustedBufferSize = (audioSettings.bufferSize / frameSize) * frameSize

        return (mixer.getLine(dataLineInfo) as SourceDataLine).also {

            it.open(format, adjustedBufferSize * 4)
        }
    }

    // ── Stop / terminate ───────────────────────────────────────────────────────
    fun requestStopCurrentStream(): Job {
        val myGen = lifecycleGeneration.incrementAndGet()
        return scope.launch {
            lifecycleMutex.withLock {
                if (myGen < lifecycleGeneration.get()) return@withLock
                stopCurrentStreamLocked()
            }
        }
    }

    suspend fun stopCurrentStream() {
        requestStopCurrentStream().join()
    }

    private suspend fun stopCurrentStreamLocked() {
        stopAnnouncingPresence()

        httpServerJob?.cancelAndJoin();  httpServerJob   = null
        rtpJob?.cancelAndJoin();         rtpJob          = null
        localMicMixJob?.cancelAndJoin(); localMicMixJob  = null
        runCatching { serverEngine?.setMicMixEnabled(false) }
        runCatching { serverEngine?.stop() }
        runCatching { serverGrabber?.stop() }
        streamingJob?.cancelAndJoin();   streamingJob    = null
        micReceiverJob?.cancelAndJoin(); micReceiverJob  = null
        broadcastingJob?.cancelAndJoin(); broadcastingJob = null
        isMicMuted.value = false
    }

    fun terminateAllServices() { scope.cancel() }

    fun playConnectionSound() {
        scope.launch(Dispatchers.IO) {
            try {
                val stream = object {}.javaClass.getResourceAsStream("/raw/connection_sound.wav")
                if (stream != null) {
                    stream.use {
                        val audioStream = AudioSystem.getAudioInputStream(it.buffered())
                        val format = audioStream.format
                        val info = DataLine.Info(SourceDataLine::class.java, format)
                        val line = AudioSystem.getLine(info) as SourceDataLine
                        line.open(format)
                        line.start()
                        val buf = ByteArray(4096)
                        var n: Int
                        while (audioStream.read(buf, 0, buf.size).also { n = it } != -1) line.write(buf, 0, n)
                        line.drain()
                        line.stop()
                        line.close()
                    }
                    return@launch
                }
                val sampleRate = 44100f
                val durationMs = 120
                val samples = (sampleRate * durationMs / 1000).toInt()
                val buf = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val t = i / sampleRate
                    val env = if (i < samples / 4) i.toDouble() / (samples / 4)
                              else 1.0 - (i - samples / 4).toDouble() / (samples * 3 / 4)
                    val sample = ((Math.sin(2 * Math.PI * 880.0 * t) * 0.5 + Math.sin(2 * Math.PI * 1320.0 * t) * 0.5) * env * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buf[i * 2] = (sample and 0xFF).toByte()
                    buf[i * 2 + 1] = (sample shr 8).toByte()
                }
                val format = AudioFormat(sampleRate, 16, 1, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(format)
                line.start()
                line.write(buf, 0, buf.size)
                line.drain()
                line.stop()
                line.close()
            } catch (_: Exception) {}
        }
    }

    fun playDisconnectionSound() {
        scope.launch(Dispatchers.IO) {
            try {
                val stream = object {}.javaClass.getResourceAsStream("/raw/disconnection_sound.wav")
                if (stream != null) {
                    stream.use {
                        val audioStream = AudioSystem.getAudioInputStream(it.buffered())
                        val format = audioStream.format
                        val info = DataLine.Info(SourceDataLine::class.java, format)
                        val line = AudioSystem.getLine(info) as SourceDataLine
                        line.open(format)
                        line.start()
                        val buf = ByteArray(4096)
                        var n: Int
                        while (audioStream.read(buf, 0, buf.size).also { n = it } != -1) line.write(buf, 0, n)
                        line.drain()
                        line.stop()
                        line.close()
                    }
                    return@launch
                }
                val sampleRate = 44100f
                val durationMs = 120
                val samples = (sampleRate * durationMs / 1000).toInt()
                val buf = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val t = i / sampleRate
                    val env = if (i < samples / 4) i.toDouble() / (samples / 4)
                              else 1.0 - (i - samples / 4).toDouble() / (samples * 3 / 4)
                    val sample = ((Math.sin(2 * Math.PI * 660.0 * t) * 0.5 + Math.sin(2 * Math.PI * 440.0 * t) * 0.5) * env * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buf[i * 2] = (sample and 0xFF).toByte()
                    buf[i * 2 + 1] = (sample shr 8).toByte()
                }
                val format = AudioFormat(sampleRate, 16, 1, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(format)
                line.start()
                line.write(buf, 0, buf.size)
                line.drain()
                line.stop()
                line.close()
            } catch (_: Exception) {}
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Application entry point
// ─────────────────────────────────────────────────────────────────────────────

private fun loadTrayIconPainter(): Painter {
    val loaders = listOfNotNull(
        Thread.currentThread().contextClassLoader,
        Any::class.java.classLoader,
        ClassLoader.getSystemClassLoader()
    )
    val candidates = listOf("app_icon.png", "/app_icon.png")
    for (loader in loaders) {
        for (path in candidates) {
            val resource = if (path.startsWith("/")) Any::class.java.getResourceAsStream(path)
                           else loader.getResourceAsStream(path)
            if (resource != null) {
                return runCatching {
                    resource.use { input ->
                        val image = ImageIO.read(input)
                        if (image != null) BitmapPainter(image.toComposeImageBitmap())
                        else null
                    }
                }.getOrNull() ?: continue
            }
        }
    }
    val blank = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    return BitmapPainter(blank.toComposeImageBitmap())
}

fun main() = application {
    // FIX CRASH: disabilita AVX-512/AVX3 — causa EXCEPTION_ACCESS_VIOLATION in
    // StubRoutines::jlong_disjoint_arraycopy_avx3 durante la copia di buffer audio
    // nativi su alcune CPU Windows con la JVM Temurin 17.
    System.setProperty("java.net.preferIPv4Stack", "true")

    AudioEngine.loadLibrary()
    val loadedSettingsForInit = SettingsRepository.loadSettings()
    NetworkHandler_v1.setupLinuxVirtualCable(loadedSettingsForInit.app.useNativeEngine)
    org.bytedeco.javacv.FFmpegLogCallback.set()
    org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all()

    val loadedSettings = SettingsRepository.loadSettings()
    var appSettings    by remember { mutableStateOf(loadedSettings.app) }
    var audioSettings  by remember { mutableStateOf(loadedSettings.audio) }
    var streamingPort  by remember { mutableStateOf(loadedSettings.streamingPort) }
    var micPort        by remember { mutableStateOf(loadedSettings.micPort) }
    var micRoutingMode by remember { mutableStateOf(MicRoutingMode.fromStringSafe(loadedSettings.micRoutingMode)) }

    val isWindowsOS = remember { System.getProperty("os.name").lowercase().contains("win") }
    var serverVolume by remember { mutableStateOf(1f) }

    LaunchedEffect(serverVolume) { NetworkHandler_v1.setServerVolume(serverVolume) }

    LaunchedEffect(appSettings, audioSettings, streamingPort, micPort, micRoutingMode) {
        SettingsRepository.saveSettings(AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode.name))
    }

    var showSettings       by remember { mutableStateOf(false) }
    var isServer           by remember { mutableStateOf(true) }
    val discoveredDevices  = remember { mutableStateMapOf<String, ServerInfo>() }
    var connectionStatus   by remember { mutableStateOf(Strings.get("status_inactive")) }
    var isStreaming         by remember { mutableStateOf(false) }
    var virtualDriverStatus by remember { mutableStateOf<VirtualDriverStatus>(VirtualDriverStatus.Ok) }
    val scope = rememberCoroutineScope()

    val outputDevices = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedOutputDevice by remember { mutableStateOf<Mixer.Info?>(null) }
    val inputDevices  = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedInputDevice  by remember { mutableStateOf<Mixer.Info?>(null) }
    var sendMicrophone          by remember { mutableStateOf(false) }
    var selectedClientMic       by remember { mutableStateOf<Mixer.Info?>(null) }
    var selectedServerMicOutput by remember { mutableStateOf<Mixer.Info?>(null) }
    var selectedMicMixInput     by remember { mutableStateOf<Mixer.Info?>(null) }
    var isMulticastMode by remember { mutableStateOf(appSettings.lastMulticastMode) }
    val isMicMuted by NetworkHandler_v1.isMicMuted.collectAsState()

    val serverCapabilities by remember(appSettings, streamingPort) {
        derivedStateOf {
            val protocols = mutableSetOf(StreamingProtocol.WFAS)
            if (appSettings.rtpEnabled)  protocols.add(StreamingProtocol.RTP)
            if (appSettings.httpEnabled) protocols.add(StreamingProtocol.HTTP)
            val httpPort = if (appSettings.httpEnabled) appSettings.httpPort.toIntOrNull() ?: 8080 else null
            ServerCapabilities(protocols, httpPort, appSettings.httpSafariMode)
        }
    }

    val localIp = remember { NetworkHandler_v1.getLocalIpAddress() }
    val httpUrl by remember(appSettings, isStreaming) {
        derivedStateOf {
            if (isStreaming && appSettings.httpEnabled)
                "http://$localIp:${appSettings.httpPort.toIntOrNull() ?: 8080}"
            else null
        }
    }

    LaunchedEffect(Unit) {
        outputDevices.value = NetworkHandler_v1.findAvailableOutputMixers()
        inputDevices.value  = NetworkHandler_v1.findAvailableInputMixers()
        selectedOutputDevice     = outputDevices.value.firstOrNull()
        selectedInputDevice      = inputDevices.value.firstOrNull()
        selectedClientMic        = inputDevices.value.firstOrNull()
        selectedMicMixInput      = inputDevices.value.firstOrNull()
        selectedServerMicOutput  = outputDevices.value
            .find { it.name.contains("CABLE Input", ignoreCase = true) }
            ?: outputDevices.value.firstOrNull()
        virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus(appSettings.useNativeEngine)
    }

    val useDarkTheme = when (appSettings.theme) {
        Theme.Light  -> false
        Theme.Dark   -> true
        Theme.System -> isSystemInDarkTheme()
    }

    val windowState = rememberWindowState(size = DpSize(600.dp, 800.dp))
    var lastDisconnectTime by remember { mutableStateOf(0L) }

    var isWindowVisible by remember { mutableStateOf(!appSettings.startMinimizedToTray) }
    val trayState = rememberTrayState()
    val trayIcon: Painter = remember { loadTrayIconPainter() }

    val performQuit: () -> Unit = {
        scope.launch {
            NetworkHandler_v1.stopCurrentStream()
            exitApplication()
        }
        Unit
    }

    val hideOrQuit: () -> Unit = {
        if (appSettings.closeToTray) {
            isWindowVisible = false
            runCatching {
                trayState.sendNotification(
                    Notification(
                        title = "WiFi Audio Streamer",
                        message = Strings.get("tray_running_in_background"),
                        type = Notification.Type.Info
                    )
                )
            }
        } else {
            performQuit()
        }
    }

    val showAndRaise: () -> Unit = {
        isWindowVisible = true
        windowState.isMinimized = false
    }

    val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    if (!isLinux) {
        // --- Windows e macOS: Usiamo il Tray nativo di Compose ---
        Tray(
            icon = trayIcon,
            state = trayState,
            tooltip = "WiFi Audio Streamer",
            onAction = showAndRaise,
            menu = {
                Item(
                    text = if (isWindowVisible) Strings.get("tray_hide_window") else Strings.get("tray_show_window"),
                    onClick = {
                        if (isWindowVisible) isWindowVisible = false
                        else showAndRaise()
                    }
                )
                Separator()
                Item(
                    text = Strings.get("tray_quit"),
                    onClick = performQuit
                )
            }
        )
    } else {
        LaunchedEffect(Unit) {
            dorkbox.systemTray.SystemTray.FORCE_GTK2 = false

            val linuxTray = dorkbox.systemTray.SystemTray.get()

            if (linuxTray != null) {
                val iconUrl = Thread.currentThread().contextClassLoader?.getResource("app_icon.png")
                    ?: ClassLoader.getSystemResource("app_icon.png")
                    ?: object {}.javaClass.getResource("/app_icon.png")

                if (iconUrl != null) {
                    linuxTray.setImage(iconUrl)
                }

                linuxTray.setTooltip("WiFi Audio Streamer")

                val toggleItem = dorkbox.systemTray.MenuItem(Strings.get("tray_show_window"))

                toggleItem.setCallback {
                    isWindowVisible = !isWindowVisible
                    if (isWindowVisible) {
                        windowState.isMinimized = false
                        toggleItem.text = Strings.get("tray_hide_window")
                    } else {
                        toggleItem.text = Strings.get("tray_show_window")
                    }
                }

                val quitItem = dorkbox.systemTray.MenuItem(Strings.get("tray_quit")) {
                    linuxTray.shutdown()
                    performQuit()
                }

                linuxTray.menu.add(toggleItem)
                linuxTray.menu.add(dorkbox.systemTray.Separator())
                linuxTray.menu.add(quitItem)

            } else {
                println("Attenzione: Dorkbox SystemTray non è supportato su questo sistema/DE.")
            }
        }
    }

    Window(
        onCloseRequest = hideOrQuit,
        state      = windowState,
        visible    = isWindowVisible,
        undecorated = true,
        icon       = trayIcon
    ) {
        val customColor = appSettings.customThemeColor?.toULong()?.let { Color(it) }
        val currentColorScheme = if (customColor != null) {
            MaterialYouGenerator.generateDynamicColorScheme(customColor, useDarkTheme)
        } else {
            if (useDarkTheme) darkColorScheme() else lightColorScheme()
        }

        MaterialTheme(colorScheme = currentColorScheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val clientDisconnectKeys = remember {
                    setOf(
                        "status_server_disconnected",
                        "status_server_no_response",
                        "status_handshake_failed"
                    )
                }

                val clientStatusHandler: (key: String, args: Array<out Any>) -> Unit = { key, args ->
                    connectionStatus = if (args.isEmpty()) Strings.get(key) else Strings.get(key, *args)
                    if (key in clientDisconnectKeys) {
                        scope.launch {
                            NetworkHandler_v1.stopCurrentStream()
                            isStreaming = false
                            connectionStatus = Strings.get("status_inactive")
                            if (!isServer) {
                                NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                                    if (serverInfo.lastSeen == 0L) {
                                        discoveredDevices.remove(hostname)
                                    } else {
                                        discoveredDevices[hostname] = serverInfo
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (appSettings.autoStartServer && isServer) {
                        isStreaming = true
                        connectionStatus = "Auto-starting Server..."
                        val port = streamingPort.toIntOrNull() ?: 9090
                        val mic = micPort.toIntOrNull() ?: 9092
                        val rtp = appSettings.rtpPort.toIntOrNull() ?: 9094

                        isMulticastMode = appSettings.autoStartMulticast

                        NetworkHandler_v1.launchServerInstance(
                            audioSettings, port, isMulticastMode, serverCapabilities,
                            micRoutingMode, selectedServerMicOutput, mic, rtp,
                            appSettings.useNativeEngine, selectedMicMixInput
                        ) { key, args ->
                            if (key == "error_virtual_driver_missing") {
                                isStreaming = false
                                virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus(appSettings.useNativeEngine)
                                connectionStatus = Strings.get("status_inactive")
                            } else if (key == "status_client_disconnected") {
                                scope.launch {
                                    NetworkHandler_v1.stopCurrentStream()
                                    isStreaming = false
                                    connectionStatus = Strings.get("status_inactive")
                                }
                            } else {
                                connectionStatus = if (args.isEmpty()) key else String.format(key, *args)
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (appSettings.autoConnectClientEnabled) {
                        isServer = false
                        discoveredDevices.clear()
                        NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                            discoveredDevices[hostname] = serverInfo
                        }
                    }
                }

                LaunchedEffect(isStreaming) {
                    if (!isStreaming) {
                        lastDisconnectTime = System.currentTimeMillis()
                    }
                }

                LaunchedEffect(isServer, appSettings.autoConnectClientEnabled, appSettings.autoConnectIps, selectedOutputDevice) {
                    if (isServer || !appSettings.autoConnectClientEnabled || appSettings.autoConnectIps.isEmpty() || selectedOutputDevice == null) return@LaunchedEffect

                    val port = streamingPort.toIntOrNull() ?: 9090
                    val mic = micPort.toIntOrNull() ?: 9092

                    while (isActive) {
                        val currentTime = System.currentTimeMillis()
                        val canReconnect = (currentTime - lastDisconnectTime) >= 10000L

                        if (!isStreaming && canReconnect) {
                            for (ip in appSettings.autoConnectIps) {
                                if (ip.isBlank() || isStreaming) continue

                                val knownServer = discoveredDevices.values.find { it.ip == ip }
                                val isOnline = knownServer != null || NetworkHandler_v1.pingServerUnicast(ip, port)

                                if (isOnline) {
                                    val targetServer = knownServer ?: ServerInfo(ip, false, port, null)
                                    isStreaming = true
                                    connectionStatus = "Auto-connecting to ${targetServer.ip}..."

                                    NetworkHandler_v1.endDeviceDiscovery()
                                    NetworkHandler_v1.launchClientInstance(
                                        audioSettings, targetServer, selectedOutputDevice!!,
                                        sendMicrophone, selectedClientMic, mic,
                                        appSettings.connectionSoundEnabled,
                                        appSettings.disconnectionSoundEnabled,
                                        clientStatusHandler
                                    )
                                    break
                                }
                            }
                        }
                        delay(5000)
                    }
                }
                LaunchedEffect(Unit) {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        // Rimuove i server che non si sentono da più di 12 secondi
                        val toRemove = discoveredDevices.filter { now - it.value.lastSeen > 12000L || it.value.lastSeen == 0L }.keys
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach { discoveredDevices.remove(it) }
                        }
                        delay(5000)
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    CustomTitleBar(
                        windowState = windowState,
                        onMinimize  = { windowState.isMinimized = true },
                        onClose     = hideOrQuit
                    )
                    Box(Modifier.fillMaxSize()) {
                        AppContent(
                            appSettings               = appSettings,
                            audioSettings             = audioSettings,
                            isServer                  = isServer,
                            isStreaming               = isStreaming,
                            connectionStatus          = connectionStatus,
                            discoveredDevices         = discoveredDevices,
                            isMulticastMode           = isMulticastMode,
                            sendMicrophone            = sendMicrophone,
                            outputDevices             = outputDevices.value,
                            selectedOutputDevice      = selectedOutputDevice,
                            inputDevices              = inputDevices.value,
                            selectedInputDevice       = null,
                            selectedClientMic         = selectedClientMic,
                            selectedServerMicOutput   = selectedServerMicOutput,
                            streamingPort             = streamingPort,
                            localIp                   = localIp,
                            httpUrl                   = httpUrl,
                            serverVolume              = serverVolume,
                            onServerVolumeChange      = { serverVolume = it },
                            isWindowsOS               = isWindowsOS,
                            virtualDriverStatus       = virtualDriverStatus,
                            onDismissPrivacyBanner    = { dontShowAgain ->
                                if (dontShowAgain) appSettings = appSettings.copy(hideWindowsPrivacyBanner = true)
                            },
                            onDismissRoutingBanner    = { dontShowAgain ->
                                if (dontShowAgain) appSettings = appSettings.copy(hideWindowsRoutingBanner = true)
                            },
                            onConnectManual = { ip ->
                                isStreaming = true
                                connectionStatus = "Detecting mode for $ip..."
                                val port = streamingPort.toIntOrNull() ?: 9090
                                val mic  = micPort.toIntOrNull() ?: 9092

                                scope.launch {
                                    // 1. Controllo furbo: l'IP è già stato scoperto in background?
                                    val knownServer = discoveredDevices.values.find { it.ip == ip }

                                    // 2. Auto-rilevamento: se è sconosciuto, eseguiamo il ping silente
                                    val isMulti = knownServer?.isMulticast ?: NetworkHandler_v1.probeIsMulticast(ip, port)

                                    val manualServerInfo = ServerInfo(ip, isMulti, port, knownServer?.capabilities)
                                    NetworkHandler_v1.endDeviceDiscovery()
                                    NetworkHandler_v1.launchClientInstance(
                                        audioSettings, manualServerInfo, selectedOutputDevice!!,
                                        sendMicrophone, selectedClientMic, mic,
                                        appSettings.connectionSoundEnabled,
                                        appSettings.disconnectionSoundEnabled,
                                        clientStatusHandler
                                    )
                                }
                            },
                            onModeChange = { isSrv ->
                                isServer = isSrv
                                isStreaming = false
                                connectionStatus = Strings.get("status_inactive")
                                discoveredDevices.clear() // Svuota SEMPRE al cambio modalità

                                if (!isSrv) {
                                    NetworkHandler_v1.requestStopCurrentStream()
                                    NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                                        if (serverInfo.lastSeen == 0L) {
                                            discoveredDevices.remove(hostname)
                                        } else {
                                            discoveredDevices[hostname] = serverInfo
                                        }
                                    }
                                } else {
                                    NetworkHandler_v1.endDeviceDiscovery()
                                }
                            },
                            onStartStreaming = {
                                isStreaming      = true
                                connectionStatus = "Starting Server..."
                                val port = streamingPort.toIntOrNull() ?: 9090
                                val mic  = micPort.toIntOrNull() ?: 9092
                                val rtp  = appSettings.rtpPort.toIntOrNull() ?: 9094

                                NetworkHandler_v1.launchServerInstance(
                                    audioSettings, port, isMulticastMode, serverCapabilities,
                                    micRoutingMode, selectedServerMicOutput, mic, rtp,
                                    appSettings.useNativeEngine, selectedMicMixInput
                                ) { key, args ->
                                    if (key == "error_virtual_driver_missing") {
                                        isStreaming          = false
                                        virtualDriverStatus  = NetworkHandler_v1.checkVirtualDriverStatus(appSettings.useNativeEngine)
                                        connectionStatus     = Strings.get("status_inactive")
                                    } else if (key == "status_client_disconnected") {
                                        scope.launch {
                                            NetworkHandler_v1.stopCurrentStream()
                                            isStreaming = false
                                            connectionStatus = Strings.get("status_inactive")
                                        }
                                    } else {
                                        connectionStatus = if (args.isEmpty()) key else String.format(key, *args)
                                    }
                                }
                            },
                            onStopStreaming = {
                                isStreaming      = false
                                connectionStatus = Strings.get("status_inactive")
                                NetworkHandler_v1.requestStopCurrentStream()
                            },
                            onConnectToServer = { serverInfo ->
                                isStreaming = true
                                val mic = micPort.toIntOrNull() ?: 9092
                                NetworkHandler_v1.endDeviceDiscovery()
                                NetworkHandler_v1.launchClientInstance(
                                    audioSettings, serverInfo, selectedOutputDevice!!,
                                    sendMicrophone, selectedClientMic, mic,
                                    appSettings.connectionSoundEnabled,
                                    appSettings.disconnectionSoundEnabled,
                                    clientStatusHandler
                                )
                            },
                            onRefreshDevices = {
                                discoveredDevices.clear()
                                NetworkHandler_v1.endDeviceDiscovery()
                                NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                                    discoveredDevices[hostname] = serverInfo
                                }
                            },
                            onMulticastModeChange      = {
                                isMulticastMode = it
                                appSettings = appSettings.copy(lastMulticastMode = it)
                            },
                            onSendMicrophoneChange     = { send -> sendMicrophone = send },
                            onSelectedOutputDeviceChange      = { selectedOutputDevice = it },
                            onSelectedInputDeviceChange       = { selectedInputDevice  = it },
                            onSelectedClientMicChange         = { selectedClientMic    = it },
                            onSelectedServerMicOutputChange   = { selectedServerMicOutput = it },
                            selectedMicMixInput               = selectedMicMixInput,
                            onMicMixInputSelected             = { selectedMicMixInput = it },
                            onAppSettingsChange               = { appSettings = it },
                            onOpenSettings                    = { showSettings = true },
                            isMicMuted                        = isMicMuted,
                            onMicMuteToggle                   = { NetworkHandler_v1.isMicMuted.value = !NetworkHandler_v1.isMicMuted.value },
                            micRoutingMode                    = micRoutingMode,
                            onMicRoutingModeChange            = { micRoutingMode = it }
                        )

                        SettingsScreen(
                            visible                = showSettings,
                            appSettings            = appSettings,
                            audioSettings          = audioSettings,
                            streamingPort          = streamingPort,
                            micPort                = micPort,
                            onAppSettingsChange    = { appSettings    = it },
                            onAudioSettingsChange  = { audioSettings  = it },
                            onStreamingPortChange  = { streamingPort  = it },
                            onMicPortChange        = { micPort        = it },
                            onClose                = { showSettings   = false },
                            onCustomColorChange    = { color -> appSettings = appSettings.copy(customThemeColor = color) }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom title bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WindowScope.CustomTitleBar(
    windowState: WindowState,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor   = MaterialTheme.colorScheme.surface

    var preMaximizeSize     by remember { mutableStateOf(windowState.size) }
    var preMaximizePosition by remember { mutableStateOf(windowState.position) }

    val maxBounds           = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val density             = LocalDensity.current.density
    val maximizedWidth      = (maxBounds.width  / density).dp
    val maximizedHeight     = (maxBounds.height / density).dp
    val maximizedPositionX  = (maxBounds.x / density).dp
    val maximizedPositionY  = (maxBounds.y / density).dp
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
                text     = "WiFi Audio Streaming",
                style    = MaterialTheme.typography.titleSmall,
                color    = onSurfaceColor,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Minimize, "Minimize", tint = onSurfaceColor)
                }
                IconButton(
                    onClick = {
                        if (isManuallyMaximized) {
                            windowState.size     = preMaximizeSize
                            windowState.position = preMaximizePosition
                        } else {
                            preMaximizeSize     = windowState.size
                            preMaximizePosition = windowState.position
                            windowState.size     = DpSize(maximizedWidth, maximizedHeight)
                            windowState.position = WindowPosition(maximizedPositionX, maximizedPositionY)
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (isManuallyMaximized) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        "Maximize/Restore", tint = onSurfaceColor
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = onSurfaceColor)
                }
            }
        }
    }
}