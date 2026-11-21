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

package wfas3

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Constants of the core (core/wfas.h). */
object W3 {
    const val UDP_PORT = 14114
    const val GROUP_PORT = 14115
    const val MAX_DATAGRAM = 1400
    const val MAX_AUDIO_PAYLOAD = 1344
    const val MAX_MCAST_PAYLOAD = 1213
    const val MAX_PAIRS = 64
    const val MAX_SESSIONS = 8
    const val DEVICE_RECORD_LEN = 40
    const val PAIR_RECORD_LEN = 420
    const val NAME_LEN = 32
    const val NONE = -1                       // 0xFFFFFFFF as a signed Int

    const val OK = 0
    const val E_ARG = 1
    const val E_DROP = 2
    const val E_AUTH = 3
    const val E_ENTROPY = 4
    const val E_SELFTEST = 5
    const val E_STATE = 6
    const val E_FULL = 7
    const val E_PERM = 8

    const val EV_STORE_PAIR = 0x0001
    const val EV_STORE_DEVICE = 0x0002
    const val EV_SESSION_UP = 0x0004
    const val EV_SESSION_DOWN = 0x0008
    const val EV_SUSPECT = 0x0010
    const val EV_ATTEMPT_FAILED = 0x0020
    const val EV_REFUSED = 0x0040
    const val EV_ATTEMPT_YIELDED = 0x0080
    const val EV_AUDIO = 0x0100
    const val EV_STREAM_START = 0x0200
    const val EV_STREAM_STOP = 0x0400
    const val EV_STREAM_PARAMS = 0x0800
    const val EV_PEER_SILENT = 0x1000
    const val EV_PEER_BACK = 0x2000
    const val EV_PAIR_ERASED = 0x4000
    const val EV_FORGET_ASKED = 0x8000
    const val EV_REKEYED = 0x10000
    const val EV_PATH_CHANGED = 0x20000
    const val EV_FOUND = 0x40000
    const val EV_NOT_FOUND = 0x80000
    const val EV_BEACON = 0x100000
    const val EV_PAIR_ASK = 0x200000
    const val EV_PAIR_CODE = 0x400000
    const val EV_PAIRED = 0x800000
    const val EV_PAIR_FAILED = 0x1000000
    const val EV_PAIR_RACE = 0x2000000
    const val EV_WINDOW_CLOSED = 0x4000000
    const val EV_GROUP_JOIN = 0x8000000
    const val EV_GROUP_LEAVE = 0x10000000

    const val BYE_NORMAL = 0
    const val BYE_PREEMPTED = 1
    const val BYE_REVOKED = 2
    const val BYE_PROTOCOL = 3
    const val BYE_GUEST_ENDED = 4
    const val BYE_REKEY_FAILED = 5
    const val DOWN_LOCAL = 0x10
    const val DOWN_TIMEOUT = 0x11
    const val DOWN_PROTOCOL = 0x12
    const val DOWN_REKEY = 0x13
    const val DOWN_REMOVED = 0x14
    const val DOWN_LIMIT = 0x15

    const val PERM_RECEIVE = 0x01
    const val PERM_MICROPHONE = 0x02
    const val PF_GUEST = 0x01
    const val PF_SUSPECT = 0x02

    const val PAIR_CODE = 1
    const val PAIR_LABEL = 2
    const val PAIR_PIN = 3
    const val PAIR_GUEST = 0x01
    const val PAIR_MIC = 0x02

    const val PAIRF_TIMEOUT = 1
    const val PAIRF_WRONG = 2
    const val PAIRF_REFUSED = 3
    const val PAIRF_KEY = 4
    const val PAIRF_USED = 5
    const val PAIRF_FULL = 6
    const val PAIRF_PROTOCOL = 7
    const val PAIRF_CANCELLED = 8
    const val PAIRF_CODE = 9

    const val CF_SOURCE = 0x01
    const val CF_SYNC = 0x02
    const val CF_MULTICAST = 0x04
    const val CF_MICROPHONE = 0x08
    const val CODEC_PCM = 1
    const val CODEC_OPUS = 2
    const val ST_ACCEPTED = 0
    const val ST_BUSY = 1
    const val ST_NOT_PERMITTED = 2
    const val ST_UNSUPPORTED = 3

    const val STREAM_MAIN = 0
    const val STREAM_MIC = 1
}

/**
 * StreamConfig (specification 8.3). [flags] uses the CF_* bits; bit0 is
 * "the sender of this payload is the audio source", so the same stream reads
 * differently on the two sides.
 */
