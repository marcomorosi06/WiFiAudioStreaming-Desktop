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

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

object SnapcastMessageType {
    const val BASE = 0
    const val CODEC_HEADER = 1
    const val WIRE_CHUNK = 2
    const val SERVER_SETTINGS = 3
    const val TIME = 4
    const val HELLO = 5
    const val STREAM_TAGS = 6
    const val CLIENT_INFO = 7
    const val ERROR = 8
}

object SnapcastErrorCode {
    const val AUTH_FAILED = 1
    const val INTERNAL = 2
}

data class SnapcastTv(val sec: Int, val usec: Int) {

    fun toMicros(): Long = sec.toLong() * 1_000_000L + usec.toLong()

    companion object {

        fun fromMicros(micros: Long): SnapcastTv {
            var sec = micros / 1_000_000L
            var usec = micros % 1_000_000L
            if (usec < 0) {
                usec += 1_000_000L
                sec -= 1L
            }
            return SnapcastTv(sec.toInt(), usec.toInt())
        }
    }
}

data class SnapcastHeader(
    val type: Int,
    val id: Int,
    val refersTo: Int,
    val sent: SnapcastTv,
    val received: SnapcastTv,
    val size: Int
)

data class SnapcastFrame(val header: SnapcastHeader, val payload: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is SnapcastFrame && header == other.header && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()
}

object SnapcastWire {

    const val BASE_SIZE = 26
    const val STREAM_PROTOCOL_VERSION = 2
    const val CONTROL_PROTOCOL_VERSION = 1
    const val MAX_PAYLOAD_SIZE = 1_000_000

    private fun putU16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putI32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun getU16(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)

    private fun getI32(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or
            ((src[offset + 1].toInt() and 0xFF) shl 8) or
            ((src[offset + 2].toInt() and 0xFF) shl 16) or
            ((src[offset + 3].toInt() and 0xFF) shl 24)

    fun frame(
        type: Int,
        id: Int,
        refersTo: Int,
        sent: SnapcastTv,
        received: SnapcastTv,
        payload: ByteArray
    ): ByteArray {
        val out = ByteArray(BASE_SIZE + payload.size)
        putU16(out, 0, type)
        putU16(out, 2, id)
        putU16(out, 4, refersTo)
        putI32(out, 6, sent.sec)
        putI32(out, 10, sent.usec)
        putI32(out, 14, received.sec)
        putI32(out, 18, received.usec)
        putI32(out, 22, payload.size)
        System.arraycopy(payload, 0, out, BASE_SIZE, payload.size)
        return out
    }

    fun parseHeader(src: ByteArray): SnapcastHeader = SnapcastHeader(
        type = getU16(src, 0),
        id = getU16(src, 2),
        refersTo = getU16(src, 4),
        sent = SnapcastTv(getI32(src, 6), getI32(src, 10)),
        received = SnapcastTv(getI32(src, 14), getI32(src, 18)),
        size = getI32(src, 22)
    )

    fun readFrame(stream: InputStream): SnapcastFrame {
        val data = DataInputStream(stream)
        val headerBytes = ByteArray(BASE_SIZE)
        data.readFully(headerBytes)
        val header = parseHeader(headerBytes)
        if (header.size < 0 || header.size > MAX_PAYLOAD_SIZE) {
            throw EOFException("snapcast payload size out of range: ${header.size}")
        }
        val payload = ByteArray(header.size)
        if (header.size > 0) data.readFully(payload)
        return SnapcastFrame(header, payload)
    }

    fun stringPayload(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + bytes.size)
        putI32(out, 0, bytes.size)
        System.arraycopy(bytes, 0, out, 4, bytes.size)
        return out
    }

    fun readString(payload: ByteArray, offset: Int = 0): String {
        if (payload.size < offset + 4) return ""
        val length = getI32(payload, offset)
        if (length < 0 || offset + 4 + length > payload.size) return ""
        return String(payload, offset + 4, length, Charsets.UTF_8)
    }

