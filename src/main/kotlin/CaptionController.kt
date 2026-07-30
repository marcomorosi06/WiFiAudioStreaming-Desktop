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

import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.net.InetAddress
import java.util.prefs.Preferences

data class CaptionSettings(
    val enabled: Boolean = false,
    val engine: AsrEngineKind = AsrEngineKind.WHISPER_CPP,
    val modelId: String = "",
    val runtimeId: String = "",
    val forcedBackend: AsrBackend? = null,
    val language: String = "auto"
)

object CaptionPreferences {
    private val prefs = Preferences.userRoot().node("com/mavco/wifiaudiostreamer")

    private const val ENABLED = "captions_enabled"
    private const val ENGINE = "captions_engine"
    private const val MODEL = "captions_model"
    private const val RUNTIME = "captions_runtime"
    private const val BACKEND = "captions_backend"
    private const val LANGUAGE = "captions_language"

    fun load(): CaptionSettings = CaptionSettings(
        enabled = prefs.getBoolean(ENABLED, false),
        engine = runCatching { AsrEngineKind.valueOf(prefs.get(ENGINE, "WHISPER_CPP")) }
            .getOrDefault(AsrEngineKind.WHISPER_CPP),
        modelId = prefs.get(MODEL, ""),
        runtimeId = prefs.get(RUNTIME, ""),
        forcedBackend = prefs.get(BACKEND, "").takeIf { it.isNotEmpty() }
            ?.let { runCatching { AsrBackend.valueOf(it) }.getOrNull() },
        language = prefs.get(LANGUAGE, "auto")
    )

    fun save(s: CaptionSettings) {
        prefs.putBoolean(ENABLED, s.enabled)
        prefs.put(ENGINE, s.engine.name)
        prefs.put(MODEL, s.modelId)
        prefs.put(RUNTIME, s.runtimeId)
        prefs.put(BACKEND, s.forcedBackend?.name ?: "")
        prefs.put(LANGUAGE, s.language)
        runCatching { prefs.flush() }
    }
}

object CaptionController {

    @Volatile
    var settings: CaptionSettings = CaptionPreferences.load()
        private set

    @Volatile
    private var sender: CaptionSender? = null

    @Volatile
    private var engine: AsrEngine? = null

    @Volatile
    private var lastError: String? = null

    private val downloader by lazy { CaptionDownloader(CaptionSupport.dataRoot()) }

    val isActive: Boolean get() = engine?.isRunning == true && sender?.isStreaming == true

    val error: String? get() = lastError

    fun update(s: CaptionSettings) {
        settings = s
        engineBroken = null
        CaptionPreferences.save(s)
    }

    fun captionPort(streamingPort: Int): Int? =
        if (readinessReason() == null) WfasCaptions.defaultPortFor(streamingPort) else null

    @Volatile
    private var engineBroken: String? = null

    val engineFailure: String? get() = engineBroken

    fun clearEngineFailure() {
        engineBroken = null
    }

    fun readinessReason(): String? {
        if (!CaptionSupport.isSupported) return WfasCaptions.REASON_DISABLED
        val s = settings
        if (!s.enabled) return WfasCaptions.REASON_DISABLED
        val model = CaptionCatalog.modelById(s.modelId) ?: return WfasCaptions.REASON_NO_MODEL
        if (model.engine != s.engine) return WfasCaptions.REASON_NO_MODEL
        if (!downloader.isModelInstalled(model)) return WfasCaptions.REASON_NO_MODEL
        val runtime = resolveRuntime() ?: return WfasCaptions.REASON_NO_MODEL
        if (!downloader.isRuntimeInstalled(runtime)) return WfasCaptions.REASON_NO_MODEL
        if (engineBroken != null) return WfasCaptions.REASON_NO_MODEL
        val benchmarks = CaptionBenchmark.load(CaptionSupport.dataRoot())
        val measured = benchmarks["${model.id}|${runtime.backend}"]
        if (measured != null && measured.verdict == RtfVerdict.TOO_SLOW) return WfasCaptions.REASON_TOO_LOW
        if (measured != null && measured.verdict == RtfVerdict.FAILED) return WfasCaptions.REASON_NO_MODEL
        return null
    }

