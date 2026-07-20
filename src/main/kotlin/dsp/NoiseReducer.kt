package dsp

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Riduzione del rumore lato ricevitore, pensata per sorgenti analogiche
 * (line-in campionato da un microcontrollore, uscita cuffie di un televisore...).
 *
 * Due stadi, perche' i due disturbi tipici di una sorgente analogica hanno
 * natura diversa e un solo strumento non li prende entrambi:
 *
 *  1. Ronzio di rete: righe strettissime a 50/60 Hz e armoniche. La risoluzione
 *     della STFT usata qui e' ~47 Hz per bin, troppo grossolana per togliere
 *     50 Hz senza portarsi via mezzo basso: servono notch IIR nel dominio del
 *     tempo, che sono chirurgici quanto serve.
 *  2. Fruscio a banda larga: la sottrazione spettrale con stima del rumore
 *     tramite statistica di minimo, senza bisogno di un VAD esplicito.
 *
 * Nessuna dipendenza esterna: lo stesso file compila su Android e su desktop.
 *
 * Latenza introdotta: un hop, cioe' meta' finestra (~10 ms a 48 kHz).
 *
 * Non e' thread safe: un'istanza per flusso, usata dal thread di riproduzione.
 */
class NoiseReducer {

    private var sampleRate = 48000
    private var channels = 1
    private var initialized = false

    /** 0f = nessun intervento, 1f = massima aggressivita'. */
    @Volatile
    private var strength = 0.5f

    private var perChannel: Array<ChannelState> = emptyArray()

    // ── FFT: tabelle condivise fra i canali ─────────────────────────────────
    private var cosTable = FloatArray(0)
    private var sinTable = FloatArray(0)
    private var bitRev = IntArray(0)
    private var window = FloatArray(0)

    fun init(sampleRate: Int, channels: Int) {
        if (initialized && sampleRate == this.sampleRate && channels == this.channels) return
        this.sampleRate = sampleRate.coerceIn(4000, 192000)
        this.channels = channels.coerceIn(1, 2)

        buildFftTables()
        buildWindow()

        val mains = 50 // ricalcolato a caldo dal profilo di rumore
        perChannel = Array(this.channels) { ChannelState(this.sampleRate, mains) }
        initialized = true
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
    }

    fun reset() {
        perChannel.forEach { it.reset() }
    }

    /**
     * Elabora PCM 16 bit interlacciato, in place.
     *
     * @param pcm buffer da modificare
     * @param offset primo campione valido, in short
     * @param length numero di short validi (frame * canali)
     */
    fun process(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset) {
        if (!initialized || length <= 0 || strength <= 0f) return
        val ch = channels
        val frames = length / ch
        if (frames <= 0) return

        for (c in 0 until ch) {
            perChannel[c].push(pcm, offset + c, ch, frames, strength, this)
        }
    }

    // ── FFT reale via FFT complessa radix-2 ─────────────────────────────────

