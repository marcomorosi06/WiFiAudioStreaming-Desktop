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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object SpectrumAnalyzer {

    const val GROOVE_MAX = 1.6f

    private const val FFT_SIZE = 2048
    private const val F_MIN = 35.0
    private const val FLOOR_DB = -66.0
    private const val CEIL_DB = -9.0
    private const val TILT_DB = 6.0
    private const val ATTACK = 0.55f
    private const val RELEASE = 0.16f
    private const val PEAK_GRAV = 0.006f
    private const val METER_FLOOR = -54.0

    private const val GRV_SLOW = 0.012f
    private const val GRV_RADIUS = 0.07f
    private const val GRV_CONTRAST = 0.85f
    private const val GRV_WHITEN = 0.25f
    private const val GRV_GATE_DB = 8.0f
    private const val GRV_MAX_DEV = 22.0f
    private const val GRV_RELEASE = 0.30f

    private const val NUM_BARS = 64

    private val lock = Any()

    private val monoRing = DoubleArray(FFT_SIZE)
    private var monoWrite = 0
    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1)) }
    private val fftRe = DoubleArray(FFT_SIZE)
    private val fftIm = DoubleArray(FFT_SIZE)
    private val cosT = DoubleArray(FFT_SIZE / 2)
    private val sinT = DoubleArray(FFT_SIZE / 2)
    private val bitRev = IntArray(FFT_SIZE)

    private val binLo = IntArray(NUM_BARS)
    private val binHi = IntArray(NUM_BARS)

    private val gvDb = FloatArray(NUM_BARS)
    private val gvSlow = FloatArray(NUM_BARS)
    private val gvSum = FloatArray(NUM_BARS + 1)
    private var gvReady = false

    private val barsOut = FloatArray(NUM_BARS)
    private val peaksOut = FloatArray(NUM_BARS)
    private val peakVel = FloatArray(NUM_BARS)

    private var sumSq = 0.0
    private var frameCount = 0L
    private var levelOut = 0f

    @Volatile private var configuredRate = 0
    @Volatile var active = false
        private set

    init {
        for (i in 0 until FFT_SIZE / 2) {
            val a = -2.0 * PI * i / FFT_SIZE
            cosT[i] = cos(a)
            sinT[i] = sin(a)
        }
        var j = 0
        for (i in 0 until FFT_SIZE) {
            bitRev[i] = j
            var m = FFT_SIZE shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        configure(48000)
    }

    fun configure(sampleRate: Int) {
        if (sampleRate == configuredRate) return
        configuredRate = sampleRate
        val nyquist = sampleRate / 2.0
        val fMax = nyquist.coerceAtMost(18000.0)
        val lnLo = ln(F_MIN)
        val lnHi = ln(fMax)
        val binHz = sampleRate.toDouble() / FFT_SIZE
        for (b in 0 until NUM_BARS) {
            val f0 = exp(lnLo + (lnHi - lnLo) * b / NUM_BARS)
            val f1 = exp(lnLo + (lnHi - lnLo) * (b + 1) / NUM_BARS)
            var lo = (f0 / binHz).roundToInt().coerceIn(1, FFT_SIZE / 2 - 1)
            var hi = (f1 / binHz).roundToInt().coerceIn(1, FFT_SIZE / 2 - 1)
            if (hi < lo) hi = lo
            binLo[b] = lo
            binHi[b] = hi
        }
    }

    fun feedFrame(samples: ShortArray, channels: Int) {
        if (samples.isEmpty()) return
        val ch = channels.coerceAtLeast(1)
        synchronized(lock) {
            var i = 0
            while (i + ch <= samples.size) {
                var acc = 0.0
                for (c in 0 until ch) acc += samples[i + c].toDouble()
                val mono = acc / ch
                monoRing[monoWrite] = mono
                monoWrite = (monoWrite + 1) % FFT_SIZE
                sumSq += mono * mono
                frameCount++
                i += ch
            }
            active = true
        }
    }

    fun reset() {
        synchronized(lock) {
            monoRing.fill(0.0)
            monoWrite = 0
            sumSq = 0.0
            frameCount = 0
            active = false
        }
        barsOut.fill(0f)
        peaksOut.fill(0f)
        peakVel.fill(0f)
        gvSlow.fill(0f)
        gvReady = false
        levelOut = 0f
    }

    val barCount: Int get() = NUM_BARS

    fun snapshot(groove: Float, bars: FloatArray, peaks: FloatArray): Float {
        val start: Int
        val frames: Long
        val energy: Double
        synchronized(lock) {
            start = monoWrite
            frames = frameCount
            energy = sumSq
            sumSq = 0.0
            frameCount = 0
            for (i in 0 until FFT_SIZE) {
                fftRe[i] = monoRing[(start + i) % FFT_SIZE] * window[i]
                fftIm[i] = 0.0
            }
        }

        fft()

        val gain = (2.0 / (FFT_SIZE * 0.5)) / 32768.0
        val denom = CEIL_DB - FLOOR_DB
        var loudest = -400.0
        for (b in 0 until NUM_BARS) {
            var m = 0.0
            var k = binLo[b]
            val e = binHi[b]
            while (k <= e) {
                val re = fftRe[k]
                val im = fftIm[k]
                val p = re * re + im * im
                if (p > m) m = p
                k++
            }
            val amp = sqrt(m) * gain
            val tilt = TILT_DB * b / (NUM_BARS - 1).coerceAtLeast(1)
            val dbv = 20.0 * log10(amp + 1e-12) + tilt
            if (dbv > loudest) loudest = dbv
            gvDb[b] = dbv.toFloat()
        }

        val grv = groove.coerceIn(0f, GROOVE_MAX)
        if (grv > 0f) applyGroove(grv, loudest) else gvReady = false

        val release = RELEASE + (GRV_RELEASE - RELEASE) * (grv / GROOVE_MAX).coerceIn(0f, 1f)
        for (b in 0 until NUM_BARS) {
            var v = ((gvDb[b] - FLOOR_DB) / denom).toFloat()
            if (v < 0f) v = 0f else if (v > 1f) v = 1f
            if (v > barsOut[b]) barsOut[b] += (v - barsOut[b]) * ATTACK
            else barsOut[b] += (v - barsOut[b]) * release
            if (barsOut[b] >= peaksOut[b]) {
                peaksOut[b] = barsOut[b]
                peakVel[b] = 0f
            } else {
                peakVel[b] += PEAK_GRAV
                peaksOut[b] -= peakVel[b]
                if (peaksOut[b] < barsOut[b]) {
                    peaksOut[b] = barsOut[b]
                    peakVel[b] = 0f
                }
            }
            if (b < bars.size) bars[b] = barsOut[b]
            if (b < peaks.size) peaks[b] = peaksOut[b]
        }

        val rms = if (frames > 0) sqrt(energy / frames) / 32768.0 else 0.0
        val dbv = 20.0 * log10(rms + 1e-12)
        var lv = ((dbv - METER_FLOOR) / (-METER_FLOOR)).toFloat()
        if (lv < 0f) lv = 0f else if (lv > 1f) lv = 1f
        levelOut += (lv - levelOut) * (if (lv > levelOut) 0.5f else 0.22f)
        return levelOut
    }

    private fun applyGroove(amount: Float, loudestDb: Double) {
        val n = NUM_BARS
        if (!gvReady) {
            for (b in 0 until n) gvSlow[b] = gvDb[b]
            gvReady = true
        }
        val gate = ((loudestDb - FLOOR_DB) / GRV_GATE_DB).coerceIn(0.0, 1.0).toFloat()
        if (gate <= 0f) return
        val k = amount * gate
        var slowSum = 0f
        for (b in 0 until n) {
            gvSlow[b] += (gvDb[b] - gvSlow[b]) * GRV_SLOW
            slowSum += gvSlow[b]
        }
        val slowMean = slowSum / n
        gvSum[0] = 0f
        for (b in 0 until n) gvSum[b + 1] = gvSum[b] + gvDb[b]
        val r = (n * GRV_RADIUS).roundToInt().coerceIn(1, 12)
        for (b in 0 until n) {
            val lo = (b - r).coerceAtLeast(0)
            val hi = (b + r + 1).coerceAtMost(n)
            val local = (gvSum[hi] - gvSum[lo]) / (hi - lo)
            val d = gvDb[b]
            var o = d + GRV_CONTRAST * (d - local) - GRV_WHITEN * (gvSlow[b] - slowMean)
            if (o > d + GRV_MAX_DEV) o = d + GRV_MAX_DEV
            else if (o < d - GRV_MAX_DEV) o = d - GRV_MAX_DEV
            gvDb[b] = d + (o - d) * k
        }
    }

    private fun fft() {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (j > i) {
                var t = fftRe[i]; fftRe[i] = fftRe[j]; fftRe[j] = t
                t = fftIm[i]; fftIm[i] = fftIm[j]; fftIm[j] = t
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val step = FFT_SIZE / len
            val half = len / 2
            var i = 0
            while (i < FFT_SIZE) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val a = i + j
                    val b = a + half
                    val tr = fftRe[b] * wr - fftIm[b] * wi
                    val ti = fftRe[b] * wi + fftIm[b] * wr
                    fftRe[b] = fftRe[a] - tr
                    fftIm[b] = fftIm[a] - ti
                    fftRe[a] += tr
                    fftIm[a] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
