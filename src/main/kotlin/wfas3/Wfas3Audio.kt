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

import java.util.ArrayDeque

/**
 * Cuts captured 16-bit interleaved PCM into the exact packets of a stream
 * configuration (PCM 16/24 bit or Opus) and hands each packet, with its
 * sample position and capture time, to [emit].
 */
class Wfas3Framer(
    val config: Wfas3Config,
    private val emit: (pos: Long, captureUs: Long, payload: ByteArray, len: Int) -> Unit,
) {
    private val ch = config.channels
    private val frame = config.frameSamples
    private val acc = ShortArray(frame * ch)
    private var fill = 0                   // samples per channel in acc
    private var accStartUs = 0L
    private val payload = ByteArray(config.payloadBytes)
    private var pos = 0L
    private var enc = 0L

    init {
        require(config.isValid()) { "invalid configuration" }
        require(ch <= 2 || !config.opus) { "Opus beyond two channels is not supported here" }
        if (config.opus) {
            enc = Wfas3Opus.nEncCreate(ch, frame, config.payloadBytes, 9)
            check(enc != 0L) { "Opus encoder refused the configuration" }
        }
    }

    /**
     * [pcm] holds [frames] samples per channel, interleaved, with [srcChannels]
     * channels (1 or 2); [captureUs] is the clock time of the first sample.
     */
    fun feed(pcm: ShortArray, off: Int, frames: Int, srcChannels: Int, captureUs: Long) {
        var i = 0
        while (i < frames) {
            if (fill == 0) accStartUs = captureUs + i * 1_000_000L / config.sampleRate
            val take = minOf(frame - fill, frames - i)
            for (k in 0 until take) {
                val si = off + (i + k) * srcChannels
                val di = (fill + k) * ch
                if (srcChannels == ch) {
                    for (c in 0 until ch) acc[di + c] = pcm[si + c]
                } else if (srcChannels == 1) {
                    for (c in 0 until ch) acc[di + c] = pcm[si]
                } else {
                    // Down-mix to mono, or copy the first channels.
                    if (ch == 1) acc[di] = ((pcm[si].toInt() + pcm[si + 1].toInt()) / 2).toShort()
                    else for (c in 0 until ch) acc[di + c] = pcm[si + minOf(c, srcChannels - 1)]
                }
            }
            fill += take
            i += take
            if (fill == frame) {
                pack()
                emit(pos, accStartUs, payload, payload.size)
                pos += frame
                fill = 0
            }
        }
    }

    private fun pack() {
        when {
            config.opus -> {
                if (Wfas3Opus.nEncode(enc, acc, payload) != payload.size) java.util.Arrays.fill(payload, 0)
            }
            config.bits == 16 -> {
                var j = 0
                for (s in acc) { payload[j++] = s.toByte(); payload[j++] = (s.toInt() shr 8).toByte() }
            }
            else -> {
                var j = 0
                for (s in acc) { payload[j++] = 0; payload[j++] = s.toByte(); payload[j++] = (s.toInt() shr 8).toByte() }
            }
        }
    }

    fun close() {
        if (enc != 0L) { Wfas3Opus.nEncDestroy(enc); enc = 0L }
        java.util.Arrays.fill(acc, 0)
        java.util.Arrays.fill(payload, 0)
    }
}

/**
 * Decodes the payload of a stream into 16-bit interleaved PCM with at most
 * two channels. Packets missing in the sample sequence become Opus
 * concealment (up to [MAX_CONCEAL] frames) or silence in the playout.
 */
class Wfas3Decoder(val config: Wfas3Config) {
    val outChannels: Int = minOf(config.channels, 2)
    private var dec = 0L
    private val tmp = ShortArray(config.frameSamples * minOf(config.channels, 2))
    private var nextPos = -1L

    init {
        if (config.opus) {
            require(config.channels <= 2) { "Opus beyond two channels is not supported here" }
            dec = Wfas3Opus.nDecCreate(config.channels, config.frameSamples)
            check(dec != 0L) { "Opus decoder refused the configuration" }
        }
    }

    /** Concealment frames for a gap before [pos] (Opus only); each goes to [out]. */
    fun conceal(pos: Long, out: (ShortArray, Int, Long) -> Unit) {
        if (!config.opus || nextPos < 0 || pos <= nextPos) return
        val missing = ((pos - nextPos) / config.frameSamples).coerceAtMost(MAX_CONCEAL.toLong()).toInt()
        var p = pos - missing.toLong() * config.frameSamples
        repeat(missing) {
            val n = Wfas3Opus.nDecode(dec, null, 0, tmp)
            if (n > 0) out(tmp, n, p)
            p += config.frameSamples
        }
    }

