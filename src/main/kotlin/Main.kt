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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Update
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
    val safariMode: Boolean = false,
    val securityMode: String? = null,
    val encrypted: Boolean = false,
    val serverSendsMic: Boolean = false,
    val serverWantsMic: Boolean = false
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
    val lastSeen: Long = System.currentTimeMillis(),
    val serverAudioSettings: AudioSettings_V1? = null
)

data class AudioSettings_V1(
    val sampleRate: Float,
    val bitDepth: Int,
    val channels: Int,
    val bufferSize: Int,
    val latencyMs: Int = 120,
    val maxPayloadBytes: Int = 1390
) {
    fun toAudioFormat(): AudioFormat = AudioFormat(sampleRate, bitDepth, channels, true, false)
}

data class ProtocolMismatch(val localVersion: Int, val remoteVersion: Int)

object WfasStats {
    enum class Cat { AUDIO, SILENCE, PING, BYE, HELLO, PROBE, OTHER }

    @Volatile var active = false
        private set
    @Volatile var sending = true
        private set
    @Volatile var peer: String = "-"
    @Volatile private var startNanos = 0L

    private val nCat = Cat.entries.size
    private val pkts = java.util.concurrent.atomic.AtomicLongArray(nCat)
    private val byts = java.util.concurrent.atomic.AtomicLongArray(nCat)

    fun begin(sending: Boolean, peer: String) {
        this.sending = sending
        this.peer = peer
        for (i in 0 until nCat) { pkts.set(i, 0); byts.set(i, 0) }
        startNanos = System.nanoTime()
        active = true
    }

    fun stop() { active = false }

    fun add(cat: Cat, bytes: Int) {
        if (!active) return
        pkts.incrementAndGet(cat.ordinal)
        if (bytes > 0) byts.addAndGet(cat.ordinal, bytes.toLong())
    }

    fun elapsedSeconds(): Double {
        val s = startNanos
        return if (s == 0L) 0.0 else (System.nanoTime() - s) / 1e9
    }

    fun pkts(cat: Cat): Long = pkts.get(cat.ordinal)
    fun bytes(cat: Cat): Long = byts.get(cat.ordinal)
    fun totalPkts(): Long { var s = 0L; for (i in 0 until nCat) s += pkts.get(i); return s }
    fun totalBytes(): Long { var s = 0L; for (i in 0 until nCat) s += byts.get(i); return s }
}

object MicStats {
    enum class Dir { OFF, SENDING, RECEIVING }

    @Volatile var dir: Dir = Dir.OFF
        private set
    @Volatile var detail: String = "-"
        private set

    private val audioPkts   = java.util.concurrent.atomic.AtomicLong(0)
    private val audioBytes  = java.util.concurrent.atomic.AtomicLong(0)
    private val silentPkts  = java.util.concurrent.atomic.AtomicLong(0)
    private val silentBytes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastNanos = 0L

    fun begin(dir: Dir, detail: String) {
        audioPkts.set(0); audioBytes.set(0); silentPkts.set(0); silentBytes.set(0)
        this.detail = detail
        this.dir = dir
        lastNanos = System.nanoTime()
    }

    fun off() { dir = Dir.OFF }

    fun addAudio(bytes: Int) {
        if (dir == Dir.OFF) return
        audioPkts.incrementAndGet()
        if (bytes > 0) audioBytes.addAndGet(bytes.toLong())
    }

    fun addSilence(bytes: Int) {
        if (dir == Dir.OFF) return
        silentPkts.incrementAndGet()
        if (bytes > 0) silentBytes.addAndGet(bytes.toLong())
    }

    fun audioPkts(): Long  = audioPkts.get()
    fun audioBytes(): Long = audioBytes.get()
    fun silentPkts(): Long  = silentPkts.get()
    fun silentBytes(): Long = silentBytes.get()
    fun totalPkts(): Long  = audioPkts.get() + silentPkts.get()
    fun totalBytes(): Long = audioBytes.get() + silentBytes.get()
    fun secondsSinceStart(): Double {
        val s = lastNanos
        return if (s == 0L) 0.0 else (System.nanoTime() - s) / 1e9
    }
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

/**
 * Interruttore globale del denoiser: il player viene creato a ogni connessione,
 * ma i controlli devono agire sul flusso gia' in riproduzione. Sta a livello di
 * file perche' lo leggono sia JitterAudioPlayer (dentro NetworkHandler_v1) sia
 * i composable dell'interfaccia.
 */
object NoiseReductionControl {
    @Volatile var enabled: Boolean = false
    @Volatile var strength: Int = 50
    fun apply(on: Boolean, strengthPercent: Int) {
        enabled = on
        strength = strengthPercent.coerceIn(0, 100)
    }
}

object NetworkHandler_v1 {

    // ── Constants ──────────────────────────────────────────────────────────────
    private const val DISCOVERY_PORT       = 9091
    private const val CLIENT_HELLO_MESSAGE = "HELLO_FROM_CLIENT"
    private const val MULTICAST_GROUP_IP   = "239.255.0.1"
    private const val DISCOVERY_MESSAGE    = "WIFI_AUDIO_STREAMER_DISCOVERY"
    private const val DISCOVERY_VERSION    = 2

    const val WFAS_PROTOCOL_VERSION = 2
    private const val INCOMPATIBLE_PREFIX = "WFAS_INCOMPATIBLE"
    private const val HELLO_ACK_PREFIX    = "HELLO_ACK"
    private const val PENDING_MESSAGE       = "WFAS_PENDING"
    private const val AUTH_REQUIRED_PREFIX  = "WFAS_AUTH_REQUIRED"
    private const val BUSY_MESSAGE = "WFAS_BUSY"
    private const val UNAUTHORIZED_MESSAGE  = "WFAS_UNAUTHORIZED"

    @Volatile var securityMode: String = "OFF"
    @Volatile var authKey: String = ""
    @Volatile var encryptionEnabled: Boolean = false
    fun configureSecurity(mode: String, key: String, encrypt: Boolean = false) {
        securityMode = mode; authKey = key; encryptionEnabled = encrypt
    }
    // Pre-shared key used only when acting as a CLIENT (e.g. CLI --auth-key). The
    // GUI client leaves this empty: it gets the key from the on-connect dialog.
    @Volatile var clientPresharedKey: String = ""
    // Mic-stream session keys (separate long-lived jobs): server decrypts incoming
    // mic with micRecvDir; client encrypts outgoing mic with micSendDir.
    @Volatile var micRecvDir: WfasCrypto.Dir? = null
    @Volatile var micRecvWin: WfasCrypto.ReplayWindow = WfasCrypto.ReplayWindow()
    @Volatile var micSendDir: WfasCrypto.Dir? = null
    var onAuthRequest: ((peer: String) -> Boolean)? = null
    var onKeyRequest: (suspend (wrong: Boolean) -> String?)? = null

    private fun clientHelloMessage(): String = "$CLIENT_HELLO_MESSAGE;v=$WFAS_PROTOCOL_VERSION"
    private fun helloAckMessage():    String = "$HELLO_ACK_PREFIX;v=$WFAS_PROTOCOL_VERSION"
    private fun incompatibleMessage(): String = "$INCOMPATIBLE_PREFIX;v=$WFAS_PROTOCOL_VERSION"

    private fun parseProtocolVersion(message: String): Int =
        message.split(";").firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")?.trim()?.toIntOrNull() ?: 0

    val protocolMismatch = MutableStateFlow<ProtocolMismatch?>(null)

    private fun signalProtocolMismatch(remoteVersion: Int) {
        if (protocolMismatch.value == null) {
            protocolMismatch.value = ProtocolMismatch(WFAS_PROTOCOL_VERSION, remoteVersion)
        }
    }

