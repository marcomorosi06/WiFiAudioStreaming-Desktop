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

enum class AsrEngineKind { WHISPER_CPP, SHERPA_ONNX }

enum class AsrBackend { CPU, CUDA, VULKAN, METAL, OPENCL }

data class CaptionModel(
    val id: String,
    val engine: AsrEngineKind,
    val displayName: String,
    val url: String,
    val archiveSha256: String?,
    val fileSha1: String?,
    val sizeBytes: Long,
    val multilingual: Boolean,
    val languages: List<String>,
    val streaming: Boolean,
    val approxRamMb: Int,
    val mobileSuitable: Boolean
)

data class NativeRuntime(
    val id: String,
    val engine: AsrEngineKind,
    val os: String,
    val arch: String,
    val backend: AsrBackend,
    val url: String,
    val sha256: String?,
    val sizeBytes: Long,
    val libNames: List<String>,
    val serverExecutable: String? = null
)

object CaptionCatalog {

    const val WHISPER_VERSION = "v1.8.4"
    const val SHERPA_VERSION = "v1.13.2"

    private const val HF_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val GH_WHISPER = "https://github.com/ggml-org/whisper.cpp/releases/download/$WHISPER_VERSION"
    private const val GH_SHERPA_LIB = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$SHERPA_VERSION"
    private const val GH_SHERPA_MODELS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    private const val MIB = 1024L * 1024L

    private fun whisper(
        name: String,
        display: String,
        sha1: String,
        sizeMib: Long,
        ram: Int,
        mobile: Boolean
    ) = CaptionModel(
        id = "whisper:$name",
        engine = AsrEngineKind.WHISPER_CPP,
        displayName = display,
        url = "$HF_WHISPER/ggml-$name.bin",
        archiveSha256 = null,
        fileSha1 = sha1,
        sizeBytes = sizeMib * MIB,
        multilingual = !name.contains(".en"),
        languages = if (name.contains(".en")) listOf("en") else WHISPER_LANGUAGES,
        streaming = false,
        approxRamMb = ram,
        mobileSuitable = mobile
    )

    private fun sherpa(
        archive: String,
        display: String,
        langs: List<String>,
        sizeMib: Long,
        ram: Int,
        mobile: Boolean
    ) = CaptionModel(
        id = "sherpa:$archive",
        engine = AsrEngineKind.SHERPA_ONNX,
        displayName = display,
        url = "$GH_SHERPA_MODELS/$archive.tar.bz2",
        archiveSha256 = null,
        fileSha1 = null,
        sizeBytes = sizeMib * MIB,
        multilingual = langs.size > 1,
        languages = langs,
        streaming = true,
        approxRamMb = ram,
        mobileSuitable = mobile
    )