    /** Decode one packet. Returns the samples per channel written to [dst], or 0. */
    fun decode(pos: Long, data: ByteArray, off: Int, len: Int, dst: ShortArray): Int {
        if (len != config.payloadBytes) return 0
        nextPos = pos + config.frameSamples
        if (config.opus) {
            val pkt = if (off == 0) data else data.copyOfRange(off, off + len)
            val n = Wfas3Opus.nDecode(dec, pkt, len, tmp)
            if (n <= 0) return 0
            System.arraycopy(tmp, 0, dst, 0, n * outChannels)
            return n
        }
        val bps = config.bits / 8
        val inCh = config.channels
        val frames = config.frameSamples
        var p = off
        for (f in 0 until frames) {
            for (c in 0 until inCh) {
                val v = if (bps == 2) {
                    ((data[p].toInt() and 0xFF) or (data[p + 1].toInt() shl 8)).toShort()
                } else {
                    ((data[p + 1].toInt() and 0xFF) or (data[p + 2].toInt() shl 8)).toShort()
                }
                if (c < outChannels) dst[f * outChannels + c] = v
                p += bps
            }
        }
        return frames
    }

    fun close() {
        if (dec != 0L) { Wfas3Opus.nDecDestroy(dec); dec = 0L }
    }

    companion object { const val MAX_CONCEAL = 3 }
}

/**
 * Playout buffer. Frames come in with the instant they must be played (our
 * clock, microseconds); the player pulls blocks for the instant they will
 * actually be heard. Late audio is dropped (never played out of time) and
 * reported; early audio waits; a small steady error is corrected by
 * dropping or repeating one sample at most every 2000 (500 ppm).
 *
 * Without synchronised playback the instant comes from the arrival times.
 * Every second the delays (arrival - capture) of that second are measured:
 * their low end is the floor of the path, their spread its jitter. The
 * instant is capture + floor + latency, the latency being at least the
 * jitter plus what the output holds. Floor and jitter come from the last
 * few seconds only, so a change of the network (Wi-Fi power save, a busier
 * link, a single fast packet) is followed within seconds instead of making
 * every block late.
 */
class Wfas3Playout(val channels: Int, val rate: Int, private val onLate: (Long) -> Unit) {
    private class Chunk(val targetUs: Long, val pcm: ShortArray, val frames: Int) { var read = 0 }

    private val queue = ArrayDeque<Chunk>()
    private val lock = Object()
    private var slipCounter = 0
    private var lastLateReport = 0L
    // Counters for the trace, reset by stats().
    private var stPushed = 0L
    private var stPlayed = 0L
    private var stLate = 0L
    private var stEarly = 0L

    // Arrival-time mapping (not synchronised), see the class comment.
    private var base = Long.MIN_VALUE        // floor of the delays in use
    private var spread = 0L                  // jitter in use
    private val win = LongArray(WINDOW_MAX)  // delays of the current window
    private var winN = 0
    private var winStartUs = 0L
    private val histLow = LongArray(HISTORY)
    private val histSpread = LongArray(HISTORY)
    private var histN = 0
    private var histPos = 0

    /** Most audio kept ahead (protects memory if the player stalls). */
    private val maxQueuedUs = 2_000_000L

    /** Synchronised: the core gives the instant. */
    fun pushAt(targetUs: Long, pcm: ShortArray, frames: Int) {
        if (frames <= 0) return
        val copy = pcm.copyOf(frames * channels)
        synchronized(lock) {
            stPushed += frames
            queue.addLast(Chunk(targetUs, copy, frames))
            while (queue.size > 1 && queue.peekLast().targetUs - queue.peekFirst().targetUs > maxQueuedUs) queue.removeFirst()
        }
    }

    /**
     * Not synchronised: the instant is the capture time mapped by the arrival
     * times. [latencyUs] is the latency asked for, [outputUs] what the output
     * holds before a sample is heard (see [Wfas3PcmOutput.bufferUs]).
     */
    fun pushArrived(captureUs: Long, arrivalUs: Long, latencyUs: Long, outputUs: Long, pcm: ShortArray, frames: Int) {
        val d = arrivalUs - captureUs
        val target: Long
        synchronized(lock) {
            if (base == Long.MIN_VALUE) {
                base = d
                spread = 0L
                winStartUs = arrivalUs
            }
            if (winN < WINDOW_MAX) win[winN++] = d
            // The first window starts from the lowest delay seen; later ones
            // replace floor and jitter at their end.
            if (histN == 0 && d < base) base = d
            if (arrivalUs - winStartUs >= WINDOW_US && winN >= 8) closeWindow(arrivalUs)
            val need = spread + outputUs + MARGIN_US
            target = captureUs + base + maxOf(latencyUs, need)
        }
        pushAt(target, pcm, frames)
    }