data class Wfas3Config(
    val flags: Int,
    val codec: Int,
    val channels: Int,
    val bits: Int,
    val sampleRate: Int,
    val frameSamples: Int,
    val payloadBytes: Int,
    val latencyMs: Int,
) {
    val sync: Boolean get() = (flags and W3.CF_SYNC) != 0
    val multicast: Boolean get() = (flags and W3.CF_MULTICAST) != 0
    val microphone: Boolean get() = (flags and W3.CF_MICROPHONE) != 0
    val opus: Boolean get() = codec == W3.CODEC_OPUS

    /** Duration of one packet in microseconds. */
    val frameUs: Long get() = if (sampleRate > 0) frameSamples * 1_000_000L / sampleRate else 0L

    fun toArray(): IntArray = intArrayOf(flags, codec, channels, bits, sampleRate, frameSamples, payloadBytes, latencyMs)

    fun withFlags(f: Int): Wfas3Config = copy(flags = f and 0x0F)

    /** The same rules as the core (specification 8.3), to refuse a bad value early. */
    fun isValid(): Boolean {
        if ((flags and 0xF0) != 0) return false
        if (latencyMs !in 0..65535) return false
        if (payloadBytes !in 1..W3.MAX_AUDIO_PAYLOAD) return false
        if (multicast && payloadBytes > W3.MAX_MCAST_PAYLOAD) return false
        return when (codec) {
            W3.CODEC_PCM -> {
                if (channels !in 1..8 || (bits != 16 && bits != 24)) return false
                if (sampleRate !in PCM_RATES) return false
                val bytesPerFrame = channels * bits / 8
                val n = minOf(sampleRate / 200, W3.MAX_AUDIO_PAYLOAD / bytesPerFrame)
                frameSamples == n && payloadBytes == n * bytesPerFrame
            }
            W3.CODEC_OPUS -> {
                sampleRate == 48000 && channels in 1..8 && bits == 16 && frameSamples in OPUS_FRAMES
            }
            else -> false
        }
    }

    companion object {
        val PCM_RATES = setOf(44100, 48000, 88200, 96000, 176400, 192000)
        val OPUS_FRAMES = setOf(120, 240, 480, 960)

        fun read(b: ByteBuffer, off: Int): Wfas3Config = Wfas3Config(
            flags = b.get(off).toInt() and 0xFF,
            codec = b.get(off + 1).toInt() and 0xFF,
            channels = b.get(off + 2).toInt() and 0xFF,
            bits = b.get(off + 3).toInt() and 0xFF,
            sampleRate = b.getInt(off + 4),
            frameSamples = b.getInt(off + 8),
            payloadBytes = b.getInt(off + 12),
            latencyMs = b.getInt(off + 16),
        )

        /** A valid PCM configuration: frame and payload follow from the rest. */
        fun pcm(flags: Int, sampleRate: Int, channels: Int, bits: Int, latencyMs: Int): Wfas3Config {
            val bytesPerFrame = channels * bits / 8
            val n = minOf(sampleRate / 200, W3.MAX_AUDIO_PAYLOAD / bytesPerFrame)
            return Wfas3Config(flags, W3.CODEC_PCM, channels, bits, sampleRate, n, n * bytesPerFrame, latencyMs)
        }

        /** A valid Opus configuration at [kbps] with frames of [frameSamples] (48 kHz). */
        fun opus(flags: Int, channels: Int, kbps: Int, frameSamples: Int, latencyMs: Int): Wfas3Config {
            val payload = (kbps.toLong() * 1000L * frameSamples / (8L * 48000L)).toInt()
                .coerceIn(1, if ((flags and W3.CF_MULTICAST) != 0) W3.MAX_MCAST_PAYLOAD else W3.MAX_AUDIO_PAYLOAD)
            return Wfas3Config(flags, W3.CODEC_OPUS, channels, 16, 48000, frameSamples, payload, latencyMs)
        }
    }
}

/** A network address as the core sees it: 16 bytes (IPv4 mapped) and a port. */
class Wfas3Addr(val ip: ByteArray, val port: Int) {
    fun toInet(): InetAddress = InetAddress.getByAddress(ip)
    fun isV4Mapped(): Boolean {
        for (i in 0 until 10) if (ip[i] != 0.toByte()) return false
        return ip[10] == 0xFF.toByte() && ip[11] == 0xFF.toByte()
    }
    fun v4(): ByteArray = ip.copyOfRange(12, 16)
    fun isZero(): Boolean = ip.all { it == 0.toByte() } && port == 0

    override fun equals(other: Any?): Boolean = other is Wfas3Addr && other.port == port && other.ip.contentEquals(ip)
    override fun hashCode(): Int = ip.contentHashCode() * 31 + port