    val WHISPER_LANGUAGES = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv",
        "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
        "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr",
        "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
        "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu",
        "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
        "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su"
    )

    val MODELS: List<CaptionModel> = listOf(
        whisper("tiny", "Whisper tiny", "bd577a113a864445d4c299885e0cb97d4ba92b5f", 75, 390, true),
        whisper("tiny.en", "Whisper tiny (English)", "c78c86eb1a8faa21b369bcd33207cc90d64ae9df", 75, 390, true),
        whisper("base", "Whisper base", "465707469ff3a37a2b9b8d8f89f2f99de7299dac", 142, 500, true),
        whisper("base.en", "Whisper base (English)", "137c40403d78fd54d454da0f9bd998f78703390c", 142, 500, true),
        whisper("small", "Whisper small", "55356645c2b361a969dfd0ef2c5a50d530afd8d5", 466, 1000, true),
        whisper("small.en", "Whisper small (English)", "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022", 466, 1000, true),
        whisper("medium", "Whisper medium", "fd9727b6e1217c2f614f9b698455c4ffd82463b4", 1536, 2600, false),
        whisper("medium.en", "Whisper medium (English)", "8c30f0e44ce9560643ebd10bbe50cd20eafd3723", 1536, 2600, false),
        whisper("large-v3-turbo-q5_0", "Whisper large-v3-turbo (q5_0)", "e050f7970618a659205450ad97eb95a18d69c9ee", 547, 1600, false),
        whisper("large-v3-turbo", "Whisper large-v3-turbo", "4af2b29d7ec73d781377bfd1758ca957a807e941", 1536, 2800, false),
        whisper("large-v3-q5_0", "Whisper large-v3 (q5_0)", "e6e2ed78495d403bef4b7cff42ef4aaadcfea8de", 1126, 2400, false),
        whisper("large-v3", "Whisper large-v3", "ad82bf6a9043ceed055076d0fd39f5f186ff8062", 2970, 4700, false),
        whisper("large-v2-q5_0", "Whisper large-v2 (q5_0)", "00e39f2196344e901b3a2bd5814807a769bd1630", 1126, 2400, false),
        whisper("large-v2", "Whisper large-v2", "0f4c8e34f21cf1a914c59d8b3ce882345ad349d6", 2970, 4700, false),

        sherpa("sherpa-onnx-streaming-zipformer-en-2023-06-26", "Zipformer streaming (English)", listOf("en"), 340, 600, true),
        sherpa("sherpa-onnx-streaming-zipformer-en-2023-06-21", "Zipformer streaming (English, 2023-06-21)", listOf("en"), 340, 600, true),
        sherpa("sherpa-onnx-streaming-zipformer-fr-2023-04-14", "Zipformer streaming (French)", listOf("fr"), 300, 600, true),
        sherpa("sherpa-onnx-streaming-zipformer-korean-2024-06-16", "Zipformer streaming (Korean)", listOf("ko"), 340, 600, true),
        sherpa("sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09", "Zipformer streaming (Bengali)", listOf("bn"), 100, 400, true),
        sherpa("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16", "Zipformer streaming small (Chinese + English)", listOf("zh", "en"), 180, 450, true),
        sherpa("sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20", "Zipformer streaming (Chinese + English)", listOf("zh", "en"), 350, 700, true),
        sherpa("sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12", "Zipformer streaming (Chinese, multi-dialect)", listOf("zh"), 340, 650, true),
        sherpa("sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30", "Zipformer streaming int8 (Chinese, 2025)", listOf("zh"), 160, 450, true)
    )

    val RUNTIMES: List<NativeRuntime> = listOf(
        NativeRuntime(
            "whisper-win-x64-cpu", AsrEngineKind.WHISPER_CPP, "windows", "x86_64", AsrBackend.CPU,
            "$GH_WHISPER/whisper-bin-x64.zip",
            "74f973345cb52ef5ba3ec9e7e7af8e48cc8c71722d1528603b80588a11f82e3e",
            4078768L, listOf("whisper.dll", "ggml.dll", "ggml-base.dll", "ggml-cpu.dll"), "whisper-server.exe"
        ),
        NativeRuntime(
            "whisper-win-x64-blas", AsrEngineKind.WHISPER_CPP, "windows", "x86_64", AsrBackend.CPU,
            "$GH_WHISPER/whisper-blas-bin-x64.zip",
            "d85e60bdba2dcb35cf42fd07c0cd1481ef6ca631f81872c1f2204ea8cdb7d001",
            16645654L, listOf("whisper.dll", "ggml.dll", "ggml-base.dll", "ggml-cpu.dll", "openblas.dll"), "whisper-server.exe"
        ),
        NativeRuntime(
            "whisper-win-x64-cuda118", AsrEngineKind.WHISPER_CPP, "windows", "x86_64", AsrBackend.CUDA,
            "$GH_WHISPER/whisper-cublas-11.8.0-bin-x64.zip",
            "194480dd24606389c4eceb98d48292f37f59138192ae491ed4f38736cdb8888c",
            58787783L, listOf("whisper.dll", "ggml.dll", "ggml-base.dll", "ggml-cpu.dll", "ggml-cuda.dll"), "whisper-server.exe"
        ),
        NativeRuntime(
            "whisper-win-x64-cuda124", AsrEngineKind.WHISPER_CPP, "windows", "x86_64", AsrBackend.CUDA,
            "$GH_WHISPER/whisper-cublas-12.4.0-bin-x64.zip",
            "b07cff4e59831b227896018facbb6334907bf324a342c84597c44f087823d252",
            457024596L, listOf("whisper.dll", "ggml.dll", "ggml-base.dll", "ggml-cpu.dll", "ggml-cuda.dll"), "whisper-server.exe"
        ),
        NativeRuntime(
            "sherpa-win-x64-cuda", AsrEngineKind.SHERPA_ONNX, "windows", "x86_64", AsrBackend.CUDA,
            "$GH_SHERPA_LIB/sherpa-onnx-$SHERPA_VERSION-cuda-12.x-cudnn-9.x-win-x64-cuda.tar.bz2",
            null, 0L, listOf("sherpa-onnx-c-api.dll", "onnxruntime.dll")
        ),
        NativeRuntime(
            "sherpa-android", AsrEngineKind.SHERPA_ONNX, "android", "*", AsrBackend.CPU,
            "$GH_SHERPA_LIB/sherpa-onnx-$SHERPA_VERSION-android.tar.bz2",
            null, 0L, listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")
        )
    )

    val SUPPORTED_DESKTOP_OS: Set<String> = setOf("windows")

    val SUPPORTED_ENGINES_ANDROID: Set<AsrEngineKind> = setOf(AsrEngineKind.SHERPA_ONNX)

    fun modelById(id: String): CaptionModel? = MODELS.firstOrNull { it.id == id }

    fun modelsFor(engine: AsrEngineKind, mobileOnly: Boolean): List<CaptionModel> =
        MODELS.filter { it.engine == engine && (!mobileOnly || it.mobileSuitable) }

    fun modelsForLanguage(lang: String): List<CaptionModel> =
        MODELS.filter { it.languages.contains(lang) }

    fun runtimesFor(engine: AsrEngineKind, os: String, arch: String): List<NativeRuntime> =
        RUNTIMES.filter {
            it.engine == engine && it.os == os && (it.arch == "*" || it.arch == arch)
        }

    fun pinnedRuntimes(): List<NativeRuntime> = RUNTIMES.filter { it.sha256 != null }

    fun unpinnedRuntimes(): List<NativeRuntime> = RUNTIMES.filter { it.sha256 == null }
}