    fun describeRuntimeFolder(): String {
        val runtime = resolveRuntime() ?: return "no runtime selected"
        val dir = downloader.localDirFor(runtime)
        if (!dir.isDirectory) return "runtime folder missing: ${dir.absolutePath}"
        val files = ArrayList<String>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c)
                else files.add(c.toRelativeString(dir) + " (" + c.length() / 1024 + " kB)")
            }
        }
        files.sort()
        val system32 = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
        val vcruntime = File(system32, "vcruntime140.dll").isFile
        val msvcp = File(system32, "msvcp140.dll").isFile
        return buildString {
            append(dir.absolutePath).append('\n')
            append("vcruntime140.dll present: ").append(vcruntime).append('\n')
            append("msvcp140.dll present: ").append(msvcp).append('\n')
            append(files.size).append(" files:\n")
            files.forEach { append("  ").append(it).append('\n') }
        }
    }

    fun resolveRuntime(): NativeRuntime? {
        val s = settings
        CaptionCatalog.RUNTIMES.firstOrNull { it.id == s.runtimeId }?.let { return it }
        return CaptionSupport.preferredRuntime(s.engine, s.forcedBackend)
    }

    fun modelFile(): File? {
        val model = CaptionCatalog.modelById(settings.modelId) ?: return null
        return downloader.localFileFor(model).takeIf { it.isFile }
    }

    fun runtimeDir(): File? = resolveRuntime()?.let { downloader.localDirFor(it) }

    fun onSessionStart(
        scope: CoroutineScope,
        streamingPort: Int,
        clientHost: String?,
        authKey: String,
        encrypting: Boolean,
        requireProof: Boolean,
        cnonceHex: String,
        snonceHex: String,
        audioRate: Int,
        audioChannels: Int
    ): Boolean {
        onSessionEnd()
        lastError = null

        if (readinessReason() != null) return false

        val runtime = resolveRuntime() ?: return false
        val dir = downloader.localDirFor(runtime)
        val model = modelFile() ?: return false
        val exe = runtime.serverExecutable ?: return false

        if (clientHost.isNullOrBlank()) {
            lastError = "no-client-host"
            return false
        }
        val resolved = runCatching { InetAddress.getByName(clientHost) }.getOrNull()
        if (resolved == null) {
            lastError = "bad-client-host:$clientHost"
            return false
        }

        val newSender = CaptionSender(
            scope = scope,
            port = WfasCaptions.defaultPortFor(streamingPort),
            clientAddress = resolved,
            authKey = authKey,
            encrypting = encrypting,
            cnonceHex = cnonceHex,
            snonceHex = snonceHex,
            languageTag = settings.language,
            requireProof = requireProof,
            availability = { readinessReason() }
        )
        if (!newSender.start()) {
            lastError = "cap-port-busy"
            return false
        }

        val newEngine = WhisperServerEngine(
            runtimeDir = dir,
            serverExecutable = exe,
            modelFile = model,
            backend = runtime.backend,
            language = settings.language,
            inputRate = audioRate,
            inputChannels = audioChannels,
            onResult = { r -> newSender.emit(r.text, r.samplePos, r.durMs, r.isFinal) },
            onFailure = { reason -> lastError = reason }
        )

        when (val started = newEngine.start()) {
            is AsrStartResult.Failed -> {
                lastError = started.reason
                engineBroken = started.reason
                newSender.stop()
                AppDebug.log("[CAP] engine failed to start: ${started.reason}")
                AppDebug.log("[CAP] runtime folder:\n" + describeRuntimeFolder())
                return false
            }
            AsrStartResult.Ok -> Unit
        }

        sender = newSender
        engine = newEngine
        AppDebug.log("[CAP] captions ready on port ${WfasCaptions.defaultPortFor(streamingPort)}")
        return true
    }

    fun onPcm(pcm: ShortArray, frames: Int, samplePos: Long) {
        engine?.feed(pcm, frames, samplePos)
    }

    fun onSessionEnd() {
        engine?.let { runCatching { it.stop() } }
        engine = null
        sender?.let { runCatching { it.stop() } }
        sender = null
    }
}
