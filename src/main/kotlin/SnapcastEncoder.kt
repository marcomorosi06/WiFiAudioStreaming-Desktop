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

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.nio.ByteOrder

data class SnapcastAudioFormat(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int
) {

    val frameBytes: Int get() = channels * bitDepth / 8

    val bytesPerMs: Int get() = sampleRate * frameBytes / 1000

    fun framesOf(byteCount: Int): Int = if (frameBytes <= 0) 0 else byteCount / frameBytes

    fun describe(): String = "$sampleRate:$bitDepth:$channels"
}

interface SnapcastEncoder {

    val codecName: String

    val header: ByteArray

    val outputFormat: SnapcastAudioFormat

    fun encode(pcm: ByteArray, offset: Int, length: Int, sink: (ByteArray, Int) -> Unit)

    fun close()
}

class SnapcastPcmEncoder(override val outputFormat: SnapcastAudioFormat) : SnapcastEncoder {

    override val codecName: String = SnapcastCodecs.PCM

    override val header: ByteArray =
        SnapcastWire.wavHeader(outputFormat.sampleRate, outputFormat.bitDepth, outputFormat.channels)

    override fun encode(pcm: ByteArray, offset: Int, length: Int, sink: (ByteArray, Int) -> Unit) {
        val payload = ByteArray(length)
        System.arraycopy(pcm, offset, payload, 0, length)
        sink(payload, outputFormat.framesOf(length))
    }

    override fun close() = Unit
}