    fun clearProtocolMismatch() { protocolMismatch.value = null }

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
            val localIp = getLocalIpAddress()
            val matchByIp = allInterfaces.firstOrNull { iface ->
                iface.isUp && !iface.isLoopback &&
                        iface.inetAddresses.toList().any { addr ->
                            addr is java.net.Inet4Address && addr.hostAddress == localIp
                        }
            }
            matchByIp ?: allInterfaces.firstOrNull { iface ->
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
                aSocket(selectorManager).udp().bind().use { sock ->
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
                aSocket(selectorManager).udp().bind().use { sock ->
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
    private val selectorManager = SelectorManager(Dispatchers.IO)

    val isMicMuted = MutableStateFlow(false)

    private var streamingJob:    Job? = null
    private var listeningJob:    Job? = null
    private var broadcastingJob: Job? = null
    private var micReceiverJob:  Job? = null
    private var localMicMixJob:  Job? = null
    private var httpServerJob:   Job? = null
    private var donationTimerJob: Job? = null

    private fun startDonationTimer() {
        donationTimerJob?.cancel()
        donationTimerJob = scope.launch {
            delay(3 * 60 * 1000L)
            SettingsRepository.setDonationQualified(true)
        }
    }
    private fun cancelDonationTimer() { donationTimerJob?.cancel(); donationTimerJob = null }
    private var rtpJob:          Job? = null

    private val lifecycleMutex = Mutex()
    private val lifecycleGeneration = java.util.concurrent.atomic.AtomicLong(0)

    // ── AudioEngine (motore nativo JNI) ───────────────────────────────────────
    @Volatile private var serverEngine: AudioEngine? = null

    private val softwareMicMixer = SoftwareMicMixer()

    // ── FFmpeg grabber (motore legacy) ────────────────────────────────────────
    @Volatile private var serverGrabber: org.bytedeco.javacv.FFmpegFrameGrabber? = null

    // ── Protocollo audio: header 10 byte preposto a ogni pacchetto PCM ────────
    // Layout (Big-Endian): [magic:2=0x5746][version:1=0x02][flags:1][seqNum:2][samplePos:4]
    // flags bit0=silence. I pacchetti control (PING/BYE/HELLO) iniziano con ASCII
    // e non matchano mai il magic 0x57 0x46, quindi la distinzione è inequivoca.
    private val AUDIO_MAGIC_0: Byte = 0x57   // 'W'
    private val AUDIO_MAGIC_1: Byte = 0x46   // 'F'
    private val AUDIO_VERSION: Byte = WFAS_PROTOCOL_VERSION.toByte()
    private val AUDIO_HEADER_SIZE = 10

    private fun writeMicHeader(dst: ByteArray, seq: Int, silence: Boolean) {
        dst[0] = AUDIO_MAGIC_0
        dst[1] = AUDIO_MAGIC_1
        dst[2] = AUDIO_VERSION
        dst[3] = if (silence) 0x01 else 0x00
        dst[4] = ((seq shr 8) and 0xFF).toByte()
        dst[5] = (seq and 0xFF).toByte()
        dst[6] = 0; dst[7] = 0; dst[8] = 0; dst[9] = 0
    }

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

    // ── macOS volume mute-on-server ───────────────────────────────────────────
    @Volatile private var macOriginalVolume: Float = -1f

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
        AppDebug.log("---Linux: saved sink='$originalLinuxSink' source='$originalLinuxSource' ---")
        runCatching { ProcessBuilder("pactl", "set-default-sink",   "VirtualCable").start().waitFor() }
        runCatching { ProcessBuilder("pactl", "set-default-source", "VirtualCable.monitor").start().waitFor() }
        AppDebug.log("---Linux: audio routed to VirtualCable ---")
    }

    fun restoreLinuxAudioRouting() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val sink   = originalLinuxSink   ?: run { AppDebug.log("---Linux: nothing to restore ---"); return }
        val source = originalLinuxSource
        runCatching { ProcessBuilder("pactl", "set-default-sink", sink).start().waitFor() }
        source?.let { runCatching { ProcessBuilder("pactl", "set-default-source", it).start().waitFor() } }
        AppDebug.log("---Linux: audio restored to sink='$sink' source='$source' ---")
        originalLinuxSink   = null
        originalLinuxSource = null
    }

    fun setupLinuxVirtualCable(useNativeEngine: Boolean) {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        if (useNativeEngine) return
        try {
            if (ProcessBuilder("which", "pactl").start().waitFor() != 0) {
                AppDebug.log("'pactl' not found - cannot create virtual cable automatically.")
                return
            }
            getPactlDefault("sink").takeIf { it != null && !it.contains("VirtualCable", true) }
                ?.also { originalLinuxSink = it }
            getPactlDefault("source").takeIf { it != null && !it.contains("VirtualCable", true) }
                ?.also { originalLinuxSource = it }
            AppDebug.log("---Linux: real sink saved at startup: '$originalLinuxSink' ---")

            val check = ProcessBuilder("sh", "-c", "pactl list short sinks | grep VirtualCable").start()
            if (check.waitFor() == 0) { AppDebug.log("---Linux: VirtualCable already active ---"); return }

            AppDebug.log("---Linux: creating VirtualCable... ---")
            val create = ProcessBuilder(
                "pactl", "load-module", "module-null-sink",
                "sink_name=VirtualCable",
                "sink_properties=device.description=VirtualCable"
            ).start()
            if (create.waitFor() == 0) AppDebug.log("---Linux: VirtualCable created ---")
            else AppDebug.log("---Linux: failed to create VirtualCable ---")
        } catch (e: Exception) {
            AppDebug.log("Linux audio setup error: ${e.message}")
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
                        "Cannot load native audio engine.\n$loadErr",
                        "Rebuild with: ./gradlew copyNativeLib"
                    )
                    else -> VirtualDriverStatus.Missing(
                        "Native audio engine",
                        "Rebuild with: ./gradlew copyNativeLib — $loadErr"
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
                        packet.length = buffer.size
                        socket.receive(packet)
                        val remoteIp = packet.address.hostAddress
                        val message  = String(packet.data, 0, packet.length).trim()

                        if (remoteIp in localIps || !message.startsWith(DISCOVERY_MESSAGE)) continue

                        AppDebug.log("[DISCOVERY] Ricevuto da $remoteIp: $message")
                        val parts = message.split(";")
                        if (parts.size < 4) { AppDebug.log("[DISCOVERY] pacchetto malformato (parti=${parts.size}): $message"); continue }
                        val hostname = parts[1]

                        if (message.contains("BYE")) {
                            AppDebug.log("[DISCOVERY] BYE da $hostname ($remoteIp), rimuovo dalla lista")
                            onDeviceFound(hostname, ServerInfo("", false, 0, null, 0L))
                            continue
                        }

                        val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                        val port        = parts[3].toIntOrNull() ?: continue
                        AppDebug.log("[DISCOVERY] Server found: hostname=$hostname ip=$remoteIp isMulticast=$isMulticast port=$port")

                        val capabilities = if (parts.size >= 5) {
                            val protoStr = parts.firstOrNull { it.startsWith("protocols=") }
                                ?.removePrefix("protocols=") ?: "WFAS"
                            val protocols = protoStr.split(",").mapNotNull { token ->
                                runCatching { StreamingProtocol.valueOf(token.trim()) }.getOrNull()
                            }.toSet().ifEmpty { setOf(StreamingProtocol.WFAS) }
                            val httpPort = parts.firstOrNull { it.startsWith("http_port=") }
                                ?.removePrefix("http_port=")?.toIntOrNull()
                            val authMode = parts.firstOrNull { it.startsWith("auth=") }
                                ?.removePrefix("auth=")?.uppercase()
                            val encrypted = parts.firstOrNull { it.startsWith("enc=") }
                                ?.removePrefix("enc=") == "1"
                            val micTok = parts.firstOrNull { it.startsWith("mic=") }?.removePrefix("mic=")
                            ServerCapabilities(
                                protocols, httpPort, securityMode = authMode, encrypted = encrypted,
                                serverSendsMic = micTok?.contains("tx") == true,
                                serverWantsMic = micTok?.contains("rx") == true
                            )
                        } else {
                            ServerCapabilities(setOf(StreamingProtocol.WFAS), null)
                        }
                        val discoveredSr = parts.firstOrNull { it.startsWith("sr=") }?.removePrefix("sr=")?.toFloatOrNull()
                        val discoveredCh = parts.firstOrNull { it.startsWith("ch=") }?.removePrefix("ch=")?.toIntOrNull()
                        val discoveredBd = parts.firstOrNull { it.startsWith("bd=") }?.removePrefix("bd=")?.toIntOrNull()
                        val discoveredAudio = if (discoveredSr != null && discoveredCh != null && discoveredBd != null)
                            AudioSettings_V1(discoveredSr, discoveredBd, discoveredCh, 6400)
                        else null
                        AppDebug.log("[DISCOVERY] caps auth=${capabilities.securityMode} enc=${capabilities.encrypted} micTx=${capabilities.serverSendsMic} micRx=${capabilities.serverWantsMic}")
                        onDeviceFound(hostname, ServerInfo(remoteIp, isMulticast, port, capabilities, System.currentTimeMillis(), discoveredAudio))
                    } catch (_: java.net.SocketTimeoutException) { continue }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) AppDebug.log("Discovery listening error: ${e.message}")
            } finally {
                runCatching { socket?.leaveGroup(groupAddress) }
                socket?.close()
            }
        }
    }

    fun endDeviceDiscovery() { listeningJob?.cancel() }

    fun startAnnouncingPresence(isMulticast: Boolean, port: Int, capabilities: ServerCapabilities, audioSettings: AudioSettings_V1? = null) {
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch {
            val hostname     = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop-PC")
            val mode         = if (isMulticast) "MULTICAST" else "UNICAST"
            val protocolsStr = capabilities.protocols.joinToString(",") { it.name }
            val httpPortStr  = capabilities.httpPort?.let { ";http_port=$it" } ?: ""
            val audioStr     = if (audioSettings != null) ";sr=${audioSettings.sampleRate.toInt()};ch=${audioSettings.channels};bd=${audioSettings.bitDepth}" else ""
            val micCode      = "${if (capabilities.serverSendsMic) "tx" else ""}${if (capabilities.serverWantsMic) "rx" else ""}"
            val micStr       = if (micCode.isNotEmpty()) ";mic=$micCode" else ""
            val staticPrefix = "$DISCOVERY_MESSAGE;$hostname;$mode;$port;protocols=$protocolsStr$httpPortStr$audioStr"
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
            MulticastSocket().use { socket ->
                socket.timeToLive = 4
                getActiveNetworkInterface()?.let { socket.networkInterface = it }
                while (isActive) {
                    val encOn   = encryptionEnabled && securityMode.equals("KEY", ignoreCase = true)
                    val secStr  = ";auth=$securityMode;enc=${if (encOn) 1 else 0}"
                    val message = "$staticPrefix$secStr$micStr"
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
        micMixInputInfo: Mixer.Info? = null,
        onStatusUpdate: ((key: String, args: Array<out Any>) -> Unit)? = null
    ) = launch {
        if (routingMode == MicRoutingMode.OFF) return@launch

        fun reportMicError(msg: String) {
            AppDebug.log("[MicReceiver] $msg")
            onStatusUpdate?.invoke("status_mic_route_failed", arrayOf(msg))
        }

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
                            reportMicError("createVirtualSink failed: ${engine.lastError}")
                            return@launch
                        }
                        activeVirtualSinkEngine = engine
                        nativeVirtualSinkActive = true
                        AppDebug.log("[MicReceiver] Virtual sink attivo: ${engine.virtualSinkName()}")
                    } else if (isWindows) {
                        val engine = AudioEngine(
                            sampleRate   = audioSettings.sampleRate.toInt(),
                            channels     = audioSettings.channels,
                            bufferFrames = audioSettings.bufferSize / (audioSettings.channels * 2)
                        )
                        val hint = micOutputMixerInfo?.name
                        val opened = engine.micSinkOpen(hint, audioSettings.sampleRate.toInt(), audioSettings.channels)
                        if (!opened) {
                            AppDebug.log("[MicReceiver] WASAPI micSinkOpen failed: ${engine.lastError}")
                            val outputs = runCatching { findAvailableOutputMixers() }.getOrDefault(emptyList())
                            val fallback = micOutputMixerInfo
                                ?: VirtualMicAutodetect.detectManualCable(outputs)?.mixerInfo
                                ?: run {
                                    reportMicError("No cable device (VB-Cable) selected or detected. Install VB-Cable and select it.")
                                    return@launch
                                }
                            val mixer = runCatching { AudioSystem.getMixer(fallback) }.getOrNull()
                                ?: run { reportMicError("Mixer '${fallback.name}' cannot be opened."); return@launch }
                            val format   = audioSettings.toAudioFormat()
                            val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
                            if (!mixer.isLineSupported(lineInfo)) {
                                reportMicError("'${fallback.name}' does not support ${format.sampleRate.toInt()}Hz/${format.channels}ch.")
                                return@launch
                            }
                            line = (mixer.getLine(lineInfo) as SourceDataLine).also {
                                it.open(format, (audioSettings.sampleRate.toInt() * audioSettings.latencyMs / 1000) * audioSettings.channels * 2)
                                it.start()
                            }
                            AppDebug.log("[MicReceiver] Fallback SourceDataLine su '${fallback.name}'")
                        } else {
                            wasapiSinkEngine = engine
                            AppDebug.log("[MicReceiver] WASAPI sink attivo su '${engine.micSinkDeviceName()}'")
                        }
                    } else {
                        val outputs = runCatching { findAvailableOutputMixers() }.getOrDefault(emptyList())
                        val mixerInfo = micOutputMixerInfo
                            ?: VirtualMicAutodetect.detectManualCable(outputs)?.mixerInfo
                            ?: run {
                                reportMicError("No virtual device (BlackHole) selected or detected. Install BlackHole and select it.")
                                return@launch
                            }
                        AppDebug.log("[MicReceiver] Opening SourceDataLine to '${mixerInfo.name}'")
                        val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: run {
                            reportMicError("Mixer '${mixerInfo.name}' cannot be opened.")
                            return@launch
                        }
                        val format   = audioSettings.toAudioFormat()
                        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
                        if (!mixer.isLineSupported(lineInfo)) {
                            reportMicError("'${mixerInfo.name}' does not support ${format.sampleRate.toInt()}Hz/${format.channels}ch/${format.sampleSizeInBits}bit.")
                            return@launch
                        }
                        line = (mixer.getLine(lineInfo) as SourceDataLine).also {
                            it.open(format, (audioSettings.sampleRate.toInt() * audioSettings.latencyMs / 1000) * audioSettings.channels * 2)
                            it.start()
                        }
                        AppDebug.log("[MicReceiver] SourceDataLine opened: buffer=${line?.bufferSize} bytes, format=$format")
                    }
                }
                MicRoutingMode.MIX_INTO_STREAM -> {
                    mixActive = true
                    softwareMicMixer.enable(true)
                    softwareMicMixer.volume = if (isMicMuted.value) 0f else 1.0f
                    launch {
                        var enabledOn: AudioEngine? = null
                        while (isActive) {
                            val cur = serverEngine
                            if (cur != null && cur !== enabledOn) {
                                runCatching { cur.setMicMixEnabled(true) }
                                runCatching { cur.setMicMixVolume(if (isMicMuted.value) 0f else 1.0f) }
                                enabledOn = cur
                            }
                            delay(200)
                        }
                    }
                    muteCollectorJob = launch {
                        isMicMuted.collect { muted ->
                            val v = if (muted) 0f else 1.0f
                            runCatching { serverEngine?.setMicMixVolume(v) }
                            softwareMicMixer.volume = v
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
                AppDebug.log("[MicReceiver] Listening on port $micPort (mode=$routingMode) rcvbuf=${socket.receiveBufferSize}")
                MicStats.begin(MicStats.Dir.RECEIVING, ":$micPort ($routingMode)")

                val reusableShorts = ShortArray(buf.size / 2)
                val lineOutBytes   = ByteArray(buf.size)
                var totalPackets = 0L
                var totalBytes   = 0L
                var micVersionChecked = false
                var lastPayloadShorts = 0
                var micEncSeen = false

                while (isActive) {
                    try {
                        packet.setData(buf, 0, buf.size)
                        socket.receive(packet)
                        val rawLen = packet.length
                        if (rawLen >= AUDIO_HEADER_SIZE && buf[0] == AUDIO_MAGIC_0 && buf[1] == AUDIO_MAGIC_1) {
                            if ((buf[3].toInt() and WfasCrypto.FLAG_ENCRYPTED) != 0) {
                                val md = micRecvDir ?: continue
                                val r = WfasCrypto.decryptPacket(md, micRecvWin, buf, rawLen)
                                if (r !is WfasCrypto.Decrypted.Ok) continue
                                micEncSeen = true
                                System.arraycopy(r.pcm, 0, buf, AUDIO_HEADER_SIZE, r.pcm.size)
                                packet.setData(buf, 0, AUDIO_HEADER_SIZE + r.pcm.size)
                            } else if (micEncSeen) {
                                continue
                            }
                        }
                        val len = packet.length
                        if (len <= 0) continue

                        var dataOff = 0
                        var dataLen = len
                        var silence = false
                        if (len >= AUDIO_HEADER_SIZE && packet.data[0] == AUDIO_MAGIC_0 && packet.data[1] == AUDIO_MAGIC_1) {
                            if (!micVersionChecked) {
                                micVersionChecked = true
                                val ver = packet.data[2].toInt() and 0xFF
                                if (ver != WFAS_PROTOCOL_VERSION) {
                                    signalProtocolMismatch(ver)
                                    onStatusUpdate?.invoke("status_protocol_incompatible", emptyArray())
                                    AppDebug.log("[MicReceiver] mic v=$ver incompatible (mine v=$WFAS_PROTOCOL_VERSION)")
                                    break
                                }
                            }
                            silence = (packet.data[3].toInt() and 0x01) != 0
                            dataOff = AUDIO_HEADER_SIZE
                            dataLen = len - AUDIO_HEADER_SIZE
                        }

                        val effSilence = silence || isMicMuted.value
                        if (effSilence) MicStats.addSilence(len) else MicStats.addAudio(len)

                        totalPackets++
                        totalBytes += len
                        if (totalPackets == 1L || totalPackets % 200L == 0L) {
                            AppDebug.log("[MicReceiver] pkt #$totalPackets from ${packet.address.hostAddress}:${packet.port} len=$len silence=$effSilence mode=$routingMode")
                        }

                        val numShorts: Int
                        if (effSilence) {
                            numShorts = (if (dataLen >= 2) dataLen / 2 else lastPayloadShorts).coerceAtMost(reusableShorts.size)
                            for (i in 0 until numShorts) reusableShorts[i] = 0
                        } else {
                            val evenLen = dataLen - (dataLen % 2)
                            numShorts = (evenLen / 2).coerceAtMost(reusableShorts.size)
                            val bb = java.nio.ByteBuffer.wrap(packet.data, dataOff, evenLen)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            var i = 0
                            while (bb.remaining() >= 2 && i < numShorts) reusableShorts[i++] = bb.short
                            lastPayloadShorts = numShorts
                        }
                        if (numShorts <= 0) continue

                        when (routingMode) {
                            MicRoutingMode.VIRTUAL_MIC -> {
                                when {
                                    nativeVirtualSinkActive -> activeVirtualSinkEngine?.writeToVirtualSink(reusableShorts, numShorts)
                                    wasapiSinkEngine != null -> wasapiSinkEngine!!.micSinkWrite(reusableShorts, numShorts)
                                    line != null -> {
                                        val outBytes = numShorts * 2
                                        java.nio.ByteBuffer.wrap(lineOutBytes, 0, outBytes)
                                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            .asShortBuffer().put(reusableShorts, 0, numShorts)
                                        line!!.write(lineOutBytes, 0, outBytes)
                                    }
                                    else -> if (totalPackets == 1L) AppDebug.log("[MicReceiver] ERROR: no active mic destination")
                                }
                            }
                            MicRoutingMode.MIX_INTO_STREAM -> {
                                serverEngine?.pushMicPcm(reusableShorts, numShorts)
                                softwareMicMixer.pushPcm(reusableShorts, numShorts)
                            }
                            MicRoutingMode.OFF -> break
                        }
                    } catch (_: java.net.SocketTimeoutException) { continue }
                }
            } finally {
                socket?.close()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) AppDebug.log("[MicReceiver] error: ${e.message}")
        } finally {
            MicStats.off()
            muteCollectorJob?.cancel()
            if (mixActive && localMicMixJob?.isActive != true) {
                serverEngine?.setMicMixEnabled(false)
                serverEngine?.setMicMixVolume(1.0f)
                softwareMicMixer.enable(false)
                softwareMicMixer.volume = 1.0f
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
        var engineEnableJob: Job? = null
        var keepAliveJob: Job? = null

        try {
            val micGain = 1.0f

            softwareMicMixer.enable(true)
            softwareMicMixer.volume = if (isMicMuted.value) 0f else micGain

            engineEnableJob = launch {
                var enabledOn: AudioEngine? = null
                while (isActive) {
                    val cur = serverEngine
                    if (cur != null && cur !== enabledOn) {
                        runCatching { cur.setMicMixEnabled(true) }
                        runCatching { cur.setMicMixVolume(if (isMicMuted.value) 0f else micGain) }
                        enabledOn = cur
                    }
                    delay(200)
                }
            }

            muteCollectorJob = launch {
                isMicMuted.collect { muted ->
                    val v = if (muted) 0f else micGain
                    runCatching { serverEngine?.setMicMixVolume(v) }
                    softwareMicMixer.volume = v
                }
            }

            keepAliveJob = launch {
                runCatching {
                    val fmt = AudioFormat(audioSettings.sampleRate, 16, 2, true, false)
                    val info = DataLine.Info(SourceDataLine::class.java, fmt)
                    if (AudioSystem.isLineSupported(info)) {
                        val line = AudioSystem.getLine(info) as SourceDataLine
                        line.open(fmt, 4096)
                        line.start()
                        val zeros = ByteArray(1024)
                        while (isActive) {
                            line.write(zeros, 0, zeros.size)
                        }
                        line.stop()
                        line.close()
                    }
                }
            }

            val mixer = runCatching { AudioSystem.getMixer(micMixInputInfo) }.getOrNull() ?: return@launch
            val streamSr  = audioSettings.sampleRate
            val streamBd  = 16
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
                    val bufSize = (fmt.sampleRate * fmt.frameSize * 0.1f).toInt()
                    val l = (mixer.getLine(info) as TargetDataLine).apply {
                        open(fmt, bufSize)
                        start()
                    }
                    localMicLine = l
                    chosen = fmt
                }
                if (chosen != null) break
            }
            if (localMicLine == null || chosen == null) return@launch

            val inSr      = chosen!!.sampleRate
            val inCh      = chosen!!.channels
            val needsUpmix   = inCh == 1 && streamCh == 2
            val needsDownmix = inCh == 2 && streamCh == 1
            val needsResample = inSr != streamSr

            val readBuf = ByteArray(16384)
            val maxInShorts = readBuf.size / 2
            val inShortsBuf = ShortArray(maxInShorts)
            val procShortsBuf = ShortArray(maxInShorts * 2)
            val ratio = streamSr.toDouble() / inSr.toDouble()
            val outShortsBuf = ShortArray(((maxInShorts * 2) * (if (needsResample) ratio else 1.0)).toInt() + 8)
            val byteOrder = if (chosen!!.isBigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN

            while (isActive) {
                val n = localMicLine!!.read(readBuf, 0, readBuf.size)
                if (n <= 0) continue

                val numInShorts = n / 2
                val bb = java.nio.ByteBuffer.wrap(readBuf, 0, n).order(byteOrder)
                var idx = 0
                while (bb.remaining() >= 2 && idx < numInShorts) inShortsBuf[idx++] = bb.short

                var procShorts: ShortArray = inShortsBuf
                var procLen = numInShorts
                var procCh = inCh
                if (needsDownmix) {
                    var j = 0
                    var k = 0
                    while (k + 1 < numInShorts) {
                        procShortsBuf[j++] = ((inShortsBuf[k].toInt() + inShortsBuf[k + 1].toInt()) / 2).toShort()
                        k += 2
                    }
                    procShorts = procShortsBuf
                    procLen = j
                    procCh = 1
                } else if (needsUpmix) {
                    var j = 0
                    for (i in 0 until numInShorts) {
                        val s = inShortsBuf[i]
                        procShortsBuf[j++] = s
                        procShortsBuf[j++] = s
                    }
                    procShorts = procShortsBuf
                    procLen = j
                    procCh = 2
                }

                val outLen: Int
                val outShorts: ShortArray
                if (!needsResample) {
                    outShorts = procShorts
                    outLen = procLen
                } else {
                    val inFrames = procLen / procCh
                    var outPos = 0
                    var srcFrame = 0.0
                    val cap = outShortsBuf.size
                    while (srcFrame < inFrames - 1 && outPos + procCh <= cap) {
                        val base = srcFrame.toInt()
                        val frac = (srcFrame - base).toFloat()
                        for (c in 0 until procCh) {
                            val a = procShorts[base * procCh + c].toInt()
                            val b = procShorts[(base + 1) * procCh + c].toInt()
                            val v = (a + (b - a) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            outShortsBuf[outPos++] = v.toShort()
                        }
                        srcFrame += 1.0 / ratio
                    }
                    outShorts = outShortsBuf
                    outLen = outPos
                }

                serverEngine?.pushMicPcm(outShorts, outLen)
                softwareMicMixer.pushPcm(outShorts, outLen)
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            AppDebug.log("[LocalMicMix] error: ${e.message}")
        } finally {
            muteCollectorJob?.cancel()
            engineEnableJob?.cancel()
            keepAliveJob?.cancel()
            runCatching { localMicLine?.stop() }
            runCatching { localMicLine?.close() }
            if (micReceiverJob?.isActive != true) {
                runCatching { serverEngine?.setMicMixEnabled(false) }
                runCatching { serverEngine?.setMicMixVolume(1.0f) }
                softwareMicMixer.enable(false)
                softwareMicMixer.volume = 1.0f
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
            MicStats.begin(MicStats.Dir.SENDING, "${serverInfo.ip}:$micPort")
            val chunkBytes = audioSettings.maxPayloadBytes.coerceIn(256, 1400 - AUDIO_HEADER_SIZE - WfasCrypto.AEAD_OVERHEAD)
            val buf = ByteArray(audioSettings.bufferSize)
            val packetBuffer = ByteArray(AUDIO_HEADER_SIZE + chunkBytes)
            var seq = 0
            var lastMutedSent = false
            fun sendMicPacket(silence: Boolean, src: ByteArray?, off: Int, len: Int) {
                val md = micSendDir
                if (md != null) {
                    val payload = if (!silence && src != null && len > 0) src.copyOfRange(off, off + len) else ByteArray(0)
                    val enc = WfasCrypto.encryptPacket(md, seq, 0, silence, payload)
                    runCatching { socket!!.send(DatagramPacket(enc, enc.size, dest, micPort)) }
                } else {
                    writeMicHeader(packetBuffer, seq, silence)
                    val plen = if (silence) AUDIO_HEADER_SIZE else AUDIO_HEADER_SIZE + len
                    if (!silence && src != null && len > 0) System.arraycopy(src, off, packetBuffer, AUDIO_HEADER_SIZE, len)
                    runCatching { socket!!.send(DatagramPacket(packetBuffer, plen, dest, micPort)) }
                }
                seq = (seq + 1) and 0xFFFF
            }
            while (isActive) {
                if (isMicMuted.value) {
                    if (!lastMutedSent) {
                        sendMicPacket(true, null, 0, 0)
                        MicStats.addSilence(AUDIO_HEADER_SIZE)
                        lastMutedSent = true
                    }
                    line.read(buf, 0, buf.size)
                    continue
                }
                lastMutedSent = false
                val n = line.read(buf, 0, buf.size)
                if (n > 0) {
                    var offset = 0
                    while (offset < n) {
                        val remaining = n - offset
                        var chunk = if (remaining > chunkBytes) chunkBytes else remaining
                        chunk -= chunk % 2
                        if (chunk <= 0) break
                        sendMicPacket(false, buf, offset, chunk)
                        MicStats.addAudio(AUDIO_HEADER_SIZE + chunk)
                        offset += chunk
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) AppDebug.log("Mic sender error: ${e.message}")
        } finally {
            MicStats.off()
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
        val isLinux = !os.contains("win") && !os.contains("mac")

        if (isLinux) routeLinuxAudioToVirtualCable()

        if (isLinux) {
            // Prova prima pulse:VirtualCable.monitor — bypassa il plugin ALSA di PulseAudio
            // e permette di impostare fragment_size direttamente su PA, riducendo la latenza
            // da ~1s (alsa:default via plugin ALSA) a ~10ms.
            val fragSize = (audioSettings.sampleRate.toInt() * audioSettings.channels * 2 * 5 / 1000)
                .coerceAtLeast(256)
            try {
                return org.bytedeco.javacv.FFmpegFrameGrabber("VirtualCable.monitor").apply {
                    setFormat("pulse")
                    setOption("probesize",         "32")
                    setOption("analyzeduration",   "0")
                    setOption("fflags",            "nobuffer")
                    setOption("thread_queue_size", "4096")
                    setOption("fragment_size",     fragSize.toString())
                    sampleRate    = audioSettings.sampleRate.toInt()
                    audioChannels = audioSettings.channels
                    sampleFormat  = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                    start()
                }
            } catch (e: Exception) {
                println("=== FFmpeg pulse grabber failed (${e.message}), falling back to alsa ===")
            }
        }

        val (grabberFormat, deviceName) = when {
            os.contains("win") -> "dshow"        to "audio=CABLE Output (VB-Audio Virtual Cable)"
            os.contains("mac") -> "avfoundation" to ":BlackHole 2ch"
            else               -> "alsa"         to "default"
        }
        return try {
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
        onPcm: ((ShortArray) -> Unit)? = null,
        onChunk: suspend (bytesToSend: Int) -> Unit
    ) {
        if (frame.samples == null) return
        val shortBuffer = frame.samples[0] as java.nio.ShortBuffer
        shortBuffer.position(0)
        while (shortBuffer.hasRemaining()) {
            val shortsToRead = minOf(shortBuffer.remaining(), maxShortsPerPacket)
            shortBuffer.get(chunkArray, 0, shortsToRead)
            softwareMicMixer.mixInto(chunkArray, shortsToRead)
            val vol = currentServerVolume
            if (vol != 1.0f) {
                for (i in 0 until shortsToRead) {
                    chunkArray[i] = (chunkArray[i] * vol).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }
            onPcm?.invoke(chunkArray.copyOf(shortsToRead))
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
                AppDebug.log("---HTTP server on port $httpPort (safariMode=$safariMode) ---")
                while (isActive) {
                    val sock = try { serverSocket.accept() }
                    catch (_: java.net.SocketTimeoutException) { continue }
                    catch (e: Exception) {
                        if (e !is CancellationException && !serverSocket.isClosed)
                            AppDebug.log("HTTP accept: ${e.message}")
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
                                    AppDebug.log("---/stream/aac client: ${sock.inetAddress} ---")
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
                                    AppDebug.log("---/stream/opus WS client: ${sock.inetAddress} ---")
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
                            if (e !is CancellationException) AppDebug.log("HTTP handler: ${e.message}")
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
            ) ?: run { AppDebug.log("AAC codec not found"); return@launch }

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
                if (e !is CancellationException) AppDebug.log("AAC encoder error: ${e.message}")
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
                        AppDebug.log("Opus pipe reader error: ${e.message}")
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
                        if (e !is CancellationException) AppDebug.log("Opus record error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) AppDebug.log("Opus encoder error: ${e.message}")
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

        AppDebug.log("---RTP sidecar started on port $port (multicast=$isMulticast) ---")
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
            if (e !is CancellationException) AppDebug.log("RTP sidecar error: ${e.message}")
        } finally {
            socket.close()
            rtpPcmQueue = null
            AppDebug.log("---RTP sidecar stopped ---")
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
        onAudioFrame: ((ShortArray) -> Unit)? = null,
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
                    useNativeEngine, micMixInputInfo, onAudioFrame, onStatusUpdate
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
        onAudioFrame: ((ShortArray) -> Unit)? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        val annCaps = capabilities.copy(serverWantsMic = micRoutingMode != MicRoutingMode.OFF)
        startDonationTimer()
        if (micRoutingMode != MicRoutingMode.OFF) {
            micReceiverJob = scope.launchMicReceiver(
                audioSettings, isMulticast, micRoutingMode, micOutputMixerInfo, micPort, micMixInputInfo, onStatusUpdate
            )
        }

        val isMacOS = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
        if (isMacOS && AudioEngine.loadLibrary()) {
            val helperEngine = AudioEngine()
            val vol = helperEngine.getSystemVolume()
            if (vol >= 0f) {
                macOriginalVolume = vol
                helperEngine.setSystemVolume(0f)
                AppDebug.log("[Server] macOS: volume saved ($vol), lowered to 0")
            }
        }

        if (micRoutingMode == MicRoutingMode.MIX_INTO_STREAM && micMixInputInfo != null) {
            localMicMixJob = scope.launchLocalMicMix(audioSettings, micMixInputInfo)
        }

        if (StreamingProtocol.HTTP in capabilities.protocols && capabilities.httpPort != null) {
            httpServerJob = scope.launchHttpServer(audioSettings, capabilities.httpPort, capabilities.safariMode, onStatusUpdate)
        }

        if (StreamingProtocol.RTP in capabilities.protocols) {
            rtpJob = scope.launchRtpSidecar(audioSettings, rtpPort, isMulticast)
        }

        startAnnouncingPresence(isMulticast, port, annCaps, audioSettings)

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
                    val mcEncrypting = encryptionEnabled && SecurityMode.fromStringSafe(securityMode) == SecurityMode.KEY
                    val mcDir: WfasCrypto.Dir?
                    val mcBeaconBytes: ByteArray?
                    if (mcEncrypting) {
                        val salt = ByteArray(WfasCrypto.SALT_BYTES).also { java.security.SecureRandom().nextBytes(it) }
                        mcDir = WfasCrypto.deriveMulticast(authKey, salt)
                        val beacon = WfasCrypto.buildMcastBeacon(authKey, SettingsRepository.nextMcastEpoch(), System.currentTimeMillis() / 1000, salt)
                        mcBeaconBytes = beacon.toByteArray(Charsets.US_ASCII)
                    } else { mcDir = null; mcBeaconBytes = null }
                    val safeMtuSize        = 1400
                    var maxShortsPerPacket = (audioSettings.maxPayloadBytes.coerceIn(256, safeMtuSize - AUDIO_HEADER_SIZE)) / 2
                    maxShortsPerPacket    -= (maxShortsPerPacket % nCh)
                    if (mcDir != null) {
                        maxShortsPerPacket -= (WfasCrypto.AEAD_OVERHEAD + 1) / 2
                        maxShortsPerPacket -= (maxShortsPerPacket % nCh)
                        if (maxShortsPerPacket < nCh) maxShortsPerPacket = nCh
                    }
                    val exactPayloadBytes  = AUDIO_HEADER_SIZE + (maxShortsPerPacket * 2)
                    val chunkArray         = ShortArray(maxShortsPerPacket)
                    val byteBuffer         = java.nio.ByteBuffer.allocate(
                        if (useNativeEngine) exactPayloadBytes else maxShortsPerPacket * 2
                    ).apply { if (!useNativeEngine) order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                    val packetArray        = ByteArray(exactPayloadBytes)
                    audioSeqNum    = 0
                    audioSamplePos = 0L
                    var legacySeq  = 0
                    fun frameForSend(buf: ByteArray, len: Int): ByteArray {
                        val dir = mcDir ?: return if (len == buf.size) buf else buf.copyOf(len)
                        val silence = (buf[3].toInt() and 0x01) != 0
                        val seq = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                        val pos = ((buf[6].toLong() and 0xFF) shl 24) or ((buf[7].toLong() and 0xFF) shl 16) or
                                  ((buf[8].toLong() and 0xFF) shl 8) or (buf[9].toLong() and 0xFF)
                        return WfasCrypto.encryptPacket(dir, seq, pos, silence, buf.copyOfRange(AUDIO_HEADER_SIZE, len))
                    }

                    try {
                        MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                            socket.reuseAddress = true
                            socket.bind(java.net.InetSocketAddress(0))
                            socket.timeToLive = 4
                            socket.loopbackMode = true
                            getActiveNetworkInterface()?.let { socket.networkInterface = it }
                            val group = InetAddress.getByName(MULTICAST_GROUP_IP)
                            var lastBeacon = 0L

                            while (isActive) {
                                if (mcBeaconBytes != null && System.currentTimeMillis() - lastBeacon >= 400L) {
                                    runCatching { socket.send(DatagramPacket(mcBeaconBytes, mcBeaconBytes.size, group, port)) }
                                    lastBeacon = System.currentTimeMillis()
                                }
                                if (useNativeEngine) {
                                    val engine  = serverEngine ?: break
                                    val samples = engine.readFrame() ?: break
                                    if (samples.isEmpty()) continue
                                    onAudioFrame?.invoke(samples)

                                    processEngineFrame(samples, chunkArray, byteBuffer, maxShortsPerPacket, nCh) { bytesToSend ->
                                        byteBuffer.array().copyInto(packetArray, 0, 0, bytesToSend)
                                        val ob = frameForSend(packetArray, bytesToSend)
                                        runCatching { socket.send(DatagramPacket(ob, ob.size, group, port)) }
                                        WfasStats.add(if ((packetArray[3].toInt() and 0x01) != 0) WfasStats.Cat.SILENCE else WfasStats.Cat.AUDIO, bytesToSend)
                                        if (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null)
                                            distributeToSidecars(byteBuffer.array().copyOfRange(AUDIO_HEADER_SIZE, bytesToSend))
                                    }
                                } else {
                                    val grabber = serverGrabber ?: break
                                    val frame = try { grabber.grabSamples() }
                                    catch (e: org.bytedeco.javacv.FFmpegFrameGrabber.Exception) {
                                        AppDebug.log("Grabber exception (multicast): ${e.message}")
                                        break
                                    }
                                    if (frame != null) {
                                        processGrabberFrame(frame, chunkArray, byteBuffer, maxShortsPerPacket, onAudioFrame) { bytesToSend ->
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
                                            val ob = frameForSend(packetArray, totalBytes)
                                            runCatching { socket.send(DatagramPacket(ob, ob.size, group, port)) }
                                            WfasStats.add(WfasStats.Cat.AUDIO, totalBytes)
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
                                        WfasStats.add(WfasStats.Cat.BYE, bye.size)
                                    }
                                    AppDebug.log("---Sent BYE to multicast group ---")
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
                    aSocket(selectorManager).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        var pendCnonce = ""
                        var pendSnonce = ""
                        while (isActive) {
                            startAnnouncingPresence(isMulticast = false, port = port, capabilities = annCaps, audioSettings = audioSettings)
                            onStatusUpdate("Waiting for Unicast Client on Port %d...", arrayOf(port))

                            val clientDatagram = socket.receive()
                            val msg = clientDatagram.packet.readText().trim()
                            val clientAddress = clientDatagram.address

                            if (msg == "MODE_PROBE") {
                                socket.send(Datagram(buildPacket { writeText("UNICAST") }, clientAddress))
                                WfasStats.add(WfasStats.Cat.PROBE, 7)
                                continue
                            }

                            if (!msg.startsWith(CLIENT_HELLO_MESSAGE)) continue

                            val clientVersion = parseProtocolVersion(msg)
                            if (clientVersion != WFAS_PROTOCOL_VERSION) {
                                AppDebug.log("[SERVER][UNICAST] incompatible client v=$clientVersion (mine v=$WFAS_PROTOCOL_VERSION), rejecting $clientAddress")
                                socket.send(Datagram(buildPacket { writeText(incompatibleMessage()) }, clientAddress))
                                WfasStats.add(WfasStats.Cat.HELLO, incompatibleMessage().length)
                                signalProtocolMismatch(clientVersion)
                                continue
                            }

                            when (SecurityMode.fromStringSafe(securityMode)) {
                                SecurityMode.KEY -> {
                                    val cproof = WfasAuth.getToken(msg, "cproof")
                                    val cnonce = WfasAuth.getToken(msg, "cnonce") ?: ""
                                    if (cproof == null) {
                                        pendCnonce = cnonce
                                        pendSnonce = WfasAuth.nonceHex()
                                        val sproof = WfasAuth.proof(authKey, 'S', pendCnonce, pendSnonce)
                                        socket.send(Datagram(buildPacket { writeText("$AUTH_REQUIRED_PREFIX;snonce=$pendSnonce;sproof=$sproof") }, clientAddress))
                                        continue
                                    }
                                    val expected = WfasAuth.proof(authKey, 'C', pendCnonce.ifEmpty { cnonce }, pendSnonce)
                                    if (!WfasAuth.constantTimeEquals(cproof, expected)) {
                                        AppDebug.log("[SERVER][UNICAST] auth failed for $clientAddress")
                                        socket.send(Datagram(buildPacket { writeText(UNAUTHORIZED_MESSAGE) }, clientAddress))
                                        continue
                                    }
                                    AppDebug.log("[SERVER][UNICAST] auth OK for $clientAddress")
                                }
                                SecurityMode.ASK -> {
                                    socket.send(Datagram(buildPacket { writeText(PENDING_MESSAGE) }, clientAddress))
                                    val allow = onAuthRequest?.invoke(clientAddress.toString()) ?: true
                                    if (!allow) {
                                        socket.send(Datagram(buildPacket { writeText(UNAUTHORIZED_MESSAGE) }, clientAddress))
                                        continue
                                    }
                                }
                                SecurityMode.OFF -> { }
                            }

                            val encrypting = encryptionEnabled &&
                                SecurityMode.fromStringSafe(securityMode) == SecurityMode.KEY
                            val sessionKeys = if (encrypting)
                                WfasCrypto.deriveUnicast(authKey, pendCnonce, pendSnonce) else null
                            val sendDir: WfasCrypto.Dir? = sessionKeys?.second
                            micRecvDir = sessionKeys?.first
                            if (encrypting) micRecvWin = WfasCrypto.ReplayWindow()
                            fun frameForSend(buf: ByteArray, len: Int): ByteArray {
                                val dir = sendDir ?: return if (len == buf.size) buf else buf.copyOf(len)
                                val silence = (buf[3].toInt() and 0x01) != 0
                                val seq = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                                val pos = ((buf[6].toLong() and 0xFF) shl 24) or ((buf[7].toLong() and 0xFF) shl 16) or
                                          ((buf[8].toLong() and 0xFF) shl 8) or (buf[9].toLong() and 0xFF)
                                val pcm = buf.copyOfRange(AUDIO_HEADER_SIZE, len)
                                return WfasCrypto.encryptPacket(dir, seq, pos, silence, pcm)
                            }

                            onStatusUpdate("Client Connected: %s", arrayOf(clientAddress.toString()))
                            stopAnnouncingPresence()

                            val ackText = helloAckMessage() + if (encrypting) ";enc=1" else ""
                            socket.send(Datagram(buildPacket { writeText(ackText) }, clientAddress))
                            WfasStats.add(WfasStats.Cat.HELLO, ackText.length)

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

                            val clientAlive = java.util.concurrent.atomic.AtomicBoolean(true)

                            val pingJob = launch {
                                var failures = 0
                                while (isActive && clientAlive.get()) {
                                    delay(1000)
                                    try {
                                        socket.send(Datagram(buildPacket { writeText("PING") }, clientAddress))
                                        WfasStats.add(WfasStats.Cat.PING, 4)
                                        failures = 0
                                    } catch (_: Exception) {
                                        if (++failures >= 3) {
                                            AppDebug.log("---PING failed 3 times, client considered gone ---")
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
                                        // Solo il client collegato pilota questa sessione:
                                        // senza il controllo sull'indirizzo, un CLIENT_BYE
                                        // di un terzo dispositivo chiuderebbe la connessione
                                        // altrui. Ai terzi si risponde "occupato".
                                        if (datagram.address != clientAddress) {
                                            if (msg.startsWith(CLIENT_HELLO_MESSAGE) || msg == "MODE_PROBE") {
                                                AppDebug.log("[SERVER][UNICAST] ${datagram.address} rejected: busy with $clientAddress")
                                                runCatching {
                                                    socket.send(Datagram(
                                                        buildPacket { writeText(BUSY_MESSAGE) },
                                                        datagram.address
                                                    ))
                                                }
                                            }
                                            continue
                                        }
                                        if (msg == "CLIENT_BYE") {
                                            AppDebug.log("---Received CLIENT_BYE from $clientAddress ---")
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
                                    var maxShortsPerPacket = (audioSettings.maxPayloadBytes.coerceIn(256, safeMtuSize - AUDIO_HEADER_SIZE)) / 2
                                    maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                    if (sendDir != null) {
                                        maxShortsPerPacket -= (WfasCrypto.AEAD_OVERHEAD + 1) / 2
                                        maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                        if (maxShortsPerPacket < audioSettings.channels) maxShortsPerPacket = audioSettings.channels
                                    }
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
                                        onAudioFrame?.invoke(samples)
                                        processEngineFrame(samples, chunkArray, byteBuffer, maxShortsPerPacket, audioSettings.channels) { bytesToSend ->
                                            byteBuffer.array().copyInto(packetArray, 0, 0, bytesToSend)
                                            val outBytes = frameForSend(packetArray, bytesToSend)
                                            val packet = buildPacket { writeFully(outBytes, 0, outBytes.size) }
                                            try {
                                                socket.send(Datagram(packet, clientAddress))
                                                WfasStats.add(if ((packetArray[3].toInt() and 0x01) != 0) WfasStats.Cat.SILENCE else WfasStats.Cat.AUDIO, bytesToSend)
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
                                    var maxShortsPerPacket = (audioSettings.maxPayloadBytes.coerceIn(256, safeMtuSize - AUDIO_HEADER_SIZE)) / 2
                                    maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                    if (sendDir != null) {
                                        maxShortsPerPacket -= (WfasCrypto.AEAD_OVERHEAD + 1) / 2
                                        maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                        if (maxShortsPerPacket < audioSettings.channels) maxShortsPerPacket = audioSettings.channels
                                    }
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
                                            AppDebug.log("Grabber exception (unicast): ${e.message}")
                                            break
                                        }
                                        if (!clientAlive.get()) break
                                        if (frame != null) {
                                            processGrabberFrame(frame, chunkArray, byteBuffer, maxShortsPerPacket, onAudioFrame) { bytesToSend ->
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
                                                val outBytes = frameForSend(packetArray, totalBytes)
                                                val packet = buildPacket { writeFully(outBytes, 0, outBytes.size) }
                                                try {
                                                    socket.send(Datagram(packet, clientAddress))
                                                    WfasStats.add(WfasStats.Cat.AUDIO, totalBytes)
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
                                            WfasStats.add(WfasStats.Cat.BYE, 3)
                                            AppDebug.log("---Sent BYE to $clientAddress ---")
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
        onAudioFrame: ((ShortArray) -> Unit)? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        startDonationTimer()
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
                    AppDebug.log("Cannot open browser: ${e.message}")
                    onStatusUpdate("status_error_client", arrayOf(e.message ?: "browser unavailable"))
                }
            return
        }

        micSendDir = null   // mic stays plaintext until the handshake derives the session key
        if (sendMicrophone && micInputMixerInfo != null)
            micReceiverJob = scope.launchMicSender(audioSettings, serverInfo, micInputMixerInfo, micPort)

        streamingJob = scope.launch {
            var sourceDataLine: SourceDataLine? = null
            var player: JitterAudioPlayer? = null
            val playbackState = ClientPlaybackState()
            AppDebug.log("[CLIENT] launchClientInstance started: server=${serverInfo.ip}:${serverInfo.port} isMulticast=${serverInfo.isMulticast} sendMic=$sendMicrophone mixer='${selectedMixerInfo.name}'")

            // I byte li manda il server: comanda il suo formato, le impostazioni
            // locali valgono solo se il beacon non lo annuncia. Un formato che non
            // sappiamo riprodurre va rifiutato: reinterpretarlo produce solo rumore.
            val advertised = serverInfo.serverAudioSettings
            if (advertised != null && !isPlayableFormat(advertised)) {
                AppDebug.log("[CLIENT] Unsupported stream format: ${describeFormat(advertised)} - refusing to play")
                onStatusUpdate("status_unsupported_format", arrayOf(describeFormat(advertised)))
                return@launch
            }
            val playbackSettings = advertised ?: audioSettings
            if (advertised != null) {
                AppDebug.log("[CLIENT] Using server-advertised format: ${describeFormat(advertised)}")
            }

            try {
                if (!serverInfo.isMulticast) { // Unicast
                    val remoteAddress = InetSocketAddress(serverInfo.ip, serverInfo.port)
                    AppDebug.log("[CLIENT][UNICAST] unicast connection to $remoteAddress")
                    aSocket(selectorManager).udp().bind().use { socket ->
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, playbackSettings)
                        if (sourceDataLine == null) {
                            AppDebug.log("[CLIENT][UNICAST] Output device rejected ${describeFormat(playbackSettings)}")
                            onStatusUpdate("status_unsupported_format", arrayOf(describeFormat(playbackSettings)))
                            return@launch
                        }
                        player = sourceDataLine?.let {
                            JitterAudioPlayer(it, playbackSettings.sampleRate.toInt(), playbackSettings.channels,
                                prebufferMs = audioSettings.latencyMs, maxBufferMs = audioSettings.latencyMs + 280).also { p -> p.start() }
                        }
                        AppDebug.log("[CLIENT][UNICAST] SourceDataLine started, sending HELLO to $remoteAddress")

                        onStatusUpdate("status_contacting_server", arrayOf(remoteAddress))
                        val cnonce = WfasAuth.nonceHex()
                        var helloMsg = "${clientHelloMessage()};cnonce=$cnonce"
                        var proved = false
                        var clientSnonce = ""
                        var clientKey = clientPresharedKey
                        var sessionEncrypted = false
                        socket.send(Datagram(buildPacket { writeText(helloMsg) }, remoteAddress))
                        AppDebug.log("[CLIENT][UNICAST] HELLO sent, waiting for reply...")
                        onStatusUpdate("status_waiting_ack", emptyArray())

                        var handshakeDeadline = System.currentTimeMillis() + 30000
                        var handshakeOk = false

                        suspend fun promptKeyAndRestart(wrong: Boolean): Boolean {
                            val k = onKeyRequest?.invoke(wrong) ?: return false
                            if (k.isBlank()) return false
                            clientKey = k
                            proved = false
                            helloMsg = "${clientHelloMessage()};cnonce=$cnonce"
                            socket.send(Datagram(buildPacket { writeText(helloMsg) }, remoteAddress))
                            handshakeDeadline = System.currentTimeMillis() + 30000
                            return true
                        }

                        while (System.currentTimeMillis() < handshakeDeadline) {
                            val ackText = try {
                                withTimeout(2000) { socket.receive() }.packet.readText().trim()
                            } catch (_: TimeoutCancellationException) {
                                socket.send(Datagram(buildPacket { writeText(helloMsg) }, remoteAddress))
                                continue
                            }
                            AppDebug.log("[CLIENT][UNICAST] reply: '$ackText'")
                            when {
                                ackText.startsWith(INCOMPATIBLE_PREFIX) -> {
                                    signalProtocolMismatch(parseProtocolVersion(ackText))
                                    onStatusUpdate("status_protocol_incompatible", emptyArray())
                                    return@use
                                }
                                ackText == UNAUTHORIZED_MESSAGE -> {
                                    AppDebug.log("[CLIENT][UNICAST] unauthorized")
                                    if (!promptKeyAndRestart(true)) {
                                        onStatusUpdate("status_unauthorized", emptyArray())
                                        return@use
                                    }
                                }
                                ackText == PENDING_MESSAGE -> {
                                    onStatusUpdate("status_awaiting_approval", emptyArray())
                                }
                                ackText.startsWith(AUTH_REQUIRED_PREFIX) -> {
                                    if (clientKey.isEmpty()) {
                                        AppDebug.log("[CLIENT][UNICAST] server requires a key")
                                        val k = onKeyRequest?.invoke(false)
                                        if (k.isNullOrBlank()) {
                                            onStatusUpdate("status_key_required", emptyArray())
                                            return@use
                                        }
                                        clientKey = k
                                        handshakeDeadline = System.currentTimeMillis() + 30000
                                    }
                                    val snonce = WfasAuth.getToken(ackText, "snonce") ?: ""
                                    clientSnonce = snonce
                                    val sproof = WfasAuth.getToken(ackText, "sproof") ?: ""
                                    if (!WfasAuth.constantTimeEquals(sproof, WfasAuth.proof(clientKey, 'S', cnonce, snonce))) {
                                        AppDebug.log("[CLIENT][UNICAST] server proof invalid (rogue server or wrong key)")
                                        if (!promptKeyAndRestart(true)) {
                                            onStatusUpdate("status_unauthorized", emptyArray())
                                            return@use
                                        }
                                    } else {
                                        val cproof = WfasAuth.proof(clientKey, 'C', cnonce, snonce)
                                        helloMsg = "${clientHelloMessage()};cnonce=$cnonce;cproof=$cproof"
                                        proved = true
                                        socket.send(Datagram(buildPacket { writeText(helloMsg) }, remoteAddress))
                                    }
                                }
                                ackText == BUSY_MESSAGE -> {
                                    AppDebug.log("[CLIENT] server busy with another device")
                                    onStatusUpdate("status_server_busy", emptyArray())
                                    return@launch
                                }
                                ackText.startsWith(HELLO_ACK_PREFIX) -> {
                                    val serverVersion = parseProtocolVersion(ackText)
                                    if (serverVersion != WFAS_PROTOCOL_VERSION) {
                                        signalProtocolMismatch(serverVersion)
                                        onStatusUpdate("status_protocol_incompatible", emptyArray())
                                        return@use
                                    }
                                    if (clientKey.isNotEmpty() && !proved) {
                                        AppDebug.log("[CLIENT][UNICAST] server skipped auth (possible downgrade) — aborting")
                                        onStatusUpdate("status_unauthorized", emptyArray())
                                        return@use
                                    }
                                    if (WfasAuth.getToken(ackText, "enc") == "1") sessionEncrypted = true
                                    handshakeOk = true
                                }
                                else -> AppDebug.log("[CLIENT][UNICAST] unexpected reply '$ackText'")
                            }
                            if (handshakeOk) break
                        }
                        if (!handshakeOk) {
                            onStatusUpdate("status_handshake_failed", emptyArray())
                            return@use
                        }
                        AppDebug.log("[CLIENT][UNICAST] handshake OK, streaming started from $remoteAddress")
                        onStatusUpdate("status_connected_streaming_from", arrayOf(remoteAddress))
                        if (connectionSoundEnabled) playConnectionSound()

                        val sessionKeys = if (clientKey.isNotEmpty() && proved)
                            WfasCrypto.deriveUnicast(clientKey, cnonce, clientSnonce) else null
                        val recvDir: WfasCrypto.Dir? = sessionKeys?.second
                        micSendDir = if (sessionEncrypted) sessionKeys?.first else null
                        val recvWin = WfasCrypto.ReplayWindow()
                        var serverEncrypts = sessionEncrypted

                        var lastPingReceived = System.currentTimeMillis()
                        val pingTimeoutMs    = 3000L
                        val serverAlive      = java.util.concurrent.atomic.AtomicBoolean(true)
                        var receivedPackets  = 0L
                        var audioPackets     = 0L
                        var totalAudioBytes  = 0L
                        var pingCount        = 0L
                        var versionChecked   = false

                        val watchdogJob = launch {
                            while (isActive && serverAlive.get()) {
                                delay(1000)
                                val elapsed = System.currentTimeMillis() - lastPingReceived
                                AppDebug.log("[CLIENT][UNICAST] watchdog: ${elapsed}ms since last PING (timeout=${pingTimeoutMs}ms) audioPkts=$audioPackets totalAudioBytes=$totalAudioBytes")
                                if (elapsed > pingTimeoutMs) {
                                    AppDebug.log("---Server timeout: no PING for ${pingTimeoutMs}ms ---")
                                    AppDebug.log("[CLIENT][UNICAST] TIMEOUT: no PING in ${pingTimeoutMs}ms, disconnecting")
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
                                receivedPackets++

                                if (bytes.size >= AUDIO_HEADER_SIZE && bytes[0] == AUDIO_MAGIC_0 && bytes[1] == AUDIO_MAGIC_1) {
                                    if (!versionChecked) {
                                        versionChecked = true
                                        val packetVersion = bytes[2].toInt() and 0xFF
                                        if (packetVersion != WFAS_PROTOCOL_VERSION) {
                                            AppDebug.log("[CLIENT][UNICAST] packet v=$packetVersion incompatible (mine v=$WFAS_PROTOCOL_VERSION)")
                                            signalProtocolMismatch(packetVersion)
                                            onStatusUpdate("status_protocol_incompatible", emptyArray())
                                            serverAlive.set(false)
                                            break
                                        }
                                    }
                                    WfasStats.add(if ((bytes[3].toInt() and 0x01) != 0) WfasStats.Cat.SILENCE else WfasStats.Cat.AUDIO, bytes.size)
                                    val pcmLen = bytes.size - AUDIO_HEADER_SIZE
                                    audioPackets++
                                    totalAudioBytes += pcmLen
                                    if (audioPackets == 1L || audioPackets % 500L == 0L) {
                                        AppDebug.log("[CLIENT][UNICAST] audioPkt #$audioPackets size=${bytes.size} pcmLen=$pcmLen totalAudioBytes=$totalAudioBytes SDL=${sourceDataLine?.isActive}")
                                    }
                                    val p = player
                                    if (p != null) {
                                        val encFlag = (bytes[3].toInt() and WfasCrypto.FLAG_ENCRYPTED) != 0
                                        if (encFlag) {
                                            if (recvDir != null) {
                                                val r = WfasCrypto.decryptPacket(recvDir, recvWin, bytes, bytes.size)
                                                if (r is WfasCrypto.Decrypted.Ok) {
                                                    serverEncrypts = true
                                                    val plain = ByteArray(AUDIO_HEADER_SIZE + r.pcm.size)
                                                    System.arraycopy(bytes, 0, plain, 0, AUDIO_HEADER_SIZE)
                                                    System.arraycopy(r.pcm, 0, plain, AUDIO_HEADER_SIZE, r.pcm.size)
                                                    handleAudioPacket(p, plain, plain.size, audioSettings.channels, playbackState, onAudioFrame)
                                                }
                                                // replay/auth-fail/malformed -> drop
                                            }
                                            // encrypted but no key -> drop
                                        } else if (!serverEncrypts) {
                                            handleAudioPacket(p, bytes, bytes.size, audioSettings.channels, playbackState, onAudioFrame)
                                        }
                                        // cleartext after encryption seen -> drop (downgrade)
                                    }
                                } else {
                                    val text = bytes.toString(Charsets.UTF_8).trim()
                                    WfasStats.add(
                                        when (text) {
                                            "PING" -> WfasStats.Cat.PING
                                            "BYE"  -> WfasStats.Cat.BYE
                                            else   -> WfasStats.Cat.OTHER
                                        },
                                        bytes.size
                                    )
                                    when (text) {
                                        "PING" -> {
                                            lastPingReceived = System.currentTimeMillis()
                                            pingCount++
                                            if (pingCount == 1L || pingCount % 10L == 0L) {
                                                AppDebug.log("[CLIENT][UNICAST] PING #$pingCount received from ${datagram.address}")
                                            }
                                        }
                                        "BYE"  -> {
                                            AppDebug.log("---Received BYE from server ---")
                                            AppDebug.log("[CLIENT][UNICAST] BYE received, clean disconnect")
                                            onStatusUpdate("status_server_disconnected", emptyArray())
                                            if (disconnectionSoundEnabled) playDisconnectionSound()
                                            serverAlive.set(false)
                                        }
                                        else -> AppDebug.log("[CLIENT][UNICAST] unknown msg (#$receivedPackets): '$text'")
                                    }
                                }
                            }
                        } finally {
                            AppDebug.log("[CLIENT][UNICAST] loop ended: receivedPackets=$receivedPackets audioPackets=$audioPackets pingCount=$pingCount totalAudioBytes=$totalAudioBytes")
                            watchdogJob.cancel()
                            withContext(NonCancellable) {
                                runCatching {
                                    AppDebug.log("[CLIENT][UNICAST] sending CLIENT_BYE to $remoteAddress")
                                    socket.send(Datagram(buildPacket { writeText("CLIENT_BYE") }, remoteAddress))
                                }
                            }
                        }
                    }
                } else { // Multicast
                    AppDebug.log("[CLIENT][MULTICAST] multicast mode, port=${serverInfo.port} ip=${serverInfo.ip}")
                    onStatusUpdate("status_joining_multicast", arrayOf(serverInfo.port))
                    val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                    MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                        socket.reuseAddress = true
                        socket.bind(java.net.InetSocketAddress(serverInfo.port))
                        val netIface = getActiveNetworkInterface()
                        if (netIface != null) {
                            AppDebug.log("[CLIENT][MULTICAST] join group $groupAddress via iface ${netIface.name}")
                            socket.joinGroup(java.net.InetSocketAddress(groupAddress, 0), netIface)
                        } else {
                            AppDebug.log("[CLIENT][MULTICAST] join group $groupAddress (no specific iface)")
                            socket.joinGroup(groupAddress)
                        }
                        val effectiveAudioSettings = playbackSettings
                        AppDebug.log("[CLIENT][MULTICAST] effectiveAudioSettings: sr=${effectiveAudioSettings.sampleRate} ch=${effectiveAudioSettings.channels} bd=${effectiveAudioSettings.bitDepth}")
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, effectiveAudioSettings)
                        if (sourceDataLine == null) {
                            AppDebug.log("[CLIENT][MULTICAST] Output device rejected ${describeFormat(effectiveAudioSettings)}")
                            onStatusUpdate("status_unsupported_format", arrayOf(describeFormat(effectiveAudioSettings)))
                            return@launch
                        }
                        player = sourceDataLine?.let {
                            JitterAudioPlayer(it, effectiveAudioSettings.sampleRate.toInt(), effectiveAudioSettings.channels,
                                prebufferMs = audioSettings.latencyMs, maxBufferMs = audioSettings.latencyMs + 280).also { p -> p.start() }
                        }
                        AppDebug.log("[CLIENT][MULTICAST] SourceDataLine started, waiting for multicast packets...")
                        onStatusUpdate("status_multicast_streaming", arrayOf(serverInfo.port))
                        if (connectionSoundEnabled) playConnectionSound()
                        socket.soTimeout = 2000
                        val buf    = ByteArray(8192)
                        val packet = DatagramPacket(buf, buf.size)
                        val frameSize = effectiveAudioSettings.channels * (effectiveAudioSettings.bitDepth / 8)
                        var mcAudioPkts = 0L
                        var mcTotalBytes = 0L
                        var mcVersionChecked = false
                        var mcDir: WfasCrypto.Dir? = null
                        var mcWin = WfasCrypto.ReplayWindow()
                        var mcKey = clientPresharedKey
                        var mcAsked = false
                        var mcWrong = false
                        var mcLastEpoch = SettingsRepository.getMcastClientEpoch(serverInfo.ip)
                        val beaconPrefixLen = WfasCrypto.MSG_MCAST_ENC.length
                        while (isActive) {
                            try {
                                packet.length = buf.size
                                socket.receive(packet)
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            }
                            if (packet.length >= beaconPrefixLen &&
                                String(packet.data, 0, beaconPrefixLen, Charsets.US_ASCII) == WfasCrypto.MSG_MCAST_ENC) {
                                val beaconStr = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                                if (mcDir == null) {
                                    if (mcKey.isEmpty()) {
                                        if (mcAsked && onKeyRequest == null) continue
                                        mcAsked = true
                                        mcKey = onKeyRequest?.invoke(mcWrong)?.takeIf { it.isNotBlank() } ?: ""
                                        if (mcKey.isEmpty()) { onStatusUpdate("status_key_required", emptyArray()); continue }
                                    }
                                    val info = WfasCrypto.parseMcastBeacon(mcKey, beaconStr, -1L)
                                    if (info != null) {
                                        if (info.epoch >= mcLastEpoch) {
                                            mcDir = WfasCrypto.deriveMulticast(mcKey, info.salt)
                                            mcWin = WfasCrypto.ReplayWindow()
                                            if (info.epoch > mcLastEpoch) {
                                                mcLastEpoch = info.epoch
                                                SettingsRepository.setMcastClientEpoch(serverInfo.ip, info.epoch)
                                            }
                                            onStatusUpdate("status_key_accepted", emptyArray())
                                        }
                                    } else {
                                        mcWrong = true
                                        mcKey = ""
                                    }
                                } else {
                                    val info = WfasCrypto.parseMcastBeacon(mcKey, beaconStr, -1L)
                                    if (info != null && info.epoch > mcLastEpoch) {
                                        mcDir = WfasCrypto.deriveMulticast(mcKey, info.salt)
                                        mcWin = WfasCrypto.ReplayWindow()
                                        mcLastEpoch = info.epoch
                                        SettingsRepository.setMcastClientEpoch(serverInfo.ip, info.epoch)
                                    }
                                }
                                continue
                            }
                            if (packet.length >= AUDIO_HEADER_SIZE && packet.data[0] == AUDIO_MAGIC_0 && packet.data[1] == AUDIO_MAGIC_1) {
                                if (!mcVersionChecked) {
                                    mcVersionChecked = true
                                    val packetVersion = packet.data[2].toInt() and 0xFF
                                    if (packetVersion != WFAS_PROTOCOL_VERSION) {
                                        AppDebug.log("[CLIENT][MULTICAST] packet v=$packetVersion incompatible (mine v=$WFAS_PROTOCOL_VERSION)")
                                        signalProtocolMismatch(packetVersion)
                                        onStatusUpdate("status_protocol_incompatible", emptyArray())
                                        break
                                    }
                                }
                                WfasStats.add(if ((packet.data[3].toInt() and 0x01) != 0) WfasStats.Cat.SILENCE else WfasStats.Cat.AUDIO, packet.length)
                                mcAudioPkts++
                                if ((packet.data[3].toInt() and WfasCrypto.FLAG_ENCRYPTED) != 0) {
                                    val dir = mcDir
                                    if (dir != null) {
                                        val r = WfasCrypto.decryptPacket(dir, mcWin, packet.data, packet.length)
                                        if (r is WfasCrypto.Decrypted.Ok) {
                                            mcTotalBytes += r.pcm.size
                                            val plain = ByteArray(AUDIO_HEADER_SIZE + r.pcm.size)
                                            System.arraycopy(packet.data, 0, plain, 0, AUDIO_HEADER_SIZE)
                                            System.arraycopy(r.pcm, 0, plain, AUDIO_HEADER_SIZE, r.pcm.size)
                                            player?.let { handleAudioPacket(it, plain, plain.size, effectiveAudioSettings.channels, playbackState, onAudioFrame) }
                                        }
                                    }
                                    // no key yet -> drop until a valid beacon arrives
                                } else {
                                    val pcmLen = packet.length - AUDIO_HEADER_SIZE
                                    val aligned = pcmLen - (pcmLen % frameSize)
                                    mcTotalBytes += aligned
                                    if (aligned > 0) {
                                        player?.let { handleAudioPacket(it, packet.data, packet.length, effectiveAudioSettings.channels, playbackState, onAudioFrame) }
                                    }
                                }
                            } else if (packet.length >= 3 && String(packet.data, 0, minOf(packet.length, 3), Charsets.UTF_8) == "BYE") {
                                WfasStats.add(WfasStats.Cat.BYE, packet.length)
                                AppDebug.log("---Received BYE from multicast server ---")
                                AppDebug.log("[CLIENT][MULTICAST] BYE received after $mcAudioPkts audio packets ($mcTotalBytes total bytes)")
                                onStatusUpdate("status_server_disconnected", emptyArray())
                                if (disconnectionSoundEnabled) playDisconnectionSound()
                                break
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                AppDebug.log("[CLIENT] TIMEOUT: no response from server within 15s")
                onStatusUpdate("status_server_no_response", emptyArray())
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    AppDebug.log("[CLIENT] ECCEZIONE: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                    onStatusUpdate("Error: %s", arrayOf(e.message ?: e.toString()))
                }
            } finally {
                AppDebug.log("[CLIENT] finally: closing player/SourceDataLine")
                val p = player
                if (p != null) {
                    p.stop()
                } else {
                    runCatching { sourceDataLine?.stop() }
                    runCatching { sourceDataLine?.close() }
                }
                AppDebug.log("[CLIENT] launchClientInstance ended")
            }
        }
    }

    private val MAX_CONCEAL_FRAMES = 9600L

    private class ClientPlaybackState {
        var expectedSeq = -1
        var expectedSamplePos = -1L
        var lastGoodPcm: ByteArray? = null
    }

    private class JitterAudioPlayer(
        private val line: SourceDataLine,
        sampleRate: Int,
        channels: Int,
        prebufferMs: Int = 120,
        maxBufferMs: Int = 400
    ) {
        // ── Riduzione del rumore (opt-in, modalita' sviluppatore) ───────────
        private val noiseReducer = dsp.NoiseReducer().apply { init(sampleRate, channels) }
        private var nrScratch = ShortArray(0)
        private var nrWasEnabled = false

        /** PCM 16 bit little endian, elaborato in place. */
        private fun denoise(buf: ByteArray) {
            val on = NoiseReductionControl.enabled
            if (on && !nrWasEnabled) noiseReducer.reset()
            nrWasEnabled = on
            if (!on || buf.size < 2) return
            noiseReducer.setStrength(NoiseReductionControl.strength / 100f)
            val samples = buf.size / 2
            if (nrScratch.size < samples) nrScratch = ShortArray(samples)
            val sc = nrScratch
            var bi = 0
            for (i in 0 until samples) {
                sc[i] = ((buf[bi].toInt() and 0xFF) or (buf[bi + 1].toInt() shl 8)).toShort()
                bi += 2
            }
            noiseReducer.process(sc, 0, samples)
            bi = 0
            for (i in 0 until samples) {
                val v = sc[i].toInt()
                buf[bi] = (v and 0xFF).toByte()
                buf[bi + 1] = ((v shr 8) and 0xFF).toByte()
                bi += 2
            }
        }

        private val frameBytes = (channels * 2).coerceAtLeast(2)
        private val bytesPerSec = sampleRate * frameBytes
        private val prebufferBytes =
            ((bytesPerSec.toLong() * prebufferMs) / 1000L).toInt().let { it - (it % frameBytes) }.coerceAtLeast(frameBytes)
        private val maxBytes =
            ((bytesPerSec.toLong() * maxBufferMs) / 1000L).toInt().let { it - (it % frameBytes) }.coerceAtLeast(prebufferBytes + frameBytes)
        private val silenceChunk =
            ByteArray(((bytesPerSec / 100).let { it - (it % frameBytes) }).coerceAtLeast(frameBytes))
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private val queuedBytes = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var running = false
        @Volatile private var primed = false
        private var thread: Thread? = null

        fun start() {
            if (running) return
            running = true
            runCatching { line.start() }
            thread = Thread {
                while (running) {
                    if (!primed) {
                        if (queuedBytes.get() >= prebufferBytes) primed = true
                        else {
                            try { Thread.sleep(3) } catch (_: InterruptedException) { break }
                            continue
                        }
                    }
                    val chunk = try {
                        queue.poll(15, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) { break }
                    if (chunk != null) {
                        queuedBytes.addAndGet(-chunk.size)
                        runCatching { line.write(chunk, 0, chunk.size) }
                    } else {
                        runCatching { line.write(silenceChunk, 0, silenceChunk.size) }
                    }
                }
            }.apply { isDaemon = true; name = "wfas-jitter-player"; start() }
        }

        fun submit(pcm: ByteArray, offset: Int, len: Int) {
            if (len <= 0) return
            val chunk = pcm.copyOfRange(offset, offset + len)
            denoise(chunk)
            while (queuedBytes.get() + chunk.size > maxBytes) {
                val dropped = queue.poll() ?: break
                queuedBytes.addAndGet(-dropped.size)
            }
            queue.offer(chunk)
            queuedBytes.addAndGet(chunk.size)
        }

        fun submitSilence(bytes: Int) {
            val n = bytes - (bytes % frameBytes)
            if (n <= 0) return
            submit(ByteArray(n), 0, n)
        }

        fun stop() {
            running = false
            runCatching { line.stop() }
            runCatching { line.flush() }
            thread?.interrupt()
            runCatching { thread?.join(300) }
            thread = null
            runCatching { line.close() }
        }
    }

    private fun concealGap(player: JitterAudioPlayer, lastGood: ByteArray?, totalBytes: Int, frameBytes: Int) {
        var remaining = totalBytes - (totalBytes % frameBytes)
        if (remaining <= 0) return
        val ref = lastGood
        if (ref != null && ref.isNotEmpty()) {
            var factor = 0.6f
            var iter = 0
            while (remaining > 0 && iter < 2) {
                val n = minOf(ref.size, remaining)
                val faded = ByteArray(n)
                val inB  = java.nio.ByteBuffer.wrap(ref, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val outB = java.nio.ByteBuffer.wrap(faded).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (inB.remaining() >= 2) {
                    val s = (inB.short * factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outB.putShort(s.toShort())
                }
                player.submit(faded, 0, n)
                remaining -= n
                factor *= 0.5f
                iter++
            }
        }
        if (remaining > 0) player.submitSilence(remaining)
    }

    private fun handleAudioPacket(
        player: JitterAudioPlayer,
        data: ByteArray,
        len: Int,
        channels: Int,
        state: ClientPlaybackState,
        onAudioFrame: ((ShortArray) -> Unit)?
    ) {
        if (len < AUDIO_HEADER_SIZE) return
        val frameBytes = (channels * 2).coerceAtLeast(2)
        val silence = (data[3].toInt() and 0x01) != 0
        val seq = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val samplePos = ((data[6].toInt() and 0xFF).toLong() shl 24) or
                        ((data[7].toInt() and 0xFF).toLong() shl 16) or
                        ((data[8].toInt() and 0xFF).toLong() shl 8) or
                        (data[9].toInt() and 0xFF).toLong()

        var pcmLen = len - AUDIO_HEADER_SIZE
        if (pcmLen > 0) pcmLen -= pcmLen % frameBytes

        if (state.expectedSeq >= 0) {
            val delta = (seq - state.expectedSeq) and 0xFFFF
            when {
                delta == 0 -> { }
                delta < 0x8000 -> {
                    val exactBytes = if (state.expectedSamplePos in 0L until samplePos) {
                        val frames = samplePos - state.expectedSamplePos
                        if (frames in 1L..MAX_CONCEAL_FRAMES) frames.toInt() * frameBytes else -1
                    } else -1
                    val concealBytes = if (exactBytes >= 0) exactBytes
                        else state.lastGoodPcm?.let { delta.coerceAtMost(8) * it.size } ?: 0
                    concealGap(player, state.lastGoodPcm, concealBytes.coerceAtMost(MAX_CONCEAL_FRAMES.toInt() * frameBytes), frameBytes)
                }
                else -> return
            }
        }

        state.expectedSeq = (seq + 1) and 0xFFFF
        state.expectedSamplePos = samplePos + (if (pcmLen > 0) (pcmLen / frameBytes).toLong() else 0L)

        if (silence || pcmLen <= 0) {
            player.submitSilence(state.lastGoodPcm?.size ?: (frameBytes * 480))
        } else {
            player.submit(data, AUDIO_HEADER_SIZE, pcmLen)
            val lg = state.lastGoodPcm
            if (lg == null || lg.size != pcmLen) state.lastGoodPcm = ByteArray(pcmLen)
            System.arraycopy(data, AUDIO_HEADER_SIZE, state.lastGoodPcm!!, 0, pcmLen)
            if (onAudioFrame != null && pcmLen >= 2) {
                val shorts = ShortArray(pcmLen / 2)
                java.nio.ByteBuffer.wrap(data, AUDIO_HEADER_SIZE, pcmLen)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                onAudioFrame.invoke(shorts)
            }
        }
    }

    /** La pipeline e' PCM 16 bit: altre profondita' non sono riproducibili. */
    private fun isPlayableFormat(s: AudioSettings_V1): Boolean =
        s.bitDepth == 16 &&
        s.channels in 1..2 &&
        s.sampleRate.toInt() in 4000..192000

    private fun describeFormat(s: AudioSettings_V1): String =
        "${s.sampleRate.toInt()} Hz, " +
        (if (s.channels == 1) "mono" else "stereo") + ", ${s.bitDepth} bit"

    private fun prepareSourceDataLine(mixerInfo: Mixer.Info, audioSettings: AudioSettings_V1): SourceDataLine? {
        val mixer        = AudioSystem.getMixer(mixerInfo)
        val format       = audioSettings.toAudioFormat()
        val dataLineInfo = DataLine.Info(SourceDataLine::class.java, format)

        AppDebug.log("[CLIENT] prepareSourceDataLine: mixer='${mixerInfo.name}' format=$format sr=${audioSettings.sampleRate} ch=${audioSettings.channels} bd=${audioSettings.bitDepth}")

        if (!mixer.isLineSupported(dataLineInfo)) {
            AppDebug.log("[CLIENT] ERROR: mixer '${mixerInfo.name}' does NOT support format $format")
            return null
        }

        val frameSize = format.frameSize
        val targetLatencyMs = 80
        val targetBytes = ((audioSettings.sampleRate.toInt() * targetLatencyMs / 1000) * frameSize)
            .let { it - (it % frameSize) }
            .coerceAtLeast(frameSize * 256)

        AppDebug.log("[CLIENT] SourceDataLine: frameSize=$frameSize targetLatencyMs=${targetLatencyMs}ms targetBufferBytes=$targetBytes")
        return (mixer.getLine(dataLineInfo) as SourceDataLine).also { sdl ->
            sdl.open(format, targetBytes)
            AppDebug.log("[CLIENT] SourceDataLine opened: bufferSize=${sdl.bufferSize} format=${sdl.format} realLatency=${sdl.bufferSize * 1000 / (audioSettings.sampleRate.toInt() * frameSize)}ms")

            val gainInfo = sdl.controls
                .filterIsInstance<javax.sound.sampled.FloatControl>()
                .find { it.type == javax.sound.sampled.FloatControl.Type.MASTER_GAIN }
            if (gainInfo != null) {
                AppDebug.log("[CLIENT] SDL MASTER_GAIN: ${gainInfo.value} dB (min=${gainInfo.minimum} max=${gainInfo.maximum})")
            } else {
                AppDebug.log("[CLIENT] SDL MASTER_GAIN: control not available")
            }
            val muteInfo = sdl.controls
                .filterIsInstance<javax.sound.sampled.BooleanControl>()
                .find { it.type == javax.sound.sampled.BooleanControl.Type.MUTE }
            if (muteInfo != null) {
                AppDebug.log("[CLIENT] SDL MUTE: ${muteInfo.value}")
            } else {
                AppDebug.log("[CLIENT] SDL MUTE: control not available")
            }

            sdl.start()
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
        cancelDonationTimer()

        httpServerJob?.cancelAndJoin();  httpServerJob   = null
        rtpJob?.cancelAndJoin();         rtpJob          = null
        localMicMixJob?.cancelAndJoin(); localMicMixJob  = null
        runCatching { serverEngine?.setMicMixEnabled(false) }
        runCatching { serverEngine?.stop() }
        runCatching { serverGrabber?.stop() }
        streamingJob?.cancelAndJoin();   streamingJob    = null
        micReceiverJob?.cancelAndJoin(); micReceiverJob  = null
        broadcastingJob?.cancelAndJoin(); broadcastingJob = null
        softwareMicMixer.enable(false)
        softwareMicMixer.volume = 1.0f
        isMicMuted.value = false

        val savedVol = macOriginalVolume
        if (savedVol >= 0f) {
            macOriginalVolume = -1f
            val isMacOS = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
            if (isMacOS && AudioEngine.loadLibrary()) {
                AudioEngine().setSystemVolume(savedVol)
                AppDebug.log("[Server] macOS: volume restored to $savedVol")
            }
        }
    }

    fun terminateAllServices() {
        scope.cancel()
        runCatching { selectorManager.close() }
    }

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

private enum class SetupAction { GUI, DONE, SHOW_HELP, EXIT }

private fun runInteractiveSetup(isHeadless: Boolean): SetupAction {
    val console = System.console() ?: return SetupAction.EXIT
    val ver = displayVersion(runCatching {
        object {}.javaClass.getResourceAsStream("/version.properties")
            ?.bufferedReader()?.lineSequence()
            ?.firstOrNull { it.startsWith("app.version=") }
            ?.removePrefix("app.version=")?.trim()
    }.getOrNull() ?: "")

    println()
    println("  WiFi Audio Streaming${if (ver.isNotEmpty()) " $ver" else ""}")
    println()

    data class Option(val label: String, val action: SetupAction)
    val options = mutableListOf<Option>()
    if (!isHeadless) options += Option("Open GUI", SetupAction.GUI)
    val alreadyInstalled = CliPathInstaller.isInstalled()
    val pathDetail = if (CliPathInstaller.isWindows) "" else "  (~/.local/bin/wfas + man wfas)"
    options += Option(
        (if (alreadyInstalled) "Reinstall wfas to PATH" else "Add wfas to PATH") + pathDetail,
        SetupAction.DONE
    )
    options += Option("Show help", SetupAction.SHOW_HELP)
    options += Option("Exit",      SetupAction.EXIT)

    options.forEachIndexed { i, o -> println("  ${i + 1}) ${o.label}") }
    println()

    val choice = console.readLine("  Choose [1-${options.size}]: ")
        ?.trim()?.toIntOrNull()?.minus(1) ?: return SetupAction.EXIT
    val selected = options.getOrNull(choice) ?: return SetupAction.EXIT

    if (selected.action == SetupAction.DONE) {
        println()
        when (val result = CliPathInstaller.install()) {
            is CliPathInstaller.InstallResult.Success ->
                println("  ✓  Done.\n     Restart your terminal and run: wfas --help\n     Then try: man wfas")
            is CliPathInstaller.InstallResult.Failure ->
                System.err.println("  ✗  ${result.reason}")
            is CliPathInstaller.InstallResult.TerminalLaunched ->
                println("  ✓  Terminal launched for installation.")
        }
        println()
        return SetupAction.EXIT
    }
    return selected.action
}

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))
    System.setErr(java.io.PrintStream(System.err, true, "UTF-8"))

    CliPathInstaller.refreshIfOutdated()

    val cliArgs = CliArgs.parse(args)

    cliArgs.configPath?.let { ConfigPaths.overrideConfigFile = java.io.File(it) }

    if (cliArgs.configCmd != null) {
        val code = ConfigCli.run(cliArgs.configCmd, cliArgs.json)
        kotlin.system.exitProcess(code)
    }

    if (cliArgs.firewallCmd != null) {
        val code = FirewallCli.run(cliArgs.firewallCmd, cliArgs.json)
        kotlin.system.exitProcess(code)
    }

    if (cliArgs.printBareHint) { CliArgs.printBareHint(); return }
    if (cliArgs.printHelp)     { CliArgs.printHelp();     return }
    if (cliArgs.printVersion)  { CliArgs.printVersion();  return }
    if (cliArgs.printProtocol) { CliArgs.printProtocol(); return }
    if (cliArgs.printLicenses) { CliArgs.printLicenses(); return }
    if (cliArgs.printFred)     { CliArgs.printFred();     return }

    if (cliArgs.autoCheckUpdate != null) {
        val on = cliArgs.autoCheckUpdate == "on"
        SettingsRepository.setAutoUpdateCheckEnabled(on)
        println(if (on) "Automatic update check enabled." else "Automatic update check disabled.")
        return
    }
    if (cliArgs.checkUpdate) { printUpdateCheck(); return }

    // FIX CRASH: disabilita AVX-512/AVX3 — causa EXCEPTION_ACCESS_VIOLATION in
    // StubRoutines::jlong_disjoint_arraycopy_avx3 durante la copia di buffer audio
    // nativi su alcune CPU Windows con la JVM Temurin 17.
    System.setProperty("java.net.preferIPv4Stack", "true")

    AudioEngine.loadLibrary()
    val loadedSettingsForInit = SettingsRepository.loadSettings()
    NetworkHandler_v1.setupLinuxVirtualCable(loadedSettingsForInit.app.useNativeEngine)
    org.bytedeco.javacv.FFmpegLogCallback.set()
    org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all()

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { NetworkHandler_v1.terminateAllServices() }
    })

    val isHeadless = java.awt.GraphicsEnvironment.isHeadless()

    // Interactive terminal with no arguments: show first-run setup menu
    if (args.isEmpty() && System.console() != null) {
        when (runInteractiveSetup(isHeadless)) {
            SetupAction.GUI       -> startGuiApplication(CliArgs(runMode = RunMode.GUI))
            SetupAction.SHOW_HELP -> CliArgs.printHelp()
            else                  -> Unit
        }
        return
    }

    if (cliArgs.runMode != RunMode.GUI || isHeadless) {
        runCli(cliArgs)
    } else {
        startGuiApplication(cliArgs)
    }
}

fun startGuiApplication(cliArgs: CliArgs) = application {
    val loadedSettings = SettingsRepository.loadSettings()
    var appSettings    by remember { mutableStateOf(loadedSettings.app) }
    var audioSettings  by remember { mutableStateOf(loadedSettings.audio) }
    var streamingPort  by remember { mutableStateOf(loadedSettings.streamingPort) }
    var micPort        by remember { mutableStateOf(loadedSettings.micPort) }
    var micRoutingMode by remember { mutableStateOf(MicRoutingMode.fromStringSafe(loadedSettings.micRoutingMode)) }

    val isWindowsOS = remember { System.getProperty("os.name").lowercase().contains("win") }
    var serverVolume by remember { mutableStateOf(1f) }

    LaunchedEffect(serverVolume) { NetworkHandler_v1.setServerVolume(serverVolume) }

    // Il denoiser legge da qui: cambiando l'impostazione l'effetto e' immediato
    // sul flusso in riproduzione, senza riconnettersi.
    LaunchedEffect(appSettings.developerMode, appSettings.noiseReductionEnabled, appSettings.noiseReductionStrength) {
        NoiseReductionControl.apply(
            appSettings.developerMode && appSettings.noiseReductionEnabled,
            appSettings.noiseReductionStrength
        )
    }

    LaunchedEffect(appSettings, audioSettings, streamingPort, micPort, micRoutingMode) {
        SettingsRepository.saveSettings(AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode.name))
    }

    var showWelcome        by remember { mutableStateOf(!SettingsRepository.hasSeenWelcome()) }
    var showChangelog      by remember {
        mutableStateOf(
            SettingsRepository.hasSeenWelcome() &&
                SettingsRepository.lastSeenChangelog() != Changelog.latest.version
        )
    }
    var changelogStandalone by remember { mutableStateOf(SettingsRepository.hasSeenWelcome()) }
    var showDonation        by remember {
        mutableStateOf(
            SettingsRepository.hasSeenWelcome() &&
                SettingsRepository.isDonationQualified() &&
                System.currentTimeMillis() >= SettingsRepository.donationSnoozeUntil()
        )
    }
    var updateBanner        by remember { mutableStateOf<UpdateChecker.Result.Available?>(null) }
    var versionAhead        by remember { mutableStateOf<UpdateChecker.Result.Ahead?>(null) }
    var manualUpdateResult  by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var checkingForUpdate   by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (SettingsRepository.isAutoUpdateCheckEnabled()) {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UpdateChecker.check() }
            // In automatico si mostra solo qualcosa di utile: se GitHub non
            // risponde si resta in silenzio.
            when (r) {
                is UpdateChecker.Result.Available -> updateBanner = r
                is UpdateChecker.Result.Ahead     -> versionAhead = r
                else -> Unit
            }
        }
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
                AppDebug.log("Warning: Dorkbox SystemTray is not supported on this system/DE.")
            }
        }
    }

    Window(
        onCloseRequest = hideOrQuit,
        state      = windowState,
        visible    = isWindowVisible,
        undecorated = false,
        title      = "WiFi Audio Streaming",
        icon       = trayIcon
    ) {
        // Enforce a minimum window size so content is always reachable on low-res displays.
        LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(400, 500)
        }
        LaunchedEffect(appSettings.securityMode, appSettings.authKey, appSettings.encryptionEnabled) {
            NetworkHandler_v1.configureSecurity(appSettings.securityMode, appSettings.authKey, appSettings.encryptionEnabled)
        }
        var authRequestPeer by remember { mutableStateOf<String?>(null) }
        val authDecision = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
        LaunchedEffect(Unit) {
            NetworkHandler_v1.onAuthRequest = { peer ->
                val def = kotlinx.coroutines.CompletableDeferred<Boolean>()
                authDecision.set(def)
                authRequestPeer = peer
                runCatching { kotlinx.coroutines.runBlocking { def.await() } }.getOrDefault(false)
            }
        }
        var keyRequestWrong by remember { mutableStateOf<Boolean?>(null) }
        val keyDecision = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<String?>?>(null) }
        LaunchedEffect(Unit) {
            NetworkHandler_v1.onKeyRequest = { wrong ->
                val def = kotlinx.coroutines.CompletableDeferred<String?>()
                keyDecision.set(def)
                keyRequestWrong = wrong
                runCatching { def.await() }.getOrNull()
            }
        }
        val customColor = appSettings.customThemeColor?.toULong()?.let { Color(it) }
        val currentColorScheme = if (customColor != null) {
            MaterialYouGenerator.generateDynamicColorScheme(customColor, useDarkTheme)
        } else {
            if (useDarkTheme) darkColorScheme() else lightColorScheme()
        }

        MaterialTheme(colorScheme = currentColorScheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                authRequestPeer?.let { peer ->
                    AlertDialog(
                        onDismissRequest = { authDecision.getAndSet(null)?.complete(false); authRequestPeer = null },
                        title = { Text(stringResource("auth_request_title")) },
                        text  = { Text(stringResource("auth_request_body", peer)) },
                        confirmButton = {
                            Button(onClick = { authDecision.getAndSet(null)?.complete(true); authRequestPeer = null }) {
                                Text(stringResource("auth_allow"))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { authDecision.getAndSet(null)?.complete(false); authRequestPeer = null }) {
                                Text(stringResource("auth_deny"))
                            }
                        }
                    )
                }
                keyRequestWrong?.let { wrong ->
                    var keyText by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { keyDecision.getAndSet(null)?.complete(null); keyRequestWrong = null },
                        title = { Text(stringResource("key_dialog_title")) },
                        text = {
                            Column {
                                Text(if (wrong) stringResource("key_dialog_wrong") else stringResource("key_dialog_body"))
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = keyText,
                                    onValueChange = { keyText = it },
                                    singleLine = true,
                                    label = { Text(stringResource("auth_key_label")) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = { keyDecision.getAndSet(null)?.complete(keyText); keyRequestWrong = null }) {
                                Text(stringResource("key_dialog_connect"))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { keyDecision.getAndSet(null)?.complete(null); keyRequestWrong = null }) {
                                Text(stringResource("key_dialog_cancel"))
                            }
                        }
                    )
                }
                val clientDisconnectKeys = remember {
                    setOf(
                        "status_server_disconnected",
                        "status_server_no_response",
                        "status_handshake_failed",
                        "status_protocol_incompatible",
                        "status_server_busy",
                        "status_unauthorized",
                        "status_unsupported_format"
                    )
                }

                val protocolMismatch by NetworkHandler_v1.protocolMismatch.collectAsState()
                if (protocolMismatch != null) {
                    val mm = protocolMismatch!!
                    AlertDialog(
                        onDismissRequest = { NetworkHandler_v1.clearProtocolMismatch() },
                        icon  = { Icon(Icons.Default.Warning, contentDescription = null) },
                        title = { Text(Strings.get("protocol_incompatible_title")) },
                        text  = { Text(Strings.get("protocol_incompatible_body", mm.localVersion, mm.remoteVersion)) },
                        confirmButton = {
                            Button(onClick = {
                                val updateUrl = if (mm.localVersion < mm.remoteVersion)
                                    "https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/releases"
                                else
                                    "https://github.com/marcomorosi06/WiFiAudioStreaming-Android/releases"
                                runCatching { openUrl(updateUrl) }
                                NetworkHandler_v1.clearProtocolMismatch()
                            }) { Text(Strings.get("protocol_incompatible_update")) }
                        },
                        dismissButton = {
                            TextButton(onClick = { NetworkHandler_v1.clearProtocolMismatch() }) { Text(Strings.get("close")) }
                        }
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

                        isMulticastMode = appSettings.autoStartMulticast || appSettings.rtpEnabled || appSettings.httpEnabled

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

                LaunchedEffect(Unit) {
                    IpcServer.start(cliArgs)
                }

                LaunchedEffect(Unit) {
                    delay(300)
                    when (cliArgs.guiInitMode) {
                        "server" -> {
                            if (isStreaming) return@LaunchedEffect
                            isServer = true
                            isStreaming = true
                            connectionStatus = "Starting Server..."

                            val port = cliArgs.port
                            val mic  = cliArgs.micPort
                            val rtp  = cliArgs.rtpPort

                            if (cliArgs.multicast || cliArgs.rtp || cliArgs.http)
                                isMulticastMode = true

                            val protocols = mutableSetOf(StreamingProtocol.WFAS)
                            if (cliArgs.rtp)  protocols += StreamingProtocol.RTP
                            if (cliArgs.http) protocols += StreamingProtocol.HTTP
                            val caps = ServerCapabilities(
                                protocols  = protocols,
                                httpPort   = if (cliArgs.http) cliArgs.httpPort else null,
                                safariMode = cliArgs.httpSafari
                            )

                            val micMode = if (cliArgs.mic) cliArgs.micRouting else micRoutingMode

                            NetworkHandler_v1.launchServerInstance(
                                audioSettings, port, isMulticastMode, caps,
                                micMode, selectedServerMicOutput, mic, rtp,
                                cliArgs.useNativeEngine, selectedMicMixInput
                            ) { key, args ->
                                if (key == "error_virtual_driver_missing") {
                                    isStreaming = false
                                    virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus(cliArgs.useNativeEngine)
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

                            if (cliArgs.volume != null) NetworkHandler_v1.setServerVolume(cliArgs.volume)
                            if (cliArgs.mute)           NetworkHandler_v1.isMicMuted.value = true
                        }

                        "client" -> {
                            if (isStreaming || selectedOutputDevice == null) return@LaunchedEffect
                            isServer = false
                            isStreaming = true

                            val serverInfo = if (cliArgs.serverIp != null) {
                                ServerInfo(ip = cliArgs.serverIp, isMulticast = false, port = cliArgs.port)
                            } else {
                                discoveredDevices.values.firstOrNull()
                            } ?: return@LaunchedEffect

                            connectionStatus = "Connecting to ${serverInfo.ip}..."
                            NetworkHandler_v1.endDeviceDiscovery()
                            NetworkHandler_v1.launchClientInstance(
                                audioSettings, serverInfo, selectedOutputDevice!!,
                                cliArgs.mic, selectedClientMic, cliArgs.micPort,
                                appSettings.connectionSoundEnabled,
                                appSettings.disconnectionSoundEnabled,
                                onStatusUpdate = clientStatusHandler
                            )

                            if (cliArgs.volume != null) NetworkHandler_v1.setServerVolume(cliArgs.volume)
                            if (cliArgs.mute)           NetworkHandler_v1.isMicMuted.value = true
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
                                        onStatusUpdate = clientStatusHandler
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

                                    val manualServerInfo = ServerInfo(ip, isMulti, port, knownServer?.capabilities, serverAudioSettings = knownServer?.serverAudioSettings)
                                    NetworkHandler_v1.endDeviceDiscovery()
                                    NetworkHandler_v1.launchClientInstance(
                                        audioSettings, manualServerInfo, selectedOutputDevice!!,
                                        sendMicrophone, selectedClientMic, mic,
                                        appSettings.connectionSoundEnabled,
                                        appSettings.disconnectionSoundEnabled,
                                        onStatusUpdate = clientStatusHandler
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

                                isMulticastMode = isMulticastMode || appSettings.rtpEnabled || appSettings.httpEnabled

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
                                    onStatusUpdate = clientStatusHandler
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
                            onCustomColorChange    = { color -> appSettings = appSettings.copy(customThemeColor = color) },
                            onShowWelcome          = { showSettings = false; showWelcome = true },
                            checkingForUpdate      = checkingForUpdate,
                            onCheckForUpdates      = {
                                if (!checkingForUpdate) {
                                    checkingForUpdate = true
                                    scope.launch {
                                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UpdateChecker.check() }
                                        checkingForUpdate = false
                                        manualUpdateResult = r
                                    }
                                }
                            }
                        )

                        WelcomeScreen(
                            visible   = showWelcome,
                            autoUpdateEnabled = appSettings.autoUpdateCheckEnabled,
                            onAutoUpdateChange = { enabled ->
                                appSettings = appSettings.copy(autoUpdateCheckEnabled = enabled)
                            },
                            onDismiss = {
                                showWelcome = false
                                SettingsRepository.markWelcomeSeen()
                                changelogStandalone = false
                                showChangelog = true
                            }
                        )

                        ChangelogScreen(
                            visible    = showChangelog,
                            standalone = changelogStandalone,
                            onContinue = {
                                showChangelog = false
                                SettingsRepository.setLastSeenChangelog(Changelog.latest.version)
                            }
                        )

                        if (showDonation && !showWelcome && !showChangelog) {
                            val snoozeLater = {
                                showDonation = false
                                val c = SettingsRepository.donationDismissCount() + 1
                                SettingsRepository.setDonationDismissCount(c)
                                SettingsRepository.setDonationQualified(false)
                                SettingsRepository.setDonationSnoozeUntil(System.currentTimeMillis() + SettingsRepository.donationBackoffDays(c) * 24 * 60 * 60 * 1000)
                            }
                            val snooze30 = {
                                showDonation = false
                                SettingsRepository.setDonationDismissCount(4)
                                SettingsRepository.setDonationQualified(false)
                                SettingsRepository.setDonationSnoozeUntil(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
                            }
                            AlertDialog(
                                onDismissRequest = snoozeLater,
                                icon  = { Icon(Icons.Outlined.Favorite, contentDescription = null) },
                                title = { Text(Strings.get("donation_title")) },
                                text  = { Text(Strings.get("donation_body")) },
                                confirmButton = {
                                    Button(onClick = {
                                        runCatching { openUrl("https://ko-fi.com/marcomorosi") }
                                        showDonation = false
                                        SettingsRepository.setDonationDismissCount(0)
                                        SettingsRepository.setDonationQualified(false)
                                        SettingsRepository.setDonationSnoozeUntil(System.currentTimeMillis() + 14L * 24 * 60 * 60 * 1000)
                                    }) { Text(Strings.get("donation_support")) }
                                },
                                dismissButton = {
                                    Row {
                                        TextButton(onClick = snooze30) { Text(Strings.get("donation_dismiss_30")) }
                                        TextButton(onClick = snoozeLater) { Text(Strings.get("donation_later")) }
                                    }
                                }
                            )
                        }

                        updateBanner?.let { info ->
                            AlertDialog(
                                onDismissRequest = { updateBanner = null },
                                icon  = { Icon(Icons.Outlined.Update, contentDescription = null) },
                                title = { Text(Strings.get("update_available_title")) },
                                text  = {
                                    Text(Strings.get("update_available_body", info.latest, info.current))
                                },
                                confirmButton = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            runCatching { openUrl(DownloadLinks.site()) }
                                            updateBanner = null
                                        }) { Text(Strings.get("update_download_site")) }
                                        FilledTonalButton(onClick = {
                                            runCatching { openUrl(DownloadLinks.GITHUB_RELEASES) }
                                            updateBanner = null
                                        }) { Text(Strings.get("update_download_github")) }
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { updateBanner = null }) {
                                        Text(Strings.get("update_later"))
                                    }
                                }
                            )
                        }

                        versionAhead?.let {
                            AlertDialog(
                                onDismissRequest = { versionAhead = null },
                                icon  = { Icon(Icons.Outlined.EmojiEvents, contentDescription = null) },
                                title = { Text(Strings.get("update_ahead_title")) },
                                text  = { Text(Strings.get("update_ahead_body")) },
                                confirmButton = {
                                    TextButton(onClick = { versionAhead = null }) {
                                        Text(Strings.get("close"))
                                    }
                                }
                            )
                        }

                        manualUpdateResult?.let { res ->
                            val message = when (res) {
                                is UpdateChecker.Result.Available ->
                                    Strings.get("update_available_body", res.latest, res.current)
                                is UpdateChecker.Result.UpToDate ->
                                    Strings.get("update_uptodate", res.current)
                                is UpdateChecker.Result.Ahead ->
                                    Strings.get("update_ahead_body")
                                is UpdateChecker.Result.Failed ->
                                    Strings.get("update_check_failed")
                            }
                            AlertDialog(
                                onDismissRequest = { manualUpdateResult = null },
                                icon  = { Icon(Icons.Outlined.Update, contentDescription = null) },
                                title = { Text(Strings.get("update_check_title")) },
                                text  = { Text(message) },
                                confirmButton = {
                                    if (res is UpdateChecker.Result.Available) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = {
                                                runCatching { openUrl(DownloadLinks.site()) }
                                                manualUpdateResult = null
                                            }) { Text(Strings.get("update_download_site")) }
                                            FilledTonalButton(onClick = {
                                                runCatching { openUrl(DownloadLinks.GITHUB_RELEASES) }
                                                manualUpdateResult = null
                                            }) { Text(Strings.get("update_download_github")) }
                                        }
                                    } else {
                                        TextButton(onClick = { manualUpdateResult = null }) {
                                            Text(Strings.get("close"))
                                        }
                                    }
                                },
                                dismissButton = {
                                    if (res is UpdateChecker.Result.Available) {
                                        TextButton(onClick = { manualUpdateResult = null }) {
                                            Text(Strings.get("close"))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

