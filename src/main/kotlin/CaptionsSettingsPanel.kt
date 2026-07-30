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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format("%.0f MB", bytes / 1_048_576.0)
    else -> String.format("%.0f kB", bytes / 1024.0)
}

private fun verdictKey(v: RtfVerdict): String = when (v) {
    RtfVerdict.EXCELLENT -> "captions_verdict_excellent"
    RtfVerdict.USABLE -> "captions_verdict_usable"
    RtfVerdict.TOO_SLOW -> "captions_verdict_too_slow"
    RtfVerdict.FAILED -> "captions_verdict_failed"
}

@Composable
fun CaptionsSettingsPanel(modifier: Modifier = Modifier) {

    if (!CaptionSupport.isSupported) {
        Text(
            stringResource(CaptionSupport.unsupportedReason ?: "captions_unsupported_os"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(CaptionController.settings) }
    var benchmarks by remember { mutableStateOf(CaptionBenchmark.load(CaptionSupport.dataRoot())) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var pendingModelDelete by remember { mutableStateOf<CaptionModel?>(null) }
    var pendingRuntimeDelete by remember { mutableStateOf<NativeRuntime?>(null) }

    val downloader = remember { CaptionDownloader(CaptionSupport.dataRoot()) }
    val detected = remember { CaptionSupport.detectBackends() }

    fun persist(next: CaptionSettings) {
        settings = next
        CaptionController.update(next)
    }

    val runtime = remember(settings, refreshTick) { CaptionController.resolveRuntime() }
    val runtimeReady = remember(runtime, refreshTick) {
        runtime != null && downloader.isRuntimeInstalled(runtime)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource("captions_summary"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { persist(settings.copy(enabled = it)) }
                )
            }

            if (settings.enabled) {
                Divider()

                Text(
                    stringResource("captions_engine_title"),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kind in AsrEngineKind.values()) {
                        val labelKey = if (kind == AsrEngineKind.WHISPER_CPP)
                            "captions_engine_whisper" else "captions_engine_sherpa"
                        FilterChip(
                            selected = settings.engine == kind,
                            onClick = { persist(settings.copy(engine = kind, modelId = "")) },
                            label = { Text(stringResource(labelKey)) }
                        )
                    }
                }
                Text(
                    stringResource(
                        if (settings.engine == AsrEngineKind.WHISPER_CPP)
                            "captions_engine_whisper_desc" else "captions_engine_sherpa_desc"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Divider()

                Text(
                    stringResource("captions_backend_title"),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.forcedBackend == null,
                        onClick = { persist(settings.copy(forcedBackend = null)) },
                        label = { Text(stringResource("captions_backend_auto")) }
                    )
                    for (b in detected) {
                        val key = if (b == AsrBackend.CUDA) "captions_backend_cuda" else "captions_backend_cpu"
                        FilterChip(
                            selected = settings.forcedBackend == b,
                            onClick = { persist(settings.copy(forcedBackend = b)) },
                            label = { Text(stringResource(key)) }
                        )
                    }
                }
                Text(
                    stringResource("captions_backend_detected", detected.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (settings.forcedBackend == AsrBackend.CUDA) {
                    Text(
                        stringResource("captions_backend_cuda_note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (runtimeReady && runtime != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = { pendingRuntimeDelete = runtime }
                        ) { Text(stringResource("captions_runtime_delete")) }
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = { message = CaptionController.describeRuntimeFolder() }
                        ) { Text(stringResource("captions_diagnostics")) }
                    }
                }

                CaptionController.engineFailure?.let { failure ->
                    Text(
                        failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (!runtimeReady && runtime != null) {
                    Divider()
                    Text(
                        stringResource("captions_runtime_title"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource("captions_runtime_missing"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (runtime.sha256 == null) {
                        Text(
                            stringResource("captions_download_unpinned"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Button(
                            enabled = busy == null,
                            onClick = {
                                scope.launch {
                                    busy = runtime.id
                                    message = null
                                    val res = withContext(Dispatchers.IO) {
                                        downloader.downloadRuntime(runtime) { progress = it }
                                    }
                                    busy = null
                                    progress = null
                                    refreshTick++
                                    message = when (res) {
                                        is DownloadResult.Failed -> when (res.reason) {
                                            CaptionDownloader.ERR_NO_PIN ->
                                                Strings.get("captions_download_unpinned")
                                            else -> Strings.get("captions_download_failed", res.reason)
                                        }
                                        else -> null
                                    }
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    "captions_runtime_download",
                                    humanBytes(runtime.sizeBytes)
                                )
                            )
                        }
                    }
                }

                Divider()

                Text(
                    stringResource("captions_model_title"),
                    style = MaterialTheme.typography.titleSmall
                )

                val models = remember(settings.engine) {
                    CaptionSupport.availableModels(settings.engine)
                }
                val backendForBench = runtime?.backend ?: AsrBackend.CPU

                for (model in models) {
                    val installed = remember(model, refreshTick) { downloader.isModelInstalled(model) }
                    val bench = benchmarks["${model.id}|$backendForBench"]

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (settings.modelId == model.id)
                                        FontWeight.SemiBold else FontWeight.Normal
                                )
                                Spacer(Modifier.width(8.dp))
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            stringResource(
                                                if (model.multilingual) "captions_model_multilingual"
                                                else "captions_model_english_only"
                                            ),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                            Text(
                                stringResource(
                                    "captions_model_size",
                                    humanBytes(model.sizeBytes),
                                    model.approxRamMb
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (bench != null) {
                                Text(
                                    if (bench.verdict == RtfVerdict.FAILED)
                                        stringResource(verdictKey(bench.verdict)) +
                                            (bench.detail?.let { ": $it" } ?: "")
                                    else Strings.get(
                                        "captions_benchmark_result",
                                        bench.rtf,
                                        bench.secondsPerMinuteOfAudio.toInt()
                                    ) + " · " + stringResource(verdictKey(bench.verdict)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (bench.verdict) {
                                        RtfVerdict.EXCELLENT -> MaterialTheme.colorScheme.primary
                                        RtfVerdict.USABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                            } else if (installed) {
                                Text(
                                    stringResource("captions_benchmark_never_run"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (installed) {
                            if (settings.modelId != model.id) {
                                OutlinedButton(
                                    enabled = busy == null,
                                    onClick = { persist(settings.copy(modelId = model.id)) }
                                ) { Text("Usa") }
                                Spacer(Modifier.width(6.dp))
                            }
                            OutlinedButton(
                                enabled = busy == null && runtimeReady,
                                onClick = {
                                    scope.launch {
                                        val rt = runtime ?: return@launch
                                        val exe = rt.serverExecutable ?: return@launch
                                        busy = model.id
                                        message = null
                                        val result = withContext(Dispatchers.IO) {
                                            CaptionBenchmark.run(
                                                runtimeDir = downloader.localDirFor(rt),
                                                serverExecutable = exe,
                                                model = model,
                                                modelFile = downloader.localFileFor(model),
                                                backend = backendForBench,
                                                language = settings.language
                                            ) { p ->
                                                message = Strings.get(
                                                    "captions_benchmark_running", p.pass, p.totalPasses
                                                )
                                            }
                                        }
                                        CaptionBenchmark.store(CaptionSupport.dataRoot(), result)
                                        benchmarks = CaptionBenchmark.load(CaptionSupport.dataRoot())
                                            .toMutableMap()
                                            .also { it["${'$'}{result.modelId}|${'$'}{result.backend}"] = result }
                                        busy = null
                                        message = result.detail
                                    }
                                }
                            ) { Text(stringResource("captions_benchmark_run")) }
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(
                                enabled = busy == null,
                                onClick = { pendingModelDelete = model }
                            ) { Text(stringResource("captions_model_delete")) }
                        } else {
                            Button(
                                enabled = busy == null,
                                onClick = {
                                    scope.launch {
                                        busy = model.id
                                        message = null
                                        val res = withContext(Dispatchers.IO) {
                                            downloader.downloadModel(model) { progress = it }
                                        }
                                        busy = null
                                        progress = null
                                        refreshTick++
                                        when (res) {
                                            is DownloadResult.Done -> persist(settings.copy(modelId = model.id))
                                            is DownloadResult.Failed -> {
                                                message = when (res.reason) {
                                                    CaptionDownloader.ERR_NO_PIN ->
                                                        Strings.get("captions_download_unpinned")
                                                    else -> Strings.get("captions_download_failed", res.reason)
                                                }
                                            }
                                            DownloadResult.Cancelled -> Unit
                                        }
                                    }
                                }
                            ) { Text(stringResource("captions_model_download")) }
                        }
                    }
                    Divider()
                }

                Text(
                    stringResource("captions_benchmark_explain"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (busy != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            message ?: busy.orEmpty(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    progress?.let { p ->
                        LinearProgressIndicator(
                            progress = p.fraction,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            Strings.get(
                                "captions_download_progress",
                                (p.fraction * 100).toInt(),
                                humanBytes(p.bytesTotal)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (message != null) {
                    Text(
                        message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
    }

    pendingModelDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingModelDelete = null },
            title = { Text(stringResource("captions_model_delete_title")) },
            text = {
                Text(
                    Strings.get(
                        "captions_model_delete_body",
                        target.displayName,
                        humanBytes(target.sizeBytes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingModelDelete = null
                    val ok = downloader.removeModel(target)
                    if (ok) {
                        CaptionBenchmark.forget(CaptionSupport.dataRoot(), target.id)
                        benchmarks = CaptionBenchmark.load(CaptionSupport.dataRoot())
                        if (settings.modelId == target.id) persist(settings.copy(modelId = ""))
                        message = null
                    } else {
                        message = Strings.get("captions_delete_failed")
                    }
                    refreshTick++
                }) { Text(stringResource("captions_delete_ok")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingModelDelete = null }) {
                    Text(stringResource("captions_delete_cancel"))
                }
            }
        )
    }

    pendingRuntimeDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRuntimeDelete = null },
            title = { Text(stringResource("captions_runtime_delete_title")) },
            text = { Text(stringResource("captions_runtime_delete_body")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRuntimeDelete = null
                    message = if (downloader.removeRuntime(target)) null
                    else Strings.get("captions_delete_failed")
                    refreshTick++
                }) { Text(stringResource("captions_delete_ok")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRuntimeDelete = null }) {
                    Text(stringResource("captions_delete_cancel"))
                }
            }
        )
    }
}