class SnapcastFfmpegEncoder private constructor(
    override val codecName: String,
    override val header: ByteArray,
    override val outputFormat: SnapcastAudioFormat,
    private val ctx: AVCodecContext,
    private val frame: AVFrame,
    private val packet: AVPacket,
    private val frameSize: Int,
    private val sampleFormat: Int
) : SnapcastEncoder {

    private val channels = outputFormat.channels
    private val pending = ShortArray(frameSize * channels)
    private var pendingSamples = 0
    private var pts = 0L
    private var closed = false

    override fun encode(pcm: ByteArray, offset: Int, length: Int, sink: (ByteArray, Int) -> Unit) {
        if (closed || length <= 0) return
        val incoming = java.nio.ByteBuffer.wrap(pcm, offset, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (incoming.hasRemaining()) {
            pending[pendingSamples++] = incoming.get()
            if (pendingSamples == pending.size) {
                submitFrame(sink)
                pendingSamples = 0
            }
        }
    }

    private fun submitFrame(sink: (ByteArray, Int) -> Unit) {
        avutil.av_frame_make_writable(frame)
        writeSamples()
        frame.pts(pts)
        pts += frameSize.toLong()
        if (avcodec.avcodec_send_frame(ctx, frame) < 0) return
        drain(sink)
    }

    private fun drain(sink: (ByteArray, Int) -> Unit) {
        while (avcodec.avcodec_receive_packet(ctx, packet) >= 0) {
            val size = packet.size()
            if (size > 0) {
                val payload = ByteArray(size)
                packet.data().position(0L).get(payload)
                val duration = packet.duration()
                val frames = if (duration > 0L) duration.toInt() else frameSize
                sink(payload, frames)
            }
            avcodec.av_packet_unref(packet)
        }
    }

    private fun writeSamples() {
        when (sampleFormat) {
            avutil.AV_SAMPLE_FMT_S16 -> {
                val dst = frame.data(0).limit(frameSize.toLong() * channels * 2)
                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                dst.put(pending, 0, frameSize * channels)
            }
            avutil.AV_SAMPLE_FMT_S16P -> {
                for (ch in 0 until channels) {
                    val dst = frame.data(ch).limit(frameSize.toLong() * 2)
                        .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in 0 until frameSize) dst.put(pending[i * channels + ch])
                }
            }
            avutil.AV_SAMPLE_FMT_S32 -> {
                val dst = frame.data(0).limit(frameSize.toLong() * channels * 4)
                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                for (i in 0 until frameSize * channels) dst.put(pending[i].toInt() shl 16)
            }
            avutil.AV_SAMPLE_FMT_S32P -> {
                for (ch in 0 until channels) {
                    val dst = frame.data(ch).limit(frameSize.toLong() * 4)
                        .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                    for (i in 0 until frameSize) dst.put(pending[i * channels + ch].toInt() shl 16)
                }
            }
            avutil.AV_SAMPLE_FMT_FLT -> {
                val dst = frame.data(0).limit(frameSize.toLong() * channels * 4)
                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until frameSize * channels) dst.put(pending[i] / 32768f)
            }
            else -> {
                for (ch in 0 until channels) {
                    val dst = frame.data(ch).limit(frameSize.toLong() * 4)
                        .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    for (i in 0 until frameSize) dst.put(pending[i * channels + ch] / 32768f)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { avutil.av_frame_free(frame) }
        runCatching { avcodec.av_packet_free(packet) }
        runCatching { avcodec.avcodec_free_context(ctx) }
    }

    companion object {

        private val PREFERRED_FORMATS = intArrayOf(
            avutil.AV_SAMPLE_FMT_S16,
            avutil.AV_SAMPLE_FMT_S16P,
            avutil.AV_SAMPLE_FMT_FLTP,
            avutil.AV_SAMPLE_FMT_FLT,
            avutil.AV_SAMPLE_FMT_S32,
            avutil.AV_SAMPLE_FMT_S32P
        )

        private fun pickSampleFormat(codec: org.bytedeco.ffmpeg.avcodec.AVCodec): Int {
            val formats = codec.sample_fmts() ?: return avutil.AV_SAMPLE_FMT_S16
            val supported = ArrayList<Int>()
            var index = 0L
            while (true) {
                val value = formats.get(index)
                if (value < 0) break
                supported.add(value)
                index++
                if (index > 32) break
            }
            if (supported.isEmpty()) return avutil.AV_SAMPLE_FMT_S16
            for (preferred in PREFERRED_FORMATS) if (supported.contains(preferred)) return preferred
            return supported.first()
        }

        fun createFlac(format: SnapcastAudioFormat, compressionLevel: Int): SnapcastFfmpegEncoder? {
            val codec = avcodec.avcodec_find_encoder(avcodec.AV_CODEC_ID_FLAC) ?: return null
            val ctx = avcodec.avcodec_alloc_context3(codec) ?: return null
            val sampleFormat = pickSampleFormat(codec)
            ctx.sample_rate(format.sampleRate)
            ctx.sample_fmt(sampleFormat)
            avutil.av_channel_layout_default(ctx.ch_layout(), format.channels)
            ctx.bits_per_raw_sample(format.bitDepth)
            ctx.frame_size(FLAC_BLOCK_SIZE)
            avutil.av_opt_set_int(ctx.priv_data(), "compression_level", compressionLevel.coerceIn(0, 12).toLong(), 0)
            avutil.av_opt_set_int(ctx.priv_data(), "frame_size", FLAC_BLOCK_SIZE.toLong(), 0)
            if (avcodec.avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
                avcodec.avcodec_free_context(ctx)
                return null
            }
            val extradataSize = ctx.extradata_size()
            val streamInfo = ByteArray(if (extradataSize > 0) extradataSize else 0)
            if (extradataSize > 0) ctx.extradata().position(0L).get(streamInfo)
            val header = SnapcastWire.flacHeader(streamInfo)
            if (header.isEmpty()) {
                avcodec.avcodec_free_context(ctx)
                return null
            }
            val frameSize = ctx.frame_size().let { if (it <= 0) FLAC_BLOCK_SIZE else it }
            val frame = allocFrame(ctx, frameSize) ?: run {
                avcodec.avcodec_free_context(ctx)
                return null
            }
            val packet = avcodec.av_packet_alloc()
            return SnapcastFfmpegEncoder(
                SnapcastCodecs.FLAC, header, format, ctx, frame, packet, frameSize, sampleFormat
            )
        }

        fun createOpus(format: SnapcastAudioFormat, bitrate: Int, frameDurationMs: Int): SnapcastFfmpegEncoder? {
            if (format.sampleRate != 48000 || format.channels != 2 || format.bitDepth != 16) return null
            val codec = avcodec.avcodec_find_encoder_by_name("libopus")
                ?: avcodec.avcodec_find_encoder(avcodec.AV_CODEC_ID_OPUS)
                ?: return null
            val ctx = avcodec.avcodec_alloc_context3(codec) ?: return null
            val sampleFormat = pickSampleFormat(codec)
            ctx.sample_rate(48000)
            ctx.sample_fmt(sampleFormat)
            avutil.av_channel_layout_default(ctx.ch_layout(), format.channels)
            ctx.bit_rate(bitrate.coerceIn(6_000, 512_000).toLong())
            ctx.flags(ctx.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
            avutil.av_opt_set(ctx.priv_data(), "application", "lowdelay", 0)
            avutil.av_opt_set(ctx.priv_data(), "frame_duration", frameDurationMs.toString(), 0)
            avutil.av_opt_set_int(ctx.priv_data(), "vbr", 0L, 0)
            if (avcodec.avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
                avcodec.avcodec_free_context(ctx)
                return null
            }
            val frameSize = ctx.frame_size().let { if (it <= 0) 48 * frameDurationMs else it }
            val frame = allocFrame(ctx, frameSize) ?: run {
                avcodec.avcodec_free_context(ctx)
                return null
            }
            val packet = avcodec.av_packet_alloc()
            val header = SnapcastWire.opusHeader(48000, 16, format.channels)
            return SnapcastFfmpegEncoder(
                SnapcastCodecs.OPUS, header, format.copy(sampleRate = 48000), ctx, frame, packet, frameSize, sampleFormat
            )
        }

        private fun allocFrame(ctx: AVCodecContext, frameSize: Int): AVFrame? {
            val frame = avutil.av_frame_alloc() ?: return null
            frame.format(ctx.sample_fmt())
            frame.ch_layout(ctx.ch_layout())
            frame.sample_rate(ctx.sample_rate())
            frame.nb_samples(frameSize)
            if (avutil.av_frame_get_buffer(frame, 0) < 0) {
                avutil.av_frame_free(frame)
                return null
            }
            return frame
        }

        private const val FLAC_BLOCK_SIZE = 1152
    }
}

object SnapcastCodecs {

    const val PCM = "pcm"
    const val FLAC = "flac"
    const val OPUS = "opus"

    val ALL = listOf(PCM, FLAC, OPUS)

    fun normalize(value: String?): String = when (value?.lowercase()?.trim()) {
        FLAC -> FLAC
        OPUS -> OPUS
        else -> PCM
    }

    fun create(
        codec: String,
        format: SnapcastAudioFormat,
        flacCompression: Int = 2,
        opusBitrate: Int = 192_000,
        opusFrameMs: Int = 20,
        onFallback: (String) -> Unit = {}
    ): SnapcastEncoder {
        val requested = normalize(codec)
        val built = when (requested) {
            FLAC -> runCatching { SnapcastFfmpegEncoder.createFlac(format, flacCompression) }.getOrNull()
            OPUS -> runCatching { SnapcastFfmpegEncoder.createOpus(format, opusBitrate, opusFrameMs) }.getOrNull()
            else -> null
        }
        if (built != null) return built
        if (requested != PCM) onFallback(requested)
        return SnapcastPcmEncoder(format)
    }
}
