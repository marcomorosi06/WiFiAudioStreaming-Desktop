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

import java.io.File
import kotlin.math.PI
import kotlin.math.sin

enum class RtfVerdict { EXCELLENT, USABLE, TOO_SLOW, FAILED }

data class BenchmarkResult(
    val modelId: String,
    val backend: AsrBackend,
    val rtf: Double,
    val elapsedMs: Long,
    val audioMs: Int,
    val verdict: RtfVerdict,
    val detail: String? = null
) {
    val secondsPerMinuteOfAudio: Double get() = rtf * 60.0
}

data class BenchmarkProgress(
    val stage: String,
    val pass: Int,
    val totalPasses: Int
)

object CaptionBenchmark {

    const val EXCELLENT_BELOW = 0.30
    const val USABLE_BELOW = 0.60

    private const val AUDIO_SECONDS = 10
    private const val PASSES = 3

    fun verdictFor(rtf: Double): RtfVerdict = when {
        rtf <= 0.0 -> RtfVerdict.FAILED
        rtf < EXCELLENT_BELOW -> RtfVerdict.EXCELLENT
        rtf < USABLE_BELOW -> RtfVerdict.USABLE
        else -> RtfVerdict.TOO_SLOW
    }

    fun syntheticSpeech(seconds: Int, rate: Int = ASR_SAMPLE_RATE): FloatArray {
        val n = seconds * rate
        val out = FloatArray(n)
        var phase = 0.0
        var formantPhase = 0.0
        val rng = java.util.Random(20260730L)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val syllable = 0.5 + 0.5 * sin(2.0 * PI * 3.5 * t)
            val pitch = 110.0 + 40.0 * sin(2.0 * PI * 0.7 * t)
            phase += 2.0 * PI * pitch / rate
            formantPhase += 2.0 * PI * (700.0 + 500.0 * sin(2.0 * PI * 1.3 * t)) / rate
            val glottal = sin(phase) + 0.4 * sin(2.0 * phase) + 0.2 * sin(3.0 * phase)
            val formant = sin(formantPhase)
            val noise = (rng.nextDouble() - 0.5) * 0.06
            out[i] = ((glottal * 0.25 + formant * 0.12 + noise) * syllable).toFloat().coerceIn(-1f, 1f)
        }
        return out
    }

    fun run(
        runtimeDir: File,
        serverExecutable: String,
        model: CaptionModel,
        modelFile: File,
        backend: AsrBackend,
        language: String = "auto",
        onProgress: (BenchmarkProgress) -> Unit = {}
    ): BenchmarkResult {

        onProgress(BenchmarkProgress("loading", 0, PASSES))

        var lastFailure: String? = null

        val engine = WhisperServerEngine(
            runtimeDir = runtimeDir,
            serverExecutable = serverExecutable,
            modelFile = modelFile,
            backend = backend,
            language = language,
            inputRate = ASR_SAMPLE_RATE,
            inputChannels = 1,
            onResult = {},
            onFailure = { lastFailure = it }
        )

        when (val started = engine.start()) {
            is AsrStartResult.Failed -> return BenchmarkResult(
                model.id, backend, 0.0, 0L, AUDIO_SECONDS * 1000,
                RtfVerdict.FAILED, started.reason
            )
            AsrStartResult.Ok -> Unit
        }

        return try {
            val samples = syntheticSpeech(AUDIO_SECONDS)
            val wav = WavWriter.mono16(samples, samples.size, ASR_SAMPLE_RATE)
            val audioMs = AUDIO_SECONDS * 1000

            onProgress(BenchmarkProgress("warmup", 0, PASSES))
            engine.transcribe(wav)

            val timings = ArrayList<Long>(PASSES)
            for (pass in 1..PASSES) {
                onProgress(BenchmarkProgress("measuring", pass, PASSES))
                val t0 = System.nanoTime()
                val text = engine.transcribe(wav)
                val elapsed = (System.nanoTime() - t0) / 1_000_000L
                if (text == null) {
                    val tail = engine.processOutput()
                    return BenchmarkResult(
                        model.id, backend, 0.0, elapsed, audioMs, RtfVerdict.FAILED,
                        listOfNotNull("inference-failed", lastFailure, tail.ifEmpty { null })
                            .joinToString(" :: ")
                    )
                }
                timings.add(elapsed)
            }

            timings.sort()
            val median = timings[timings.size / 2]
            val rtf = median.toDouble() / audioMs.toDouble()
            BenchmarkResult(model.id, backend, rtf, median, audioMs, verdictFor(rtf))
        } catch (e: Exception) {
            BenchmarkResult(
                model.id, backend, 0.0, 0L, AUDIO_SECONDS * 1000,
                RtfVerdict.FAILED, "exception:${e.message}"
            )
        } finally {
            engine.stop()
        }
    }

    fun store(root: File, result: BenchmarkResult) {
        runCatching {
            val f = File(root, "benchmarks.txt")
            val existing = if (f.isFile) f.readLines().filterNot {
                it.startsWith("${result.modelId}|${result.backend}|")
            } else emptyList()
            val line = "${result.modelId}|${result.backend}|${result.rtf}|${result.elapsedMs}|${result.verdict}"
            f.writeText((existing + line).joinToString("\n"))
        }
    }

    fun forget(root: File, modelId: String) {
        runCatching {
            val f = File(root, "benchmarks.txt")
            if (!f.isFile) return
            val kept = f.readLines().filterNot { it.startsWith("$modelId|") }
            if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n"))
        }
    }

    fun load(root: File): Map<String, BenchmarkResult> {
        val f = File(root, "benchmarks.txt")
        if (!f.isFile) return emptyMap()
        val out = HashMap<String, BenchmarkResult>()
        for (line in runCatching { f.readLines() }.getOrDefault(emptyList())) {
            val p = line.split("|")
            if (p.size < 5) continue
            val backend = runCatching { AsrBackend.valueOf(p[1]) }.getOrNull() ?: continue
            val rtf = p[2].toDoubleOrNull() ?: continue
            val ms = p[3].toLongOrNull() ?: continue
            val verdict = runCatching { RtfVerdict.valueOf(p[4]) }.getOrNull() ?: continue
            out["${p[0]}|${p[1]}"] = BenchmarkResult(p[0], backend, rtf, ms, AUDIO_SECONDS * 1000, verdict)
        }
        return out
    }

    fun recommended(
        results: Map<String, BenchmarkResult>,
        backend: AsrBackend,
        engine: AsrEngineKind
    ): List<CaptionModel> =
        CaptionCatalog.modelsFor(engine, mobileOnly = false).filter {
            val r = results["${it.id}|$backend"]
            r != null && r.verdict == RtfVerdict.EXCELLENT
        }
}