    fun codecHeaderPayload(codec: String, header: ByteArray): ByteArray {
        val codecBytes = codec.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + codecBytes.size + 4 + header.size)
        putI32(out, 0, codecBytes.size)
        System.arraycopy(codecBytes, 0, out, 4, codecBytes.size)
        putI32(out, 4 + codecBytes.size, header.size)
        System.arraycopy(header, 0, out, 8 + codecBytes.size, header.size)
        return out
    }

    fun wireChunkPayload(timestamp: SnapcastTv, audio: ByteArray, audioOffset: Int, audioLength: Int): ByteArray {
        val out = ByteArray(12 + audioLength)
        putI32(out, 0, timestamp.sec)
        putI32(out, 4, timestamp.usec)
        putI32(out, 8, audioLength)
        System.arraycopy(audio, audioOffset, out, 12, audioLength)
        return out
    }

    fun timePayload(latency: SnapcastTv): ByteArray {
        val out = ByteArray(8)
        putI32(out, 0, latency.sec)
        putI32(out, 4, latency.usec)
        return out
    }

    fun parseTimePayload(payload: ByteArray): SnapcastTv =
        if (payload.size < 8) SnapcastTv(0, 0) else SnapcastTv(getI32(payload, 0), getI32(payload, 4))

    fun errorPayload(code: Int, error: String, details: String): ByteArray {
        val errorBytes = error.toByteArray(Charsets.UTF_8)
        val detailBytes = details.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + 4 + errorBytes.size + 4 + detailBytes.size)
        putI32(out, 0, code)
        putI32(out, 4, errorBytes.size)
        System.arraycopy(errorBytes, 0, out, 8, errorBytes.size)
        putI32(out, 8 + errorBytes.size, detailBytes.size)
        System.arraycopy(detailBytes, 0, out, 12 + errorBytes.size, detailBytes.size)
        return out
    }

    fun wavHeader(sampleRate: Int, bitDepth: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * bitDepth / 8
        val blockAlign = channels * bitDepth / 8
        val out = ByteArray(44)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        putI32(out, 4, 36)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(out, 12)
        putI32(out, 16, 16)
        putU16(out, 20, 1)
        putU16(out, 22, channels)
        putI32(out, 24, sampleRate)
        putI32(out, 28, byteRate)
        putU16(out, 32, blockAlign)
        putU16(out, 34, bitDepth)
        "data".toByteArray(Charsets.US_ASCII).copyInto(out, 36)
        putI32(out, 40, 0)
        return out
    }

    fun opusHeader(sampleRate: Int, bitDepth: Int, channels: Int): ByteArray {
        val out = ByteArray(12)
        putI32(out, 0, 0x4F505553)
        putI32(out, 4, sampleRate)
        putU16(out, 8, bitDepth)
        putU16(out, 10, channels)
        return out
    }

    fun flacHeader(streamInfo: ByteArray): ByteArray {
        if (streamInfo.size < 34) return ByteArray(0)
        val out = ByteArray(4 + 4 + 34)
        "fLaC".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[4] = 0x80.toByte()
        out[5] = 0
        out[6] = 0
        out[7] = 34
        System.arraycopy(streamInfo, 0, out, 8, 34)
        return out
    }
}

class SnapcastSteadyClock {

    private val baseNanos: Long = System.nanoTime()

    fun nowMicros(): Long = (System.nanoTime() - baseNanos) / 1000L

    fun now(): SnapcastTv = SnapcastTv.fromMicros(nowMicros())
}

class SnapcastStreamClock(
    private val clock: SnapcastSteadyClock,
    private val sampleRate: Int
) {

    private var anchorMicros: Long = 0L
    private var framesEmitted: Long = 0L
    private var started = false

    @Volatile
    var lastDriftMicros: Long = 0L
        private set

    @Synchronized
    fun reset() {
        started = false
        framesEmitted = 0L
        anchorMicros = 0L
        lastDriftMicros = 0L
    }

    @Synchronized
    fun timestampFor(frames: Int): SnapcastTv {
        val nowMicros = clock.nowMicros()
        if (!started) {
            started = true
            anchorMicros = nowMicros
            framesEmitted = 0L
        }
        val predicted = anchorMicros + framesEmitted * 1_000_000L / sampleRate
        val error = nowMicros - predicted
        lastDriftMicros = error
        when {
            error > HARD_RESYNC_MICROS || error < -HARD_RESYNC_MICROS -> {
                anchorMicros = nowMicros
                framesEmitted = 0L
            }
            error > 0 -> anchorMicros += minOf(error, MAX_SLEW_MICROS)
            error < 0 -> anchorMicros -= minOf(-error, MAX_SLEW_MICROS)
        }
        val timestamp = anchorMicros + framesEmitted * 1_000_000L / sampleRate
        framesEmitted += frames.toLong()
        return SnapcastTv.fromMicros(timestamp)
    }

    companion object {
        private const val HARD_RESYNC_MICROS = 500_000L
        private const val MAX_SLEW_MICROS = 20L
    }
}