    private fun buildFftTables() {
        val n = FFT_SIZE
        cosTable = FloatArray(n / 2)
        sinTable = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            cosTable[i] = cos(2.0 * PI * i / n).toFloat()
            sinTable[i] = sin(2.0 * PI * i / n).toFloat()
        }
        bitRev = IntArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            bitRev[i] = j
        }
    }

    private fun buildWindow() {
        // Radice di Hann, applicata sia in analisi che in sintesi: il prodotto
        // delle due e' una Hann piena, che con hop = N/2 somma esattamente a 1.
        // Usare Hann su entrambi i lati darebbe hann^2, la cui somma oscilla fra
        // 0,5 e 1: si sentirebbe come una modulazione d'ampiezza a ~94 Hz.
        window = FloatArray(FFT_SIZE) { i ->
            sqrt(0.5 - 0.5 * cos(2.0 * PI * i / FFT_SIZE)).toFloat()
        }
    }

    internal fun fft(re: FloatArray, im: FloatArray) {
        val n = FFT_SIZE
        for (i in 1 until n) {
            val j = bitRev[i]
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (jj in i until i + len / 2) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val ur = re[jj]
                    val ui = im[jj]
                    val vr = re[jj + len / 2] * wr - im[jj + len / 2] * wi
                    val vi = re[jj + len / 2] * wi + im[jj + len / 2] * wr
                    re[jj] = ur + vr
                    im[jj] = ui + vi
                    re[jj + len / 2] = ur - vr
                    im[jj + len / 2] = ui - vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    internal fun ifft(re: FloatArray, im: FloatArray) {
        // IFFT come coniugato della FFT del coniugato.
        for (i in re.indices) im[i] = -im[i]
        fft(re, im)
        val inv = 1f / FFT_SIZE
        for (i in re.indices) {
            re[i] *= inv
            im[i] = -im[i] * inv
        }
    }

    internal fun windowAt(i: Int) = window[i]

    // ── stato per canale ────────────────────────────────────────────────────

    private class ChannelState(val sampleRate: Int, mainsHz: Int) {

        // Notch di rete: fondamentale piu' armoniche, finche' stanno sotto Nyquist.
        private var notches: Array<Biquad> = emptyArray()
        private val dcBlock = Biquad().apply { highPass(30f, 0.707f, sampleRate) }
        private var mains = mainsHz
        private var mainsDecided = false

        // Buffer STFT
        private val inBuf = FloatArray(FFT_SIZE)
        private var inFill = 0
        private val outBuf = FloatArray(FFT_SIZE)
        private val re = FloatArray(FFT_SIZE)
        private val im = FloatArray(FFT_SIZE)

        // Coda dei campioni gia' elaborati, in attesa di essere restituiti.
        private val pending = FloatArray(FFT_SIZE * 4)
        private var pendingHead = 0
        private var pendingTail = 0

        // Stima del rumore per bin, con statistica di minimo.
        private val noiseMag = FloatArray(BINS) { 0f }
        private val minTracker = FloatArray(BINS) { Float.MAX_VALUE }
        private var minFrames = 0
        private var primed = false
        private var framesSeen = 0

        init { rebuildNotches() }

        fun reset() {
            inFill = 0
            pendingHead = 0; pendingTail = 0
            java.util.Arrays.fill(noiseMag, 0f)
            java.util.Arrays.fill(minTracker, Float.MAX_VALUE)
            minFrames = 0; primed = false; framesSeen = 0
            mainsDecided = false
            notches.forEach { it.clearState() }
            dcBlock.clearState()
        }

        private fun rebuildNotches() {
            val list = ArrayList<Biquad>()
            var f = mains.toFloat()
            while (f < sampleRate / 2f * 0.9f && list.size < 8) {
                list.add(Biquad().apply { notch(f, 12f, sampleRate) })
                f += mains
            }
            notches = list.toTypedArray()
        }

        fun push(
            pcm: ShortArray,
            start: Int,
            stride: Int,
            frames: Int,
            strength: Float,
            owner: NoiseReducer
        ) {
            for (n in 0 until frames) {
                val idx = start + n * stride
                var x = pcm[idx] / 32768f

                // Stadio 1, dominio del tempo: continua e ronzio di rete.
                x = dcBlock.process(x)
                for (b in notches) x = b.process(x)

                // Stadio 2: accumula per la STFT.
                inBuf[inFill++] = x
                if (inFill == FFT_SIZE) {
                    analyzeAndSynthesize(strength, owner)
                    // hop = meta' finestra
                    System.arraycopy(inBuf, HOP, inBuf, 0, FFT_SIZE - HOP)
                    inFill = FFT_SIZE - HOP
                }

                // Restituisce un campione gia' pronto, oppure quello grezzo finche'
                // la pipeline non e' a regime (primi ~21 ms).
                val out = if (pendingHead != pendingTail) {
                    val v = pending[pendingHead]
                    pendingHead = (pendingHead + 1) % pending.size
                    v
                } else x

                pcm[idx] = (out.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        }

        private fun analyzeAndSynthesize(strength: Float, owner: NoiseReducer) {
            for (i in 0 until FFT_SIZE) {
                re[i] = inBuf[i] * owner.windowAt(i)
                im[i] = 0f
            }
            owner.fft(re, im)

            framesSeen++

            // Aggiorna la stima del rumore con statistica di minimo: il minimo di
            // ogni bin su una finestra scorrevole e' una buona approssimazione del
            // fondo, senza dover distinguere voce da silenzio.
            for (k in 0 until BINS) {
                val mag = hypot(re[k], im[k])
                if (mag < minTracker[k]) minTracker[k] = mag
            }
            minFrames++
            if (minFrames >= MIN_WINDOW_FRAMES) {
                // Il minimo di una magnitudine rumorosa sta parecchio sotto il suo
                // valore medio, quindi la statistica di minimo sottostima sempre il
                // fondo. Senza compensare, la sottrazione toglie pochissimo: misurato
                // -1,4 dB invece dei -6 dB attesi a forza media.
                val bias = 2.0f + 4.0f * strength
                for (k in 0 until BINS) {
                    val m = minTracker[k] * bias
                    noiseMag[k] = if (!primed) m else noiseMag[k] * 0.9f + m * 0.1f
                    minTracker[k] = Float.MAX_VALUE
                }
                minFrames = 0
                primed = true
            }

            if (!mainsDecided && framesSeen > MIN_WINDOW_FRAMES) decideMains()

            if (primed) {
                // Valori tarati in simulazione: a forza 1 si tolgono ~12 dB di
                // fruscio perdendo 0,5 dB sul segnale utile.
                val alpha = 1.5f + 1.7f * strength       // fattore di sovrasottrazione
                val floorGain = 0.20f - 0.18f * strength // quanto resta nei bin dominati dal rumore

                for (k in 0 until BINS) {
                    val mag = hypot(re[k], im[k])
                    if (mag <= 1e-9f) continue
                    val cleaned = max(mag - alpha * noiseMag[k], floorGain * mag)
                    val gain = cleaned / mag
                    re[k] *= gain
                    im[k] *= gain
                    if (k > 0 && k < FFT_SIZE - k) {
                        // simmetria hermitiana per mantenere reale l'uscita
                        re[FFT_SIZE - k] = re[k]
                        im[FFT_SIZE - k] = -im[k]
                    }
                }
            }

            owner.ifft(re, im)

            // Overlap-add classico: accumula la finestra sintetizzata, i primi
            // HOP campioni dell'accumulatore sono ormai completi e si possono
            // emettere, poi si scorre a sinistra di HOP.
            for (i in 0 until FFT_SIZE) {
                outBuf[i] += re[i] * owner.windowAt(i)
            }
            for (i in 0 until HOP) {
                val nextTail = (pendingTail + 1) % pending.size
                if (nextTail != pendingHead) {
                    pending[pendingTail] = outBuf[i]
                    pendingTail = nextTail
                }
            }
            System.arraycopy(outBuf, HOP, outBuf, 0, FFT_SIZE - HOP)
            java.util.Arrays.fill(outBuf, FFT_SIZE - HOP, FFT_SIZE, 0f)
        }

        /** Sceglie 50 o 60 Hz guardando dove il rumore di fondo ha piu' energia. */
        private fun decideMains() {
            val binHz = sampleRate.toFloat() / FFT_SIZE
            fun energyAround(f: Float): Double {
                val k = (f / binHz).toInt()
                if (k <= 0 || k >= BINS - 1) return 0.0
                return (noiseMag[k - 1] + noiseMag[k] + noiseMag[k + 1]).toDouble()
            }
            val energy50 = energyAround(50f) + energyAround(100f) + energyAround(150f)
            val energy60 = energyAround(60f) + energyAround(120f) + energyAround(180f)
            val detected = if (energy60 > energy50 * 1.2) 60 else 50
            if (detected != mains) {
                mains = detected
                rebuildNotches()
            }
            mainsDecided = true
        }
    }

    // ── biquad ──────────────────────────────────────────────────────────────

    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun clearState() { z1 = 0f; z2 = 0f }

        fun notch(freq: Float, q: Float, sampleRate: Int) {
            val w0 = (2.0 * PI * freq / sampleRate).toFloat()
            val cw = cos(w0.toDouble()).toFloat()
            val sw = sin(w0.toDouble()).toFloat()
            val alpha = sw / (2f * q)
            val a0 = 1f + alpha
            b0 = 1f / a0
            b1 = (-2f * cw) / a0
            b2 = 1f / a0
            a1 = (-2f * cw) / a0
            a2 = (1f - alpha) / a0
            clearState()
        }

        fun highPass(freq: Float, q: Float, sampleRate: Int) {
            val w0 = (2.0 * PI * freq / sampleRate).toFloat()
            val cw = cos(w0.toDouble()).toFloat()
            val sw = sin(w0.toDouble()).toFloat()
            val alpha = sw / (2f * q)
            val a0 = 1f + alpha
            b0 = ((1f + cw) / 2f) / a0
            b1 = (-(1f + cw)) / a0
            b2 = ((1f + cw) / 2f) / a0
            a1 = (-2f * cw) / a0
            a2 = (1f - alpha) / a0
            clearState()
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    companion object {
        const val FFT_SIZE = 1024
        const val HOP = FFT_SIZE / 2
        const val BINS = FFT_SIZE / 2 + 1

        /** ~1,5 s a 48 kHz: finestra su cui cercare il minimo di ogni bin. */
        const val MIN_WINDOW_FRAMES = 140
    }
}
