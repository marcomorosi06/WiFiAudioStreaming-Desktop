/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import wfas3.W3
import wfas3.Wfas3Addr
import wfas3.Wfas3Beacon
import wfas3.Wfas3Client
import wfas3.Wfas3ClientState
import wfas3.Wfas3Engine
import wfas3.Wfas3Exception
import wfas3.Wfas3Failure
import wfas3.Wfas3Library
import wfas3.Wfas3Listener
import wfas3.Wfas3PairInfo
import wfas3.Wfas3PcmOutput
import wfas3.Wfas3Peer
import wfas3.Wfas3Resampler
import wfas3.Wfas3Server
import wfas3.Wfas3ServerSettings
import wfas3.Wfas3Store
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** Settings helpers for WFAS v3 on the desktop. */
object Wfas3Settings {
    const val PROTOCOL_V3 = "v3"
    const val PROTOCOL_V2 = "v2"
    val PROTOCOLS = listOf(PROTOCOL_V3, PROTOCOL_V2)
    const val CODEC_PCM = "pcm"
    const val CODEC_OPUS = "opus"
    val CODECS = listOf(CODEC_PCM, CODEC_OPUS)
    const val OPUS_KBPS_MIN = 32
    const val OPUS_KBPS_MAX = 510
    val OPUS_FRAMES = listOf(120, 240, 480, 960)
    val PCM_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000)

    fun isV3(app: AppSettings): Boolean = app.wfasProtocol != PROTOCOL_V2

    /** The v3 server settings for these app and audio settings. */
    fun server(app: AppSettings, audio: AudioSettings_V1, multicast: Boolean, acceptMic: Boolean): Wfas3ServerSettings {
        val rate = audio.sampleRate.toInt().takeIf { it in PCM_RATES } ?: 48000
        return Wfas3ServerSettings(
            codec = if (app.v3Codec == CODEC_OPUS) W3.CODEC_OPUS else W3.CODEC_PCM,
            sampleRate = rate,
            channels = audio.channels.coerceIn(1, 2),
            bits = 16,
            opusKbps = app.v3OpusKbps.coerceIn(OPUS_KBPS_MIN, OPUS_KBPS_MAX),
            opusFrameSamples = app.v3OpusFrameSamples.takeIf { it in OPUS_FRAMES } ?: 480,
            latencyMs = app.v3LatencyMs.coerceIn(20, 1000),
            sync = app.v3Sync,
            multicast = multicast,
            // A group has fixed flags: the microphone needs its own session (bit3).
            acceptMicrophone = acceptMic && !multicast,
            acceptNew = app.v3AcceptNew,
        )
    }

    fun deviceName(app: AppSettings): String = app.v3DeviceName.trim().ifEmpty {
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.')?.takeIf { it.isNotBlank() }
            ?: "WiFi Audio Streaming"
    }
}

/**
 * Encrypted storage of the v3 records on the desktop.
 *
 * One random 256-bit key lives in the OS credential store (DPAPI, Keychain,
 * Secret Service: [SecretVault]); every record is a file sealed with it
 * (AES-256-GCM, the file name as associated data, so a file cannot be
 * swapped for another). Without a credential store - a headless Linux box -
 * the key is kept in a file readable by this account only, like an SSH key,
 * and [protection] says so. Files are written to a temporary file, flushed
 * and moved into place; erased files are overwritten first.
 */
