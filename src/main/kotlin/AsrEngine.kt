/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val ASR_SAMPLE_RATE = 16000

data class AsrResult(
    val text: String,
    val samplePos: Long,
    val durMs: Int,
    val isFinal: Boolean
)

sealed class AsrStartResult {
    object Ok : AsrStartResult()
    class Failed(val reason: String) : AsrStartResult()
}

interface AsrEngine {
    val isRunning: Boolean
    fun start(): AsrStartResult
    fun stop()
    fun feed(pcm: ShortArray, frames: Int, samplePos: Long)
    fun flush()
}

class MonoDownsampler(
    private val inputRate: Int,
    private val channels: Int,
    private val outputRate: Int = ASR_SAMPLE_RATE
) {
    private val taps: FloatArray = buildLowPass()
    private val history = FloatArray(taps.size)
    private var historyPos = 0
    private var phase = 0.0
    private val step = inputRate.toDouble() / outputRate.toDouble()
    private var previous = 0f

    private fun buildLowPass(): FloatArray {
        val n = 63
        val cutoff = min(7800.0, outputRate / 2.0 - 200.0) / inputRate
        val out = FloatArray(n)
        val mid = n / 2
        var sum = 0.0
        for (i in 0 until n) {
            val k = (i - mid).toDouble()
            val sinc = if (k == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * k) / (PI * k)
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
            val v = sinc * window
            out[i] = v.toFloat()
            sum += v
        }
        if (sum != 0.0) for (i in out.indices) out[i] = (out[i] / sum).toFloat()
        return out
    }

    private fun pushSample(x: Float): Float {
        history[historyPos] = x
        historyPos = (historyPos + 1) % history.size
        var acc = 0f
        var idx = historyPos
        for (t in taps.indices) {
            acc += taps[t] * history[idx]
            idx = (idx + 1) % history.size
        }
        return acc
    }

    fun process(pcm: ShortArray, frames: Int, out: MutableList<Float>) {
        if (inputRate == outputRate && channels == 1) {
            for (i in 0 until frames) out.add(pcm[i] / 32768f)
            return
        }
        for (f in 0 until frames) {
            var mono = 0f
            val base = f * channels
            for (c in 0 until channels) mono += pcm[base + c] / 32768f
            mono /= channels
            val filtered = pushSample(mono)
            while (phase < 1.0) {
                val frac = phase.toFloat()
                out.add(previous + (filtered - previous) * frac)
                phase += step
            }
            phase -= 1.0
            previous = filtered
        }
    }

    fun reset() {
        java.util.Arrays.fill(history, 0f)
        historyPos = 0
        phase = 0.0
        previous = 0f
    }
}

object WavWriter {
    fun mono16(samples: FloatArray, count: Int, rate: Int): ByteArray {
        val dataBytes = count * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(16)
        le16(1)
        le16(1)
        le32(rate)
        le32(rate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        le32(dataBytes)
        for (i in 0 until count) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            val s = (clamped * 32767f).toInt()
            le16(s and 0xFFFF)
        }
        return out.toByteArray()
    }
}

class EnergyVad(
    private val rate: Int = ASR_SAMPLE_RATE,
    private val frameMs: Int = 20,
    private val minSpeechMs: Int = 250,
    private val minSilenceMs: Int = 500
) {
    private val frameSize = rate * frameMs / 1000
    private var noiseFloor = 0.003f
    private var speechFrames = 0
    private var silenceFrames = 0
    private var inSpeech = false

    val isSpeaking: Boolean get() = inSpeech

    fun feed(buffer: FloatArray, from: Int, to: Int): Boolean {
        var closed = false
        var i = from
        while (i + frameSize <= to) {
            var acc = 0.0
            for (k in i until i + frameSize) acc += buffer[k].toDouble() * buffer[k]
            val rms = sqrt(acc / frameSize).toFloat()
            val threshold = maxOf(noiseFloor * 3f, 0.004f)
            if (rms > threshold) {
                speechFrames++
                silenceFrames = 0
                if (!inSpeech && speechFrames * frameMs >= minSpeechMs) inSpeech = true
            } else {
                noiseFloor = noiseFloor * 0.97f + rms * 0.03f
                silenceFrames++
                speechFrames = 0
                if (inSpeech && silenceFrames * frameMs >= minSilenceMs) {
                    inSpeech = false
                    closed = true
                }
            }
            i += frameSize
        }
        return closed
    }

    fun reset() {
        speechFrames = 0
        silenceFrames = 0
        inSpeech = false
        noiseFloor = 0.003f
    }
}

object JsonText {
    fun extractText(json: String): String? {
        val key = "\"text\""
        var i = json.indexOf(key)
        if (i < 0) return null
        i += key.length
        while (i < json.length && json[i] != ':') i++
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> return sb.toString().trim()
                c == '\\' -> {
                    i++
                    if (i >= json.length) return sb.toString().trim()
                    when (val e = json[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '/' -> sb.append('/')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'u' -> {
                            if (i + 4 < json.length) {
                                val hex = json.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    i += 4
                                }
                            }
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim()
    }
}

object AsrTextUtil {
    fun isBlank(text: String): Boolean {
        if (text.isBlank()) return true
        val stripped = text.filter { it.isLetterOrDigit() }
        if (stripped.isEmpty()) return true
        return BRACKETED.matches(text.trim())
    }

    private val BRACKETED = Regex("^[\\[(*][^\\]) *]*[\\])*]$")
}
