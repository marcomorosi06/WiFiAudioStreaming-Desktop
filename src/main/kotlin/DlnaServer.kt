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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object DlnaCodecSupport {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Set<DlnaCodec>>()

    fun available(sampleRate: Int, channels: Int): Set<DlnaCodec> =
        cache.getOrPut("$sampleRate/$channels") {
            val out = LinkedHashSet<DlnaCodec>()
            out.add(DlnaCodec.LPCM)
            out.add(DlnaCodec.WAV)
            if (encoderUsable(avcodec.AV_CODEC_ID_MP3, sampleRate, channels)) out.add(DlnaCodec.MP3)
            if (encoderUsable(avcodec.AV_CODEC_ID_AAC, sampleRate, channels)) out.add(DlnaCodec.ADTS)
            DlnaDiagnostics.record(
                "codec",
                "encoders usable at ${sampleRate}Hz/${channels}ch: ${out.joinToString(", ") { it.id }}"
            )
            out
        }

    private fun encoderUsable(codecId: Int, sampleRate: Int, channels: Int): Boolean = runCatching {
        val probe = FfmpegAudioEncoder.create(codecId, sampleRate, channels, 320_000) ?: return false
        probe.release()
        true
    }.getOrDefault(false)
}

private class FfmpegAudioEncoder private constructor(
    private val context: AVCodecContext,
    private val frame: AVFrame,
    private val packet: org.bytedeco.ffmpeg.avcodec.AVPacket,
    private val sampleFormat: Int,
    val frameSize: Int,
    private val channels: Int
) {
    private val samplesPerFrame = frameSize * channels
    private val accumulator = ShortArray(samplesPerFrame)
    private var accumulated = 0
    private var pts = 0L

    fun feed(pcmLittleEndian: ByteArray, onPacket: (ByteArray) -> Unit) {
        val input = ByteBuffer.wrap(pcmLittleEndian).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (input.hasRemaining()) {
            accumulator[accumulated++] = input.get()
            if (accumulated >= samplesPerFrame) {
                accumulated = 0
                encodeFrame(onPacket)
            }
        }
    }

    private fun encodeFrame(onPacket: (ByteArray) -> Unit) {
        avutil.av_frame_make_writable(frame)
        when (sampleFormat) {
            avutil.AV_SAMPLE_FMT_FLTP -> {
                for (channel in 0 until channels) {
                    val plane = frame.data(channel).limit(frameSize.toLong() * 4)
                        .asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
                    for (i in 0 until frameSize) {
                        plane.put(accumulator[i * channels + channel] / 32768f)
                    }
                }
            }
            avutil.AV_SAMPLE_FMT_S16P -> {
                for (channel in 0 until channels) {
                    val plane = frame.data(channel).limit(frameSize.toLong() * 2)
                        .asByteBuffer().order(ByteOrder.nativeOrder()).asShortBuffer()
                    for (i in 0 until frameSize) {
                        plane.put(accumulator[i * channels + channel])
                    }
                }
            }
            else -> {
                val plane = frame.data(0).limit(samplesPerFrame.toLong() * 2)
                    .asByteBuffer().order(ByteOrder.nativeOrder()).asShortBuffer()
                for (i in 0 until samplesPerFrame) plane.put(accumulator[i])
            }
        }
        frame.pts(pts)
        pts += frameSize.toLong()
        if (avcodec.avcodec_send_frame(context, frame) >= 0) {
            while (avcodec.avcodec_receive_packet(context, packet) >= 0) {
                val data = ByteArray(packet.size())
                packet.data().get(data)
                avcodec.av_packet_unref(packet)
                onPacket(data)
            }
        }
    }

    fun release() {
        runCatching { avutil.av_frame_free(frame) }
        runCatching { avcodec.av_packet_free(packet) }
        runCatching { avcodec.avcodec_free_context(context) }
    }

    companion object {
        fun create(codecId: Int, sampleRate: Int, channels: Int, bitRate: Int): FfmpegAudioEncoder? {
            if (channels !in 1..2 || sampleRate <= 0) return null
            val codec: AVCodec = runCatching { avcodec.avcodec_find_encoder(codecId) }.getOrNull() ?: return null
            val candidates = intArrayOf(
                avutil.AV_SAMPLE_FMT_FLTP,
                avutil.AV_SAMPLE_FMT_S16P,
                avutil.AV_SAMPLE_FMT_S16
            )
            candidates.forEach { format ->
                val built = runCatching { build(codec, codecId, format, sampleRate, channels, bitRate) }.getOrNull()
                if (built != null) return built
            }
            return null
        }

        private fun build(
            codec: AVCodec,
            codecId: Int,
            sampleFormat: Int,
            sampleRate: Int,
            channels: Int,
            bitRate: Int
        ): FfmpegAudioEncoder? {
            val context = avcodec.avcodec_alloc_context3(codec) ?: return null
            context.bit_rate(bitRate.toLong())
            context.sample_rate(sampleRate)
            avutil.av_channel_layout_default(context.ch_layout(), channels)
            context.sample_fmt(sampleFormat)
            if (codecId == avcodec.AV_CODEC_ID_AAC) {
                context.flags(context.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
                context.profile(1)
            }
            if (avcodec.avcodec_open2(context, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
                avcodec.avcodec_free_context(context)
                return null
            }
            val frameSize = context.frame_size().let { if (it <= 0) 1152 else it }
            val frame = avutil.av_frame_alloc()
            if (frame == null) {
                avcodec.avcodec_free_context(context)
                return null
            }
            frame.format(sampleFormat)
            frame.ch_layout(context.ch_layout())
            frame.sample_rate(sampleRate)
            frame.nb_samples(frameSize)
            if (avutil.av_frame_get_buffer(frame, 0) < 0) {
                avutil.av_frame_free(frame)
                avcodec.avcodec_free_context(context)
                return null
            }
            val packet = avcodec.av_packet_alloc()
            if (packet == null) {
                avutil.av_frame_free(frame)
                avcodec.avcodec_free_context(context)
                return null
            }
            return FfmpegAudioEncoder(context, frame, packet, sampleFormat, frameSize, channels)
        }
    }
}

private class DlnaClient(
    val codec: DlnaCodec,
    val stream: OutputStream,
    val remoteAddress: String
) {
    @Volatile
    var alive: Boolean = true
}

class DlnaMediaServer(
    private val scope: CoroutineScope,
    private val port: Int,
    private val sampleRate: Int,
    private val channels: Int,
    private val quirksForAddress: (String) -> DlnaQuirks
) {
    private companion object {
        const val BIND_ATTEMPTS = 8
        const val BIND_RETRY_DELAY_MS = 400L
    }

    private val clients = CopyOnWriteArrayList<DlnaClient>()
    private val pcmQueue = ArrayBlockingQueue<ByteArray>(12)

    @Volatile
    private var acceptorJob: Job? = null

    @Volatile
    private var pumpJob: Job? = null

    @Volatile
    var bindFailure: String? = null
        private set

    @Volatile
    var lastRequestSummary: String? = null
        private set

    fun clientCount(): Int = clients.count { it.alive }

    fun clientCount(codec: DlnaCodec): Int = clients.count { it.alive && it.codec == codec }

    fun submitPcm(pcmLittleEndian: ByteArray) {
        if (clients.isEmpty()) return
        while (!pcmQueue.offer(pcmLittleEndian)) {
            if (pcmQueue.poll() == null) break
        }
    }

    fun start() {
        acceptorJob = scope.launch(Dispatchers.IO) { runAcceptor(this) }
        pumpJob = scope.launch(Dispatchers.IO) { runPump(this) }
    }

    suspend fun stop() {
        val acceptor = acceptorJob
        val pump = pumpJob
        acceptorJob = null
        pumpJob = null
        clients.forEach { client ->
            client.alive = false
            runCatching { client.stream.close() }
        }
        clients.clear()
        pcmQueue.clear()
        runCatching { acceptor?.cancelAndJoin() }
        runCatching { pump?.cancelAndJoin() }
    }

    private suspend fun bindServerSocket(owner: CoroutineScope): ServerSocket? {
        var attempt = 0
        while (owner.isActive && attempt < BIND_ATTEMPTS) {
            attempt++
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                socket.soTimeout = 500
                return socket
            } catch (e: Exception) {
                runCatching { socket.close() }
                if (e is CancellationException) throw e
                DlnaDiagnostics.record(
                    "media",
                    "bind on port $port failed (attempt $attempt/$BIND_ATTEMPTS): ${e.javaClass.simpleName} ${e.message}"
                )
                delay(BIND_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private suspend fun runAcceptor(owner: CoroutineScope) {
        val serverSocket = bindServerSocket(owner)
        if (serverSocket == null) {
            bindFailure = "port $port unavailable"
            DlnaDiagnostics.record("media", "giving up on port $port, DLNA output is not listening")
            return
        }
        bindFailure = null
        DlnaDiagnostics.record("media", "media server listening on port $port")
        try {
            while (owner.isActive) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!serverSocket.isClosed && e !is CancellationException) {
                        DlnaDiagnostics.record("media", "accept failed: ${e.message}")
                    }
                    break
                }
                owner.launch(Dispatchers.IO) { handle(socket, owner) }
            }
        } finally {
            runCatching { serverSocket.close() }
            DlnaDiagnostics.record("media", "media server stopped")
        }
    }

    private suspend fun handle(socket: java.net.Socket, owner: CoroutineScope) {
        val remote = socket.inetAddress?.hostAddress.orEmpty()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 3000
            val input = socket.getInputStream()
            val requestText = readRequestHead(input) ?: return
            val lines = requestText.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val method = requestLine.substringBefore(' ').uppercase()
            val rawPath = requestLine.split(' ').getOrNull(1) ?: "/"
            val path = rawPath.substringBefore('?')
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            lastRequestSummary = "$method $path from $remote ua=${headers["user-agent"].orEmpty()}"
            DlnaDiagnostics.record(
                "media",
                "$method $path from $remote :: ua=${headers["user-agent"].orEmpty()} " +
                        "transferMode=${headers["transfermode.dlna.org"].orEmpty()} range=${headers["range"].orEmpty()}"
            )

            val codec = DlnaCodec.entries.firstOrNull { it.path == path }
            val output = socket.getOutputStream()

            if (codec == null) {
                val body = "WiFi Audio Streaming DLNA endpoint".toByteArray()
                output.write(
                    ("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()
                )
                output.write(body)
                output.flush()
                return
            }

            val quirks = quirksForAddress(remote)
            val header = buildResponseHeader(codec, quirks)
            output.write(header)
            output.flush()

            if (method == "HEAD") {
                return
            }

            if (codec == DlnaCodec.WAV) {
                output.write(wavStreamingHeader())
                output.flush()
            }

            val client = DlnaClient(codec, output, remote)
            clients.add(client)
            DlnaDiagnostics.record("media", "client attached: $remote codec=${codec.id}")
            try {
                while (owner.isActive && client.alive && !socket.isClosed) {
                    delay(400)
                }
            } finally {
                client.alive = false
                clients.remove(client)
                DlnaDiagnostics.record("media", "client detached: $remote codec=${codec.id}")
            }
        } catch (e: Exception) {
            if (e !is CancellationException) DlnaDiagnostics.record("media", "handler error from $remote: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readRequestHead(input: java.io.InputStream): String? {
        val buffer = StringBuilder()
        var consecutiveNewlines = 0
        var total = 0
        while (total < 16384) {
            val value = input.read()
            if (value < 0) return if (buffer.isEmpty()) null else buffer.toString()
            total++
            val ch = value.toChar()
            buffer.append(ch)
            when {
                ch == '\n' -> {
                    consecutiveNewlines++
                    if (consecutiveNewlines >= 2) return buffer.toString()
                }
                ch == '\r' -> Unit
                else -> consecutiveNewlines = 0
            }
        }
        return buffer.toString()
    }

    private fun buildResponseHeader(codec: DlnaCodec, quirks: DlnaQuirks): ByteArray {
        val mime = quirks.mimeOverride[codec] ?: codec.defaultMime(sampleRate, channels)
        val profile = quirks.pnOverride[codec] ?: codec.defaultPn
        val contentFeatures = "DLNA.ORG_PN=$profile;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=${quirks.flags}"
        return buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(mime).append("\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: ").append(contentFeatures).append("\r\n")
            append("EXT:\r\n")
            append("Server: ").append(DlnaConst.USER_AGENT).append("\r\n")
            append("Accept-Ranges: none\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Pragma: no-cache\r\n")
            if (quirks.requireContentLength) append("Content-Length: 2147483647\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
    }

    private fun wavStreamingHeader(): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(-1)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(-1)
        return buffer.array()
    }

    private fun toBigEndian(pcmLittleEndian: ByteArray): ByteArray {
        val out = ByteArray(pcmLittleEndian.size)
        var i = 0
        while (i + 1 < pcmLittleEndian.size) {
            out[i] = pcmLittleEndian[i + 1]
            out[i + 1] = pcmLittleEndian[i]
            i += 2
        }
        return out
    }

    private fun broadcast(codec: DlnaCodec, data: ByteArray) {
        val dead = ArrayList<DlnaClient>()
        clients.forEach { client ->
            if (client.codec != codec || !client.alive) return@forEach
            try {
                client.stream.write(data)
                client.stream.flush()
            } catch (_: Exception) {
                client.alive = false
                dead.add(client)
            }
        }
        if (dead.isNotEmpty()) clients.removeAll(dead)
    }

    private fun adtsHeaderWriter(): (ByteArray) -> ByteArray {
        val frequencyIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
            32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
            11025 -> 10; 8000 -> 11; 7350 -> 12; else -> 4
        }
        val channelConfig = channels
        return { raw ->
            val frameLength = raw.size + 7
            val out = ByteArray(frameLength)
            out[0] = 0xFF.toByte()
            out[1] = 0xF1.toByte()
            out[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig shr 2)).toByte()
            out[3] = (((channelConfig and 3) shl 6) or (frameLength shr 11)).toByte()
            out[4] = ((frameLength shr 3) and 0xFF).toByte()
            out[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
            out[6] = 0xFC.toByte()
            System.arraycopy(raw, 0, out, 7, raw.size)
            out
        }
    }

    private suspend fun runPump(owner: CoroutineScope) {
        var mp3Encoder: FfmpegAudioEncoder? = null
        var aacEncoder: FfmpegAudioEncoder? = null
        val wrapAdts = adtsHeaderWriter()
        try {
            while (owner.isActive) {
                val pcm = pcmQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

                if (clientCount(DlnaCodec.LPCM) > 0) broadcast(DlnaCodec.LPCM, toBigEndian(pcm))
                if (clientCount(DlnaCodec.WAV) > 0) broadcast(DlnaCodec.WAV, pcm)

                if (clientCount(DlnaCodec.MP3) > 0) {
                    if (mp3Encoder == null) {
                        mp3Encoder = FfmpegAudioEncoder.create(avcodec.AV_CODEC_ID_MP3, sampleRate, channels, 320_000)
                        if (mp3Encoder == null) DlnaDiagnostics.record("media", "mp3 encoder unavailable")
                    }
                    mp3Encoder?.feed(pcm) { broadcast(DlnaCodec.MP3, it) }
                } else if (mp3Encoder != null) {
                    mp3Encoder.release()
                    mp3Encoder = null
                }

                if (clientCount(DlnaCodec.ADTS) > 0) {
                    if (aacEncoder == null) {
                        aacEncoder = FfmpegAudioEncoder.create(avcodec.AV_CODEC_ID_AAC, sampleRate, channels, 320_000)
                        if (aacEncoder == null) DlnaDiagnostics.record("media", "aac encoder unavailable")
                    }
                    aacEncoder?.feed(pcm) { broadcast(DlnaCodec.ADTS, wrapAdts(it)) }
                } else if (aacEncoder != null) {
                    aacEncoder.release()
                    aacEncoder = null
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) DlnaDiagnostics.record("media", "pump error: ${e.message}")
        } finally {
            mp3Encoder?.release()
            aacEncoder?.release()
        }
    }
}