    /** For the user interface only, never for logs. */
    /** Whether this is the host [a] (the bytes; an IPv6 zone such as %wlan0 is not compared). */
    fun sameHost(a: InetAddress?): Boolean = a != null && of(a, port).ip.contentEquals(ip)

    fun hostText(): String = try {
        if (isV4Mapped()) InetAddress.getByAddress(v4()).hostAddress ?: "" else toInet().hostAddress ?: ""
    } catch (_: Exception) { "" }

    companion object {
        fun read(b: ByteBuffer, off: Int): Wfas3Addr {
            val ip = ByteArray(16)
            for (i in 0 until 16) ip[i] = b.get(off + i)
            return Wfas3Addr(ip, b.getInt(off + 16) and 0xFFFF)
        }

        fun of(a: InetAddress, port: Int): Wfas3Addr {
            val raw = a.address
            val ip = ByteArray(16)
            if (a is Inet4Address || raw.size == 4) {
                ip[10] = 0xFF.toByte(); ip[11] = 0xFF.toByte()
                System.arraycopy(raw, 0, ip, 12, 4)
            } else {
                System.arraycopy(raw, 0, ip, 0, 16)
            }
            return Wfas3Addr(ip, port)
        }
    }
}

/**
 * A PIN-mode PairBeacon as reported by the core (nothing in it is verified).
 * [raw] goes back to the core unchanged when the user chooses it.
 */
class Wfas3Beacon(val raw: ByteArray, val name: String, val addr: Wfas3Addr) {
    val key: String get() = raw.copyOfRange(24, 40).joinToString("") { "%02x".format(it) }
}

/** What the user interface may know of a pair. Nothing secret. */
data class Wfas3PairInfo(
    val slot: Int,
    val name: String,
    val perms: Int,
    val flags: Int,
) {
    val guest: Boolean get() = (flags and W3.PF_GUEST) != 0
    val suspect: Boolean get() = (flags and W3.PF_SUSPECT) != 0
    val mayReceive: Boolean get() = (perms and W3.PERM_RECEIVE) != 0
    val microphone: Boolean get() = (perms and W3.PERM_MICROPHONE) != 0
}

/** Counters of a session for the status screen (wfas3_session_info). */
data class Wfas3SessionInfo(
    val session: Int,
    val pair: Int,
    val initiator: Boolean,
    val source: Boolean,
    val microphone: Boolean,
    val synchronised: Boolean,
    val txOn: Boolean,
    val txStarted: Boolean,
    val peerReceived: Long,
    val peerLost: Long,
    val peerJitterUs: Long,
    val peerBufferMs: Long,
    val rxOn: Boolean,
    val rxReceived: Long,
    val rxLost: Long,
    val rxJitterUs: Long,
    val timeValid: Boolean,
    val offsetUs: Long,
    val delayMs: Long,
    val rekeys: Long,
    val silent: Boolean,
)

/** One output of the core, parsed from the shared buffer. */
internal class Wfas3Out(private val buf: ByteArray) {
    private val bb: ByteBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

    val status: Int get() = bb.getInt(0)
    val events: Int get() = bb.getInt(4)
    val pair: Int get() = bb.getInt(8)
    val session: Int get() = bb.getInt(12)
    val ostatus: Int get() = bb.getInt(16)
    val broadcast: Boolean get() = bb.getInt(20) != 0
    val streamOn: Boolean get() = bb.getInt(24) != 0
    val stream: Int get() = bb.getInt(28)
    val epoch: Int get() = bb.getInt(32)
    val code: Int get() = bb.getInt(36)
    val ask: Int get() = bb.getInt(40)
    val pflags: Int get() = bb.getInt(44)
    val len: Int get() = bb.getInt(48)
    val audioLen: Int get() = bb.getInt(52)
    val samplePos: Long get() = bb.getLong(56)
    val captureUs: Long get() = bb.getLong(64)
    val playoutUs: Long get() = bb.getLong(72)
    val to: Wfas3Addr get() = Wfas3Addr.read(bb, 80)
    val addr: Wfas3Addr get() = Wfas3Addr.read(bb, 100)
    val config: Wfas3Config get() = Wfas3Config.read(bb, 120)
    fun rawName(): ByteArray = buf.copyOfRange(140, 172)
    fun rawBeacon(): ByteArray = buf.copyOfRange(172, 172 + 72)

    val dgramOffset: Int = DGRAM
    val audioOffset: Int = AUDIO
    val bytes: ByteArray get() = buf

    fun isIdle(): Boolean = status == W3.OK && len == 0 && events == 0

    companion object {
        const val SIZE = 256 + 1400 + 1344
        const val DGRAM = 256
        const val AUDIO = 256 + 1400
    }
}