    /** End of a measuring window (lock held). */
    private fun closeWindow(nowUs: Long) {
        java.util.Arrays.sort(win, 0, winN)
        val low = win[winN / 10]
        val high = win[minOf(winN - 1, (winN * 95) / 100)]
        histLow[histPos] = low
        histSpread[histPos] = high - low
        histPos = (histPos + 1) % HISTORY
        if (histN < HISTORY) histN++
        // The floor follows the last two windows (a path that got slower is
        // followed in about 2 s), the jitter the last four.
        var lo = Long.MAX_VALUE
        var sp = 0L
        for (i in 0 until histN) {
            val age = (histPos - 1 - i + HISTORY) % HISTORY
            if (age < LOW_WINDOWS) lo = minOf(lo, histLow[i])
            sp = maxOf(sp, histSpread[i])
        }
        // Small moves are left to the slip of pull(): each re-anchoring
        // costs a short gap or a short drop, so it happens only when worth it.
        if (kotlin.math.abs(lo - base) > MOVE_US) base = lo
        if (sp > spread || spread - sp > MOVE_US) spread = sp
        winN = 0
        winStartUs = nowUs
    }

    /** Counters since the last call, for the trace. */
    fun stats(): String = synchronized(lock) {
        val r = "pushed=$stPushed played=$stPlayed late=$stLate waited=$stEarly queued=${queuedUs() / 1000}ms " +
            "floor=${if (base == Long.MIN_VALUE) "-" else (base / 1000).toString()}ms jitter=${spread / 1000}ms"
        stPushed = 0; stPlayed = 0; stLate = 0; stEarly = 0
        r
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            base = Long.MIN_VALUE
            spread = 0L
            winN = 0
            histN = 0
            histPos = 0
        }
    }

    /** Microseconds of audio waiting. */
    fun queuedUs(): Long = synchronized(lock) {
        var f = 0L
        for (c in queue) f += (c.frames - c.read)
        f * 1_000_000L / rate
    }

    /**
     * Fill [dst] with [frames] samples per channel to be heard at [playUs].
     * Returns true if any audio (not only silence) was written.
     */
    fun pull(dst: ShortArray, frames: Int, playUs: Long): Boolean {
        java.util.Arrays.fill(dst, 0, frames * channels, 0)
        var any = false
        var report = 0L
        synchronized(lock) {
            var o = 0
            var lateUs = 0L
            while (o < frames) {
                val head = queue.peekFirst() ?: break
                val tNow = playUs + o * 1_000_000L / rate
                val expected = head.targetUs + head.read * 1_000_000L / rate
                val err = tNow - expected                     // > 0: this audio is late
                if (err > LATE_US) {
                    // Drop what should already have been heard.
                    val drop = minOf(head.frames - head.read, ((err * rate) / 1_000_000L).toInt().coerceAtLeast(1))
                    head.read += drop
                    stLate += drop
                    if (head.read >= head.frames) queue.removeFirst()
                    lateUs = maxOf(lateUs, err)
                    continue
                }
                if (err < -EARLY_US) {
                    // Too early: silence until it is due.
                    val wait = ((-err) * rate / 1_000_000L).toInt().coerceAtLeast(1)
                    stEarly += minOf(wait, frames - o)
                    o += minOf(wait, frames - o)
                    continue
                }
                // Within tolerance: copy, with one slip or repeat when the error is steady.
                var step = 1
                var repeat = false
                if (++slipCounter >= SLIP_EVERY) {
                    slipCounter = 0
                    if (err > FINE_US && head.frames - head.read > 1) step = 2
                    else if (err < -FINE_US) repeat = true
                }
                val si = head.read * channels
                val di = o * channels
                for (c in 0 until channels) dst[di + c] = head.pcm[si + c]
                stPlayed++
                any = true
                o++
                if (!repeat) {
                    head.read += step
                    if (head.read >= head.frames) queue.removeFirst()
                }
            }
            if (lateUs > 0L && playUs - lastLateReport > 100_000L) {
                lastLateReport = playUs
                report = lateUs
            }
        }
        // Outside the lock: the receiver pushes audio holding the engine lock
        // and then this one; reporting while holding this one would invert
        // the order and deadlock both threads.
        if (report > 0L) try { onLate(report) } catch (_: Throwable) { }
        return any
    }

    companion object {
        const val LATE_US = 20_000L
        const val EARLY_US = 20_000L
        /** Delays measured per window (arrival-time mapping). */
        private const val WINDOW_US = 1_000_000L
        private const val WINDOW_MAX = 1024
        /** Windows remembered: the jitter of the last 4 s, the floor of the last 2. */
        private const val HISTORY = 4
        private const val LOW_WINDOWS = 2
        /** Below this, floor and jitter are not moved. */
        private const val MOVE_US = 5_000L
        /** Kept beyond jitter and output, against the delays above the 95th percentile. */
        private const val MARGIN_US = 10_000L
        const val FINE_US = 1_000L
        const val SLIP_EVERY = 2000
    }
}