class Wfas3FileStore(private val dir: File) : Wfas3Store {
    private val rng = SecureRandom()
    @Volatile private var key: ByteArray? = null
    @Volatile var protection: String = ""
        private set

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
        ownerOnly(dir, directory = true)
    }

    private fun key(): ByteArray {
        key?.let { return it }
        synchronized(this) {
            key?.let { return it }
            ensureDir()
            val k = loadKey() ?: run {
                // A new key would make every stored record unreadable and the
                // next save would replace the device record: with records on
                // disk and no key (a reset keychain, a copied folder), stop
                // here and touch nothing.
                if (hasRecords()) throw Wfas3Exception(Wfas3Failure.STORE_KEY, "store key missing")
                newKey()
            }
            key = k
            return k
        }
    }

    private fun hasRecords(): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.endsWith(".rec") } ?: false

    override fun hasPairRecords(): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.startsWith("pair-") && it.name.endsWith(".rec") } ?: false

    private fun loadKey(): ByteArray? {
        SecretVault.load(VAULT_NAME)?.let { b64 ->
            val k = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            if (k != null && k.size == 32) { protection = SecretVault.label; return k }
        }
        val f = File(dir, KEY_FILE)
        if (f.isFile) {
            val k = runCatching { Files.readAllBytes(f.toPath()) }.getOrNull()
            if (k != null && k.size == 32) { protection = PROTECTION_FILE; return k }
        }
        return null
    }

    private fun newKey(): ByteArray {
        // A key is made only when there are no records it could have opened.
        val k = ByteArray(32).also { rng.nextBytes(it) }
        if (SecretVault.available && SecretVault.store(VAULT_NAME, Base64.getEncoder().encodeToString(k))) {
            protection = SecretVault.label
        } else {
            writeAtomic(File(dir, KEY_FILE), k)
            protection = PROTECTION_FILE
        }
        return k
    }

    private fun seal(name: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(("wfas3:" + name).toByteArray(Charsets.US_ASCII))
        val ct = c.doFinal(plain)
        return MAGIC + nonce + ct
    }

    private fun open(name: String, blob: ByteArray): ByteArray? {
        if (blob.size < MAGIC.size + 12 + 16 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, blob, MAGIC.size, 12))
            c.updateAAD(("wfas3:" + name).toByteArray(Charsets.US_ASCII))
            c.doFinal(blob, MAGIC.size + 12, blob.size - MAGIC.size - 12)
        }.getOrNull()
    }

    /** Null if the record does not exist; throws if it exists and cannot be read or opened. */
    private fun read(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val blob = try { Files.readAllBytes(f.toPath()) } catch (e: java.io.IOException) {
            throw java.io.IOException("record not readable")
        }
        return open(name, blob) ?: throw java.io.IOException("record does not open")
    }

    private fun write(name: String, plain: ByteArray) {
        ensureDir()
        val blob = seal(name, plain)
        writeAtomic(File(dir, name), blob)
    }

    private fun erase(name: String) {
        val f = File(dir, name)
        if (!f.exists()) return
        runCatching {
            val n = f.length().toInt().coerceAtLeast(0)
            FileChannel.open(f.toPath(), StandardOpenOption.WRITE).use { ch ->
                ch.write(ByteBuffer.allocate(n))
                ch.force(true)
            }
        }
        if (!f.delete()) throw java.io.IOException("record not erased")
    }

    private fun writeAtomic(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        Files.deleteIfExists(tmp.toPath())
        if (posix) {
            Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            tmp.createNewFile()
            ownerOnly(tmp, directory = false)
        }
        FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            ch.write(ByteBuffer.wrap(data))
            ch.force(true)
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (posix) runCatching { FileChannel.open(target.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun ownerOnly(f: File, directory: Boolean) {
        if (posix) {
            runCatching {
                Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            }
            return
        }
        // Windows: File.setReadable cannot take access away there. The ACL is
        // set to one entry, full control for the owner (inherited by what the
        // folder holds), and nothing for anyone else.
        val done = runCatching {
            val view = Files.getFileAttributeView(f.toPath(), AclFileAttributeView::class.java)
                ?: return@runCatching false
            val owner = Files.getOwner(f.toPath())
            val entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(*AclEntryPermission.values())
                .apply { if (directory) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) }
                .build()
            view.acl = listOf(entry)
            true
        }.getOrDefault(false)
        if (!done) runCatching {
            f.setReadable(false, false); f.setWritable(false, false)
            f.setReadable(true, true); f.setWritable(true, true)
            if (directory) { f.setExecutable(false, false); f.setExecutable(true, true) }
        }
    }

    private var lockChannel: FileChannel? = null
    private var lockHeld: java.nio.channels.FileLock? = null

    /**
     * Only one process may use the records at a time: two engines on the same
     * pair records would reuse the same nonce ranges. The lock belongs to the
     * process, so a crash never leaves it behind. Returns false if another
     * process holds it; throws if the folder cannot be used at all.
     */
    @Synchronized
    fun acquire(): Boolean {
        if (lockHeld?.isValid == true) return true
        try {
            ensureDir()
            val ch = FileChannel.open(File(dir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val l = try { ch.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (l == null) { ch.close(); return false }
            lockChannel = ch
            lockHeld = l
            return true
        } catch (x: java.io.IOException) {
            throw Wfas3Exception(Wfas3Failure.STORAGE, "store folder not usable")
        }
    }

    @Synchronized
    fun release() {
        runCatching { lockHeld?.release() }
        runCatching { lockChannel?.close() }
        lockHeld = null
        lockChannel = null
    }

    override fun loadDevice(): ByteArray? = read(DEVICE)
    override fun saveDevice(record: ByteArray) = write(DEVICE, record)
    override fun loadPair(slot: Int): ByteArray? = read(pairName(slot))
    override fun savePair(slot: Int, record: ByteArray) = write(pairName(slot), record)
    override fun erasePair(slot: Int) = erase(pairName(slot))
    override fun loadHint(slot: Int): ByteArray? = read(hintName(slot))
    override fun saveHint(slot: Int, data: ByteArray?) { if (data == null) erase(hintName(slot)) else write(hintName(slot), data) }

    override fun eraseAll() {
        dir.listFiles()?.forEach { f -> if (f.name != LOCK_FILE) runCatching { erase(f.name) } }
        SecretVault.clear(VAULT_NAME)
        key?.fill(0)
        key = null
    }

    companion object {
        private val MAGIC = "W3S1".toByteArray(Charsets.US_ASCII)
        private const val DEVICE = "device.rec"
        private const val KEY_FILE = "store.key"
        private const val LOCK_FILE = "lock"
        const val VAULT_NAME = "wfas3-store-key"
        const val PROTECTION_FILE = "file"
        private val posix = runCatching {
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        }.getOrDefault(false)
        private fun pairName(slot: Int) = "pair-%02d.rec".format(slot)
        private fun hintName(slot: Int) = "hint-%02d.rec".format(slot)

        /**
         * Where the records live. Not in the settings folder: on Windows that
         * is the roaming profile, which can be copied to other machines and
         * servers; on Linux ~/.config is often synchronised or backed up. The
         * records go to the local, non-roaming state folder instead
         * (ConfigPaths.stateDir), and a folder left by an earlier version is
         * moved there once. With --config the folder next to that file is
         * kept, as the user chose it.
         */
        fun defaultDir(): File {
            val legacy = File(ConfigPaths.configDir(), "wfas3")
            if (ConfigPaths.overrideConfigFile != null) return legacy
            val target = File(ConfigPaths.stateDir(), "wfas3")
            if (target.absoluteFile == legacy.absoluteFile) return excludeFromBackup(target)
            if (target.isDirectory || !legacy.isDirectory) return excludeFromBackup(target)
            return if (migrate(legacy, target)) excludeFromBackup(target) else legacy
        }

        /**
         * Move the records of an earlier version. Not while another process
         * holds them (its lock), and never half: a failed copy is removed and
         * the old folder stays in use.
         */
        private fun migrate(from: File, to: File): Boolean {
            val lockCh = runCatching {
                FileChannel.open(File(from, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }.getOrNull() ?: return false
            try {
                val l = try { lockCh.tryLock() } catch (_: Exception) { null } ?: return false
                l.release()
            } finally {
                runCatching { lockCh.close() }
            }
            to.parentFile?.mkdirs()
            val moved = runCatching {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            }.getOrDefault(false)
            if (moved) return true
            // Another volume: copy, check, then erase the old copies.
            val names = from.listFiles()?.filter { it.isFile && it.name != LOCK_FILE } ?: return false
            val ok = runCatching {
                to.mkdirs()
                for (f in names) {
                    val t = File(to, f.name)
                    Files.copy(f.toPath(), t.toPath(), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    if (!Files.readAllBytes(t.toPath()).contentEquals(Files.readAllBytes(f.toPath()))) error("copy differs")
                }
                true
            }.getOrDefault(false)
            if (!ok) {
                to.listFiles()?.forEach { runCatching { it.delete() } }
                runCatching { to.delete() }
                return false
            }
            for (f in names) {
                runCatching {
                    val n = f.length().toInt().coerceAtLeast(0)
                    FileChannel.open(f.toPath(), StandardOpenOption.WRITE).use { ch ->
                        ch.write(ByteBuffer.allocate(n)); ch.force(true)
                    }
                    f.delete()
                }
            }
            runCatching { File(from, LOCK_FILE).delete() }
            runCatching { from.delete() }
            return true
        }

        /**
         * macOS: Time Machine skips the folder (a sticky exclusion, no
         * administrator needed). A restored old copy of the records would make
         * the pairs look stolen, and the copy is of no use without the key in
         * the Keychain anyway. Best effort, once per run.
         */
        @Volatile private var excluded = false

        private fun excludeFromBackup(d: File): File {
            if (excluded || ConfigPaths.os != HostOs.MACOS) return d
            excluded = true
            runCatching {
                d.mkdirs()
                ProcessBuilder("/usr/bin/tmutil", "addexclusion", d.absolutePath)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }
            return d
        }
    }
}

/** Loads libwfas3 from the resources, after checking it against the bytes it was extracted from. */
object Wfas3NativeLoader {
    @Volatile var error: String? = null
        private set

    fun install() {
        Wfas3Library.loader = { load() }
    }

    private fun load() {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val osDir = when { osName.contains("win") -> "windows"; osName.contains("mac") -> "macos"; else -> "linux" }
        val archDir = if (osArch.contains("aarch64") || osArch.contains("arm64")) "arm64" else "x86_64"
        val libName = when { osName.contains("win") -> "wfas3.dll"; osName.contains("mac") -> "libwfas3.dylib"; else -> "libwfas3.so" }
        val path = "/native/$osDir/$archDir/$libName"
        try {
            val bytes = (Wfas3NativeLoader::class.java.getResourceAsStream(path) as InputStream?)?.use { it.readBytes() }
                ?: throw UnsatisfiedLinkError("wfas3 library not found: $path")
            val want = MessageDigest.getInstance("SHA-256").digest(bytes)
            // A private directory: no other account can replace the file
            // between the moment it is written and the moment it is loaded.
            val tmpDir = if (runCatching { java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix") }.getOrDefault(false)) {
                Files.createTempDirectory("wfas3-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createTempDirectory("wfas3-")
            }
            val f = tmpDir.resolve(libName)
            Files.write(f, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val got = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))
            if (!MessageDigest.isEqual(want, got) || Files.isSymbolicLink(f) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                throw UnsatisfiedLinkError("wfas3 library changed after extraction")
            }
            System.load(f.toAbsolutePath().toString())
            f.toFile().deleteOnExit()
            tmpDir.toFile().deleteOnExit()
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            throw t
        }
    }
}

/** Plays a v3 stream on a SourceDataLine. */
class Wfas3LineOutput(private val line: SourceDataLine, override val rate: Int, override val channels: Int) : Wfas3PcmOutput {
    private val bytes = ByteArray(rate / 100 * channels * 2 * 4)
    private var written = 0L

    /** Writes in chunks of the buffer; the client volume of the app applies here, as in v2. */
    override fun write(buf: ShortArray, frames: Int) {
        val n = frames * channels
        val vol = NetworkHandler_v1.currentClientVolume
        var i = 0
        while (i < n) {
            var j = 0
            while (i < n && j + 1 < bytes.size) {
                var s = buf[i++].toInt()
                if (vol != 1.0f) s = (s * vol).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[j++] = s.toByte()
                bytes[j++] = (s shr 8).toByte()
            }
            line.write(bytes, 0, j)
        }
        written += frames
    }

    override fun pendingUs(): Long {
        val played = runCatching { line.longFramePosition }.getOrDefault(written)
        val pending = (written - played).coerceAtLeast(0L)
        return pending * 1_000_000L / rate
    }

    // Writes block once the line buffer is full: the system may give more than was asked.
    override val bufferUs: Long = line.bufferSize.toLong() / (channels * 2) * 1_000_000L / rate

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }

    companion object {
        /** A line on [mixer] (or the default device) for 16-bit PCM, with a short buffer. */
        fun open(mixer: Mixer.Info?, rate: Int, channels: Int): Wfas3LineOutput {
            val fmt = AudioFormat(rate.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, fmt)
            val line = (if (mixer != null) AudioSystem.getMixer(mixer).getLine(info) else AudioSystem.getLine(info)) as SourceDataLine
            line.open(fmt, rate / 1000 * 40 * channels * 2)
            line.start()
            return Wfas3LineOutput(line, rate, channels)
        }
    }
}

/**
 * The v3 side of the desktop app: one engine, the server or the client
 * role, pairing, and the state the interface shows. Every user-facing
 * message is described by state here; the interface words it.
 */
object Wfas3Desktop : Wfas3Listener {
    /** Set at start in window mode by --debug or WFAS3_TRACE=1: the v3 trace goes to stdout. */
    @Volatile var traceToConsole = false


    data class WindowState(val kind: Int, val text: String, val qr: String?, val openedAtMs: Long, val ask: Boolean, val paired: String? = null)
    /** [from]: the address the client asks from, shown with the question. */
    data class AskState(val ask: Int, val name: String, val flags: Int, val code: String, val pin: Boolean, val from: String = "") {
        val wantsGuest: Boolean get() = (flags and W3.PAIR_GUEST) != 0
        val wantsMic: Boolean get() = (flags and W3.PAIR_MIC) != 0
    }

    sealed class ClientPairing {
        object Idle : ClientPairing()
        /** Waiting for the server: for its beacon, then for its user's answer. */
        data class Waiting(val pin: Boolean) : ClientPairing()
        /** PIN mode: compare this code with the one on the server. */
        data class Compare(val code: String) : ClientPairing()
        /** [slot]: the pair just made, so the interface can connect to it at once. */
        data class Done(val name: String, val guest: Boolean, val slot: Int = -1) : ClientPairing()
        /** [noNetwork]: nothing was sent, the port of this device could not open. */
        data class Failed(val reason: Int, val race: Boolean, val noNetwork: Boolean = false) : ClientPairing()
    }

    sealed class Notice {
        abstract val id: Long
        data class Suspect(override val id: Long, val pair: Int, val name: String) : Notice()
        data class ForgetAsked(override val id: Long, val pair: Int, val name: String) : Notice()
        data class MicRefused(override val id: Long) : Notice()
        data class Storage(override val id: Long) : Notice()
    }

    private val lock = Any()
    @Volatile private var engine: Wfas3Engine? = null
    @Volatile private var store: Wfas3FileStore? = null
    @Volatile private var server: Wfas3Server? = null
    @Volatile private var client: Wfas3Client? = null
    private var micLine: TargetDataLine? = null
    @Volatile private var micThread: Thread? = null
    private var serverMic: ((ShortArray, Int) -> Unit)? = null
    private var serverMicRate = 48000
    private var serverMicChannels = 2
    private var micResampler: Wfas3Resampler? = null
    private var noticeSeq = 0L

    val failure = MutableStateFlow<Wfas3Failure?>(null)
    val pairs = MutableStateFlow<List<Wfas3PairInfo>>(emptyList())
    val peers = MutableStateFlow<List<Wfas3Peer>>(emptyList())
    val clientState = MutableStateFlow<Wfas3ClientState>(Wfas3ClientState.Idle)
    val window = MutableStateFlow<WindowState?>(null)
    val windowFailures = MutableStateFlow(0)
    val asks = MutableStateFlow<List<AskState>>(emptyList())
    val pairing = MutableStateFlow<ClientPairing>(ClientPairing.Idle)
    val beacons = MutableStateFlow<List<Wfas3Beacon>>(emptyList())
    val notices = MutableStateFlow<List<Notice>>(emptyList())
    val protection = MutableStateFlow("")

    val running: Boolean get() = engine?.isRunning == true

    /** The settings the v3 side follows; set on every load and save of the settings. */
    @Volatile var appSettings: AppSettings = AppSettings()
        private set

    /**
     * Set by 'wfas ... --legacy-v2': this run uses WFAS v2 whatever the saved
     * setting says. Never saved.
     */
    @Volatile var legacyForThisRun = false

    val v3Enabled: Boolean get() = !legacyForThisRun && Wfas3Settings.isV3(appSettings)

    /**
     * Follow new settings: switching to v2 stops the v3 engine (no v3 port
     * stays open); a running server takes the new stream settings at once.
     */
    fun configure(app: AppSettings) {
        val before = appSettings
        appSettings = app
        if (!v3Enabled) {
            if (engine != null) shutdown()
            return
        }
        engine?.let { applyNetwork(it, app) }
        val s = server ?: return
        if (before.v3Codec != app.v3Codec || before.v3OpusKbps != app.v3OpusKbps ||
            before.v3OpusFrameSamples != app.v3OpusFrameSamples || before.v3LatencyMs != app.v3LatencyMs ||
            before.v3Sync != app.v3Sync || before.v3AcceptNew != app.v3AcceptNew) {
            val cur = s.settings
            val next = cur.copy(
                codec = if (app.v3Codec == Wfas3Settings.CODEC_OPUS) W3.CODEC_OPUS else W3.CODEC_PCM,
                opusKbps = app.v3OpusKbps.coerceIn(Wfas3Settings.OPUS_KBPS_MIN, Wfas3Settings.OPUS_KBPS_MAX),
                opusFrameSamples = app.v3OpusFrameSamples.takeIf { it in Wfas3Settings.OPUS_FRAMES } ?: 480,
                latencyMs = app.v3LatencyMs.coerceIn(20, 1000),
                sync = app.v3Sync,
                acceptNew = app.v3AcceptNew,
            )
            // The capture keeps its rate: only a change that needs another rate waits for a restart.
            if (next.captureRate == cur.captureRate) s.update(next)
        }
    }

    /** Words for the last failure of the engine, for a status line. */
    fun failureText(): String = when (failure.value) {
        Wfas3Failure.LIBRARY -> Strings.get("w3_fail_library")
        Wfas3Failure.SELFTEST -> Strings.get("w3_fail_selftest")
        Wfas3Failure.DEVICE_RECORD -> Strings.get("w3_fail_device_record")
        Wfas3Failure.STORAGE -> Strings.get("w3_fail_storage")
        Wfas3Failure.PORT_IN_USE -> Strings.get("w3_fail_port", W3.UDP_PORT)
        Wfas3Failure.NETWORK -> Strings.get("w3_fail_network")
        Wfas3Failure.IN_USE -> Strings.get("w3_fail_in_use")
        Wfas3Failure.STORE_KEY -> Strings.get("w3_fail_store_key")
        null -> Strings.get("w3_fail_network")
    }

    // -- engine ---------------------------------------------------------------

    /** Start the engine if needed. Returns null (and sets [failure]) if it cannot run. */
    fun ensure(app: AppSettings): Wfas3Engine? {
        synchronized(lock) {
            engine?.let { if (it.isRunning) return it }
            Wfas3NativeLoader.install()
            // --debug: the v3 trace (events, sessions, counters; never keys or codes).
            if ((AppDebug.enabled || traceToConsole) && wfas3.Wfas3Trace.sink == null) {
                val t0 = System.currentTimeMillis()
                wfas3.Wfas3Trace.sink = { line ->
                    val msg = "[WFAS3 +${System.currentTimeMillis() - t0}ms] $line"
                    if (AppDebug.enabled) AppDebug.log(msg) else println(msg)
                }
            }
            val st = store ?: Wfas3FileStore(Wfas3FileStore.defaultDir()).also { store = it }
            val e = Wfas3Engine(st, Wfas3Settings.deviceName(app))
            applyNetwork(e, app)
            e.addListener(this)
            try {
                if (!st.acquire()) throw Wfas3Exception(Wfas3Failure.IN_USE, "records in use")
                // The records are loaded now; the port opens only while something needs it.
                e.start(listen = false)
            } catch (x: Wfas3Exception) {
                if (x.failure != Wfas3Failure.IN_USE) st.release()
                failure.value = x.failure
                AppDebug.log("[WFAS3] engine not started: ${x.failure}")
                return null
            } catch (t: Throwable) {
                st.release()
                failure.value = Wfas3Failure.LIBRARY
                AppDebug.log("[WFAS3] engine not started: ${t.javaClass.simpleName}")
                return null
            }
            failure.value = null
            engine = e
            protection.value = st.protection
            refreshPairs()
            return e
        }
    }

    /** Follow the chosen network interface: broadcasts and multicast go there. */
    fun applyNetwork(app: AppSettings) { engine?.let { applyNetwork(it, app) } }

    private fun applyNetwork(e: Wfas3Engine, app: AppSettings) {
        val ifc = runCatching { NetworkHandler_v1.getActiveNetworkInterface(app.networkInterface) }.getOrNull()
        e.broadcastTargets = Wfas3Engine.broadcastsOf(ifc)
        e.multicastInterface = ifc
    }

    // -- the port is open only while it is needed ---------------------------------

    @Volatile private var clientWanted = false
    @Volatile private var pinSearching = false
    @Volatile private var pairingActive = false
    private val closer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wfas3-port").apply { isDaemon = true }
    }

    @Volatile private var locating = false
    // The slot the client is after; a locate running meanwhile leaves its search alone.
    @Volatile private var clientSlot = -1
    // One locate at a time: they share the broadcast targets of the engine.
    private val locateLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * While the port is wanted, look at the network every 10 s: a move between
     * an IPv4 network and an IPv6-only one reopens the port for the new link
     * (Wfas3Engine.multicastInterface). Nothing changes while the link stays.
     */
    @Volatile private var watcher: java.util.concurrent.ScheduledFuture<*>? = null

    private fun watchNetwork() {
        if (watcher != null) return
        watcher = closer.scheduleWithFixedDelay({
            val e = engine
            if (e == null || !wantsNetwork()) {
                watcher?.cancel(false)
                watcher = null
            } else if (!e.isListening) {
                // The port could not reopen on the last network: try again on this one.
                runCatching { updateNetwork() }
            } else if (!locating) {
                // Not while locating: that adds the typed address to the targets for a moment.
                runCatching { applyNetwork(e, appSettings) }
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun wantsNetwork(): Boolean =
        server != null || clientWanted || window.value != null || pinSearching || pairingActive || locating

    /**
     * Open the port when something needs it; close it a moment after the last
     * user is gone, so that closing packets (BYE) still leave.
     */
    private fun updateNetwork() {
        val e = engine ?: return
        if (wantsNetwork()) {
            // The interface as it is now: an IPv6-only network is served over IPv6.
            applyNetwork(e, appSettings)
            watchNetwork()
            if (!e.isListening) {
                try {
                    e.listen(true)
                    // The port is open again: a past network failure no longer applies.
                    if (failure.value == Wfas3Failure.NETWORK || failure.value == Wfas3Failure.PORT_IN_USE) failure.value = null
                } catch (x: Wfas3Exception) {
                    failure.value = x.failure
                } catch (_: Exception) {
                    failure.value = Wfas3Failure.NETWORK
                }
            }
        } else if (e.isListening) {
            closer.schedule({ if (!wantsNetwork()) runCatching { e.listen(false) } }, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    fun shutdown() {
        synchronized(lock) {
            stopServer()
            stopClient()
            engine?.shutdown()
            engine = null
            store?.release()
            window.value = null
            asks.value = emptyList()
            beacons.value = emptyList()
            pairing.value = ClientPairing.Idle
        }
    }

    fun refreshPairs() {
        pairs.value = engine?.pairs() ?: emptyList()
    }

    // -- server ---------------------------------------------------------------

    /**
     * Start serving. [micSink] receives the client's microphone, converted to
     * [micRate] / [micChannels], or null when the user does not route it.
     */
    fun startServer(app: AppSettings, settings: Wfas3ServerSettings, micRate: Int, micChannels: Int,
                    micSink: ((ShortArray, Int) -> Unit)?): Wfas3Server? {
        val e = ensure(app) ?: return null
        synchronized(lock) {
            stopClient()
            server?.stop()
            serverMic = micSink
            serverMicRate = micRate
            serverMicChannels = micChannels
            micResampler = null
            val s = Wfas3Server(e, settings)
            s.events = object : Wfas3Server.Events {
                override fun onPeers(peers: List<Wfas3Peer>) { this@Wfas3Desktop.peers.value = peers }
                override fun onMicrophone(session: Int, pcm: ShortArray, frames: Int, channels: Int, rate: Int, captureUs: Long, playUs: Long) {
                    val sink = serverMic ?: return
                    val r = micResampler?.takeIf { it.inRate == rate && it.inChannels == channels }
                        ?: Wfas3Resampler(rate, serverMicRate, channels, serverMicChannels).also { micResampler = it }
                    val (out, n) = r.process(pcm, 0, frames)
                    if (n > 0) sink(out, n * serverMicChannels)
                }
            }
            server = s
            updateNetwork()
            if (e.isListening) {
                s.start()
            } else {
                server = null
                return null
            }
            return s
        }
    }

    fun updateServer(settings: Wfas3ServerSettings) { server?.update(settings) }

    fun feed(pcm: ShortArray, frames: Int, channels: Int) {
        val s = server ?: return
        val now = Wfas3Engine.clockUs()
        s.feed(pcm, 0, frames, channels, now - frames * 1_000_000L / s.settings.captureRate)
    }

    fun pace() { server?.pace(Wfas3Engine.clockUs()) }

    fun stopServer() {
        synchronized(lock) {
            server?.stop()
            server = null
            serverMic = null
            peers.value = emptyList()
            if (window.value != null) closeWindow()
            updateNetwork()
        }
    }

    val serving: Boolean get() = server != null

    /**
     * A v3 session is live: this PC plays from a server, or serves a client
     * that listens. A v3 session is always encrypted and authenticated, so
     * this is what the status says instead of the v2 encryption flag.
     */
    val sessionLive: Boolean
        get() = clientState.value is Wfas3ClientState.Connected || (server != null && peers.value.any { it.streaming })

    // -- client ---------------------------------------------------------------

    fun connect(app: AppSettings, pair: Int, output: Mixer.Info?, at: Wfas3Addr?, mic: Mixer.Info?, sendMic: Boolean, latencyMs: Int,
                onPcm: ((ShortArray) -> Unit)? = null): Boolean {
        val e = ensure(app) ?: return false
        synchronized(lock) {
            stopServer()
            val c = client ?: Wfas3Client(e) { rate, ch -> Wfas3LineOutput.open(output, rate, ch) }.also {
                it.events = object : Wfas3Client.Events {
                    override fun onState(state: Wfas3ClientState) {
                        clientState.value = state
                        if (state is Wfas3ClientState.Connected) {
                            startMicIfWanted()
                            if (appSettings.v3LastPair != state.pair) lastPairChanged?.invoke(state.pair)
                        }
                        if (state !is Wfas3ClientState.Connected) stopMicCapture()
                        if (state is Wfas3ClientState.Idle || state is Wfas3ClientState.Stopped) {
                            clientWanted = false
                            updateNetwork()
                        }
                    }
                    override fun onMicrophoneRefused() { notice(Notice.MicRefused(nextId())) }
                }
                it.attach()
                client = it
            }
            c.latencyMs = latencyMs.coerceIn(20, 1000)
            c.pcmTap = onPcm?.let { f -> { pcm: ShortArray, _: Int -> f(pcm) } }
            pendingMic = if (sendMic) mic else null
            c.setMicrophone(sendMic && mic != null)
            clientWanted = true
            clientSlot = pair
            updateNetwork()
            if (!e.isListening) { clientWanted = false; return false }
            c.connect(pair, at)
            return true
        }
    }

    @Volatile private var pendingMic: Mixer.Info? = null

    /** Called with the pair slot of a server the client reached, to remember it. */
    @Volatile var lastPairChanged: ((Int) -> Unit)? = null

    /**
     * Ask every paired server to answer and report where each one did within
     * [timeoutMs]. [extra] receives the probes too: an address the user typed,
     * for a server that broadcasts may not reach. Only paired servers answer.
     */
    fun locate(app: AppSettings, timeoutMs: Long, extra: java.net.InetAddress? = null): Map<Int, Wfas3Addr> {
        locateLock.lock()
        try { return locateLocked(app, timeoutMs, extra) } finally { locateLock.unlock() }
    }

    private fun locateLocked(app: AppSettings, timeoutMs: Long, extra: java.net.InetAddress?): Map<Int, Wfas3Addr> {
        val e = ensure(app) ?: return emptyMap()
        val found = java.util.concurrent.ConcurrentHashMap<Int, Wfas3Addr>()
        val l = object : Wfas3Listener { override fun onFound(pair: Int, addr: Wfas3Addr) { found[pair] = addr } }
        val before = e.broadcastTargets
        val slots = e.pairs().filterNot { it.suspect }.map { it.slot }
        if (slots.isEmpty()) return emptyMap()
        locating = true
        e.addListener(l)
        try {
            // The port first (it refreshes the interface and its targets), then the typed address.
            updateNetwork()
            if (extra != null) {
                e.broadcastTargets = e.broadcastTargets.ifEmpty { listOf(java.net.InetAddress.getByName("255.255.255.255")) } + extra
            }
            if (!e.isListening) return emptyMap()
            slots.forEach { e.find(it, true) }
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end && found.size < slots.size) Thread.sleep(50)
            // A connection started meanwhile keeps searching for its own server.
            slots.forEach { if (!(clientWanted && it == clientSlot)) e.find(it, false) }
        } finally {
            e.removeListener(l)
            e.broadcastTargets = before
            locating = false
            updateNetwork()
        }
        return HashMap(found)
    }

    fun disconnect() {
        client?.disconnect()
        stopMicCapture()
        clientWanted = false
        updateNetwork()
    }

    fun stopClient() {
        synchronized(lock) {
            client?.detach()
            client = null
            stopMicCapture()
            clientState.value = Wfas3ClientState.Idle
            clientWanted = false
            updateNetwork()
        }
    }

    fun setMicrophone(on: Boolean, mic: Mixer.Info?) {
        pendingMic = if (on) mic else null
        client?.setMicrophone(on && mic != null)
        if (!on) stopMicCapture()
    }

    private fun startMicIfWanted() {
        val m = pendingMic ?: return
        val c = client ?: return
        if (micThread != null) return
        val t = Thread({
            var line: TargetDataLine? = null
            try {
                // 48 kHz mono is what the v3 microphone stream carries; a device
                // that cannot give it is read at its own format and converted.
                val mixer = AudioSystem.getMixer(m)
                val candidates = listOf(AudioFormat(48000f, 16, 1, true, false), AudioFormat(48000f, 16, 2, true, false),
                    AudioFormat(44100f, 16, 1, true, false), AudioFormat(44100f, 16, 2, true, false))
                val fmt = candidates.firstOrNull { mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, it)) } ?: return@Thread
                line = mixer.getLine(DataLine.Info(TargetDataLine::class.java, fmt)) as TargetDataLine
                line.open(fmt, fmt.sampleRate.toInt() / 1000 * 40 * fmt.channels * 2)
                line.start()
                micLine = line
                val ch = fmt.channels
                val rate = fmt.sampleRate.toInt()
                val frames = rate / 100
                val bytes = ByteArray(frames * ch * 2)
                val pcm = ShortArray(frames * ch)
                val rs = Wfas3Resampler(rate, 48000, ch, 1)
                while (!Thread.currentThread().isInterrupted) {
                    val n = line.read(bytes, 0, bytes.size)
                    if (n <= 0) continue
                    val f = n / (2 * ch)
                    for (i in 0 until f * ch) pcm[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                    if (NetworkHandler_v1.isMicMuted.value) java.util.Arrays.fill(pcm, 0, f * ch, 0)
                    val (out, outFrames) = rs.process(pcm, 0, f)
                    val now = Wfas3Engine.clockUs()
                    c.feedMic(out, 0, outFrames, 1, now - outFrames * 1_000_000L / 48000)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                AppDebug.log("[WFAS3] microphone capture stopped: ${t.javaClass.simpleName}")
            } finally {
                runCatching { line?.stop() }
                runCatching { line?.close() }
            }
        }, "wfas3-mic")
        t.isDaemon = true
        micThread = t
        t.start()
    }

    private fun stopMicCapture() {
        val t = micThread ?: return
        micThread = null
        t.interrupt()
        runCatching { micLine?.stop() }
        runCatching { micLine?.close() }
        micLine = null
    }

    // -- pairing --------------------------------------------------------------

    fun openWindow(app: AppSettings, kind: Int, ask: Boolean, allowMic: Boolean): Boolean {
        val e = ensure(app) ?: return false
        windowFailures.value = 0
        val perms = W3.PERM_RECEIVE or (if (allowMic) W3.PERM_MICROPHONE else 0)
        // The window needs the port for its beacons: hold it open first.
        window.value = WindowState(kind, "", null, System.currentTimeMillis(), ask)
        val code = try {
            updateNetwork()
            if (e.isListening) e.openWindow(kind, ask, perms) else null
        } catch (_: Exception) {
            null
        }
        // Never a window left waiting for a code that will not come.
        if (code == null) { window.value = null; runCatching { updateNetwork() }; return false }
        window.value = WindowState(kind, code.text, code.qr, System.currentTimeMillis(), ask)
        return true
    }

    fun closeWindow() {
        engine?.closeWindow()
        window.value = null
        asks.value = emptyList()
        updateNetwork()
    }

    fun answer(a: AskState, yes: Boolean, allowMic: Boolean, guest: Boolean) {
        val perms = W3.PERM_RECEIVE or (if (allowMic && a.wantsMic) W3.PERM_MICROPHONE else 0)
        engine?.answer(a.ask, yes, perms, guest || a.wantsGuest)
        asks.value = asks.value.filterNot { it.ask == a.ask }
    }

    fun codeValid(text: String): Boolean {
        Wfas3NativeLoader.install()
        return Wfas3Engine.codeValid(text)
    }

    /**
     * Open the port for a client pairing. If it cannot open nothing would be
     * sent: the attempt ends at once and says why, instead of waiting.
     */
    private fun portReady(e: Wfas3Engine): Boolean {
        runCatching { updateNetwork() }
        if (e.isListening) return true
        pairingActive = false
        runCatching { updateNetwork() }
        pairing.value = ClientPairing.Failed(W3.PAIRF_PROTOCOL, race = false, noNetwork = true)
        return false
    }

    // A pairing from this PC is made to listen: the server gets no permission
    // to receive this computer's audio (see Wfas3Engine.pairWithCode).
    fun pairWithCode(app: AppSettings, code: String, guest: Boolean, mic: Boolean, replace: Int = -1): Boolean {
        val e = ensure(app) ?: return false
        pairing.value = ClientPairing.Waiting(pin = false)
        pairingActive = true
        if (!portReady(e)) return false
        val st = e.pairWithCode(code.trim(), guest, mic, replace)
        if (st != W3.OK) {
            pairingActive = false
            updateNetwork()
            pairing.value = ClientPairing.Failed(if (st == W3.E_ARG) W3.PAIRF_WRONG else W3.PAIRF_PROTOCOL, false)
            return false
        }
        return true
    }

    fun startPinSearch(app: AppSettings): Boolean {
        val e = ensure(app) ?: return false
        beacons.value = emptyList()
        pinSearching = true
        updateNetwork()
        return e.beacons(true) == W3.OK
    }

    fun stopPinSearch() {
        engine?.beacons(false)
        beacons.value = emptyList()
        pinSearching = false
        updateNetwork()
    }

    fun pairWithPin(b: Wfas3Beacon, pin: String, guest: Boolean, mic: Boolean, replace: Int = -1): Boolean {
        val e = engine ?: return false
        pairing.value = ClientPairing.Waiting(pin = true)
        pairingActive = true
        if (!portReady(e)) return false
        val st = e.pairWithPin(b, pin, guest, mic, replace)
        if (st != W3.OK) {
            pairingActive = false
            updateNetwork()
            pairing.value = ClientPairing.Failed(W3.PAIRF_WRONG, false)
            return false
        }
        return true
    }

    fun confirmCode(same: Boolean) { engine?.confirm(same) }

    fun cancelPairing() {
        engine?.cancelPairing()
        pairingActive = false
        stopPinSearch()
        pairing.value = ClientPairing.Idle
    }

    fun resetPairing() { pairing.value = ClientPairing.Idle }

    fun removePair(slot: Int) {
        engine?.removePair(slot)
        refreshPairs()
    }

    /**
     * Erase every v3 key and pair on this device. Returns false, and erases
     * nothing, while another process of this app is using them.
     */
    fun eraseAll(): Boolean {
        synchronized(lock) {
            shutdown()
            val st = store ?: Wfas3FileStore(Wfas3FileStore.defaultDir())
            val mine = runCatching { st.acquire() }.getOrDefault(false)
            if (!mine) { failure.value = Wfas3Failure.IN_USE; return false }
            val ok = runCatching { st.eraseAll() }.isSuccess
            st.release()
            store = null
            failure.value = null
            pairs.value = emptyList()
            notices.value = emptyList()
            return ok
        }
    }

    fun dismiss(n: Notice) { notices.value = notices.value.filterNot { it.id == n.id } }

    private fun nextId(): Long = synchronized(lock) { ++noticeSeq }

    private fun notice(n: Notice) { notices.value = notices.value + n }

    private fun nameOf(pair: Int): String = pairs.value.firstOrNull { it.slot == pair }?.name ?: ""

    // -- engine events (event thread) -----------------------------------------

    override fun onPairAsk(ask: Int, name: String, flags: Int, code: String, from: Wfas3Addr) {
        val w = window.value
        asks.value = asks.value.filterNot { it.ask == ask } + AskState(ask, name, flags, code, w?.kind == W3.PAIR_PIN, from.hostText())
    }

    override fun onPaired(pair: Int, name: String, flags: Int) {
        refreshPairs()
        val w = window.value
        if (w != null) {
            window.value = w.copy(paired = name)
            asks.value = emptyList()
            // One pairing per window (specification 13.2).
            engine?.closeWindow()
        } else {
            pairing.value = ClientPairing.Done(name, (flags and W3.PAIR_GUEST) != 0, pair)
            engine?.beacons(false)
            beacons.value = emptyList()
            pinSearching = false
            pairingActive = false
            updateNetwork()
        }
    }

    /** Both modes reach this step; only in PIN mode do the users compare the codes. */
    override fun onPairCode(code: String) {
        val p = pairing.value
        if (p is ClientPairing.Waiting && p.pin) pairing.value = ClientPairing.Compare(code)
    }

    override fun onPairFailed(reason: Int, race: Boolean) {
        if (window.value != null) {
            windowFailures.value = engine?.windowFailed()?.coerceAtLeast(0) ?: windowFailures.value
            return
        }
        pairingActive = false
        updateNetwork()
        if (reason == W3.PAIRF_CANCELLED && !race) { pairing.value = ClientPairing.Idle; return }
        pairing.value = ClientPairing.Failed(reason, race)
    }

    override fun onWindowClosed() {
        val w = window.value ?: return
        if (w.paired == null) window.value = null
        asks.value = emptyList()
        if (window.value == null) updateNetwork()
    }

    /** The user closed the window dialog after a pairing: release the port if unused. */
    fun windowDismissed() {
        window.value = null
        asks.value = emptyList()
        updateNetwork()
    }

    /** When each listed beacon was last heard: a window sends one every second. */
    private val beaconHeard = HashMap<String, Long>()
    private const val BEACON_FRESH_MS = 4_000L

    override fun onBeacon(beacon: Wfas3Beacon) {
        // At most 8 names, as the specification asks. A server stays listed
        // while it is heard; one not heard for 4 s leaves, so that the list
        // follows the windows that are open and old or forged entries do not
        // keep a real server out for good.
        val now = System.currentTimeMillis()
        synchronized(beaconHeard) {
            val cur = fresh(now)
            val known = cur.indexOfFirst { it.key == beacon.key }
            when {
                known >= 0 -> { beaconHeard[beacon.key] = now; beacons.value = cur.toMutableList().also { it[known] = beacon } }
                cur.size < 8 -> { beaconHeard[beacon.key] = now; beacons.value = cur + beacon }
                else -> beacons.value = cur
            }
        }
    }

    /** Drop the beacons not heard lately; the panel calls it every second. */
    fun pruneBeacons() {
        synchronized(beaconHeard) {
            val cur = fresh(System.currentTimeMillis())
            if (cur.size != beacons.value.size) beacons.value = cur
        }
    }

    private fun fresh(now: Long): List<Wfas3Beacon> {
        val cur = beacons.value.filter { now - (beaconHeard[it.key] ?: 0L) <= BEACON_FRESH_MS }
        beaconHeard.keys.retainAll(cur.map { it.key }.toSet())
        return cur
    }

    override fun onSuspect(pair: Int) {
        refreshPairs()
        notice(Notice.Suspect(nextId(), pair, nameOf(pair)))
    }

    override fun onForgetAsked(pair: Int) {
        notice(Notice.ForgetAsked(nextId(), pair, nameOf(pair)))
    }

    override fun onPairsChanged() = refreshPairs()

    override fun onFatal(failure: Wfas3Failure) {
        this.failure.value = failure
        if (failure == Wfas3Failure.STORAGE) notice(Notice.Storage(nextId()))
    }

    fun pairsFlow(): StateFlow<List<Wfas3PairInfo>> = pairs
}
