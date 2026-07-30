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

object CaptionSupport {

    private val osRaw: String = System.getProperty("os.name")?.lowercase() ?: ""
    private val archRaw: String = System.getProperty("os.arch")?.lowercase() ?: ""

    val os: String = when {
        osRaw.contains("win") -> "windows"
        osRaw.contains("mac") -> "macos"
        else -> "linux"
    }

    val arch: String = when {
        archRaw.contains("aarch64") || archRaw.contains("arm64") -> "arm64"
        else -> "x86_64"
    }

    val isSupported: Boolean = os in CaptionCatalog.SUPPORTED_DESKTOP_OS && arch == "x86_64"

    val unsupportedReason: String? = when {
        isSupported -> null
        os != "windows" -> "captions.unsupported.os"
        else -> "captions.unsupported.arch"
    }

    fun availableRuntimes(engine: AsrEngineKind): List<NativeRuntime> =
        if (!isSupported) emptyList() else CaptionCatalog.runtimesFor(engine, os, arch)

    fun availableEngines(): List<AsrEngineKind> =
        if (!isSupported) emptyList()
        else AsrEngineKind.values().filter { availableRuntimes(it).any { r -> r.sha256 != null } }

    fun availableModels(engine: AsrEngineKind): List<CaptionModel> =
        if (!isSupported) emptyList() else CaptionCatalog.modelsFor(engine, mobileOnly = false)

    fun dataRoot(): File {
        val base = System.getenv("LOCALAPPDATA")
            ?: System.getProperty("user.home")
        return File(File(base, "WiFiAudioStreaming"), "captions")
    }

    fun detectBackends(): List<AsrBackend> {
        if (!isSupported) return emptyList()
        val out = mutableListOf(AsrBackend.CPU)
        if (hasNvidiaDriver()) out.add(AsrBackend.CUDA)
        return out
    }

    private fun hasNvidiaDriver(): Boolean {
        val system32 = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
        if (File(system32, "nvcuda.dll").isFile) return true
        return try {
            val p = ProcessBuilder("nvidia-smi", "-L")
                .redirectErrorStream(true)
                .start()
            val ok = p.waitFor() == 0
            p.destroy()
            ok
        } catch (_: Exception) {
            false
        }
    }

    fun preferredRuntime(engine: AsrEngineKind, forced: AsrBackend?): NativeRuntime? {
        val candidates = availableRuntimes(engine).filter { it.sha256 != null }
        if (candidates.isEmpty()) return null
        if (forced == AsrBackend.CUDA) {
            return candidates.firstOrNull { it.id.endsWith("cuda124") }
                ?: candidates.firstOrNull { it.backend == AsrBackend.CUDA }
        }
        return candidates.firstOrNull { it.id.endsWith("blas") }
            ?: candidates.firstOrNull { it.backend == AsrBackend.CPU }
    }
}