/**
 * Linear resampler and channel adapter for 16-bit interleaved PCM: enough
 * for the microphone path and for a capture device that cannot run at the
 * stream rate. Keeps its phase between calls.
 */
class Wfas3Resampler(val inRate: Int, val outRate: Int, val inChannels: Int, val outChannels: Int) {
    private var pos = 0.0
    private val last = ShortArray(maxOf(inChannels, 1))
    private var haveLast = false
    private var buf = ShortArray(0)

    /** Returns the converted samples in a buffer owned by this object, and their frame count. */
    fun process(src: ShortArray, off: Int, frames: Int): Pair<ShortArray, Int> {
        val step = inRate.toDouble() / outRate
        val maxOut = ((frames + 2) / step).toInt() + 2
        if (buf.size < maxOut * outChannels) buf = ShortArray(maxOut * outChannels)
        var n = 0
        if (inRate == outRate) {
            for (f in 0 until frames) { put(src, off + f * inChannels, n); n++ }
            return buf to n
        }
        // Positions are measured from the last sample of the previous call (index -1).
        while (true) {
            val i = pos.toInt()
            val frac = pos - i
            if (i >= frames) break
            for (c in 0 until outChannels) {
                val sc = minOf(c, inChannels - 1)
                val a = if (i == 0) (if (haveLast) last[sc].toInt() else src[off + sc].toInt()) else src[off + (i - 1) * inChannels + sc].toInt()
                val b = src[off + i * inChannels + sc].toInt()
                buf[n * outChannels + c] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            }
            n++
            pos += step
        }
        pos -= frames
        for (c in 0 until inChannels) last[c] = src[off + (frames - 1) * inChannels + c]
        haveLast = true
        return buf to n
    }

    private fun put(src: ShortArray, si: Int, n: Int) {
        for (c in 0 until outChannels) {
            buf[n * outChannels + c] = if (inChannels == 2 && outChannels == 1) {
                ((src[si].toInt() + src[si + 1].toInt()) / 2).toShort()
            } else src[si + minOf(c, inChannels - 1)]
        }
    }
}

/** A device that plays 16-bit interleaved PCM (AudioTrack, SourceDataLine...). */
interface Wfas3PcmOutput {
    val rate: Int
    val channels: Int

    /** Blocking write of [frames] samples per channel. */
    fun write(buf: ShortArray, frames: Int)

    /** Microseconds until a sample written now is heard. */
    fun pendingUs(): Long

    /**
     * Microseconds of audio the output holds when its buffer is full, which
     * is how far ahead the player writes. 0 if negligible.
     */
    val bufferUs: Long get() = 0L

    fun close()
}

/** Pulls 5 ms blocks from a playout buffer into an output, on its own thread. */
class Wfas3Player(private val output: Wfas3PcmOutput, val playout: Wfas3Playout) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var volume: Float = 1f
    @Volatile var muted: Boolean = false

    /** See [Wfas3PcmOutput.bufferUs]. */
    val bufferUs: Long get() = output.bufferUs

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "wfas3-play").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop() {
        val block = maxOf(1, output.rate / 200)
        val buf = ShortArray(block * output.channels)
        while (running) {
            try {
                val playUs = Wfas3Engine.clockUs() + output.pendingUs()
                playout.pull(buf, block, playUs)
                val v = if (muted) 0f else volume
                if (v != 1f) for (i in buf.indices) buf[i] = (buf[i] * v).toInt().coerceIn(-32768, 32767).toShort()
                output.write(buf, block)
            } catch (_: Throwable) {
                if (!running) break
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) { }
        thread = null
        try { output.close() } catch (_: Throwable) { }
        playout.clear()
    }
}
