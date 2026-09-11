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
 *
 * --------------------------------------------------------------------------
 * RtpReceiveUi.kt
 *
 * Card "Sorgente RTP / SDP" nella sezione Ricevi, sotto la lista dei server
 * WFAS rilevati.
 *
 * Impostazione: UN SOLO form, che e' l'unica verita'. Importare un file .sdp o
 * incollarne il testo non apre un percorso separato — riempie quel form. Cosi'
 * l'utente vede sempre cosa ha capito l'app e puo' correggerlo, invece di
 * doversi fidare di un parsing invisibile. Chi non ha un SDP compila a mano gli
 * stessi campi.
 *
 * La card resta chiusa finche' non serve: chi usa WFAS non deve inciamparci.
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun RtpReceiveCard(
    draft: RtpSource,
    onDraftChange: (RtpSource) -> Unit,
    saved: List<RtpSource>,
    onSave: (RtpSource) -> Unit,
    onForget: (RtpSource) -> Unit,
    status: RtpStatus,
    onListen: (RtpSource) -> Unit,
    onStop: () -> Unit,
    /** Senza uscita audio scelta non si puo' ascoltare: il pulsante lo dice. */
    outputReady: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    // Se un ascolto e' in corso la card si apre da sola: lo stato va visto.
    LaunchedEffect(status.active) { if (status.active) expanded = true }

    fun applyParse(text: String) {
        val res = RtpSdp.parse(text)
        parseError = res.error
        warnings = res.warnings
        res.source?.let {
            // Il nome gia' scelto dall'utente non viene sovrascritto da un s= generico.
            onDraftChange(
                it.copy(
                    name = it.name.ifBlank { draft.name },
                    sdpText = text
                )
            )
        }
    }

    val errors = remember(draft) { RtpSdp.validate(draft) }
    val canListen = outputReady && errors.isEmpty() && !status.active

    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.animateContentSize()) {

            // ── Intestazione sempre visibile ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    Icons.Outlined.SettingsInputAntenna,
                    contentDescription = null,
                    tint = if (status.active) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource("rtp_rx_title"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            status.active -> draft.displayName()
                            saved.isNotEmpty() -> stringResource("rtp_rx_subtitle_saved", saved.size)
                            else -> stringResource("rtp_rx_subtitle")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (status.active) RtpLiveDot(status.state == RtpState.PLAYING)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HorizontalDivider()

                    // ── Sorgenti salvate ────────────────────────────────────
                    if (saved.isNotEmpty()) {
                        Text(
                            stringResource("rtp_rx_saved"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            saved.forEach { s ->
                                SavedSourceRow(
                                    source = s,
                                    selected = s.address == draft.address && s.port == draft.port,
                                    enabled = !status.active,
                                    onClick = {
                                        warnings = emptyList(); parseError = null
                                        onDraftChange(s.copy(origin = RtpSourceOrigin.SAVED))
                                    },
                                    onForget = { onForget(s) }
                                )
                            }
                        }
                        HorizontalDivider()
                    }

                    // ── Due modi per riempire il form ───────────────────────
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                pickSdpFile()?.let { file ->
                                    val txt = runCatching { file.readText() }.getOrNull()
                                    if (txt == null) { parseError = "sdp_err_read"; return@let }
                                    val res = RtpSdp.parse(txt)
                                    parseError = res.error
                                    warnings = res.warnings
                                    res.source?.let { parsed ->
                                        onDraftChange(
                                            parsed.copy(
                                                // Un s= generico non deve cancellare un nome scelto
                                                // dall'utente; se non c'e' ne', vale il nome del file.
                                                name = parsed.name
                                                    .ifBlank { draft.name }
                                                    .ifBlank { file.nameWithoutExtension },
                                                sdpText = txt,
                                                origin = RtpSourceOrigin.SDP_FILE
                                            )
                                        )
                                    }
                                }
                            },
                            enabled = !status.active,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource("rtp_rx_import_file"))
                        }
                        OutlinedButton(
                            onClick = { showPaste = true },
                            enabled = !status.active,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource("rtp_rx_paste"))
                        }
                    }

                    Text(
                        stringResource("rtp_rx_form_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    parseError?.let { RtpBanner(Icons.Outlined.ErrorOutline, stringResource(it), error = true) }
                    warnings.forEach { RtpBanner(Icons.Outlined.Info, stringResource(it), error = false) }

                    // ── Il form ─────────────────────────────────────────────
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        label = { Text(stringResource("rtp_rx_name")) },
                        placeholder = { Text(draft.displayName()) },
                        singleLine = true,
                        enabled = !status.active,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.address,
                            onValueChange = { onDraftChange(draft.copy(address = it.trim())) },
                            label = { Text(stringResource("rtp_rx_address")) },
                            placeholder = { Text(stringResource("rtp_rx_address_ph")) },
                            singleLine = true,
                            isError = "rtp_val_address" in errors,
                            enabled = !status.active,
                            modifier = Modifier.weight(2f)
                        )
                        RtpNumberField(
                            label = stringResource("rtp_rx_port"),
                            value = draft.port,
                            onValue = { onDraftChange(draft.copy(port = it)) },
                            isError = "rtp_val_port" in errors,
                            enabled = !status.active,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.encoding,
                            onValueChange = { onDraftChange(draft.copy(encoding = it.trim())) },
                            label = { Text(stringResource("rtp_rx_codec")) },
                            singleLine = true,
                            enabled = !status.active,
                            modifier = Modifier.weight(1.2f)
                        )
                        RtpNumberField(
                            label = stringResource("rtp_rx_rate"),
                            value = draft.sampleRate,
                            onValue = { onDraftChange(draft.copy(sampleRate = it)) },
                            isError = "rtp_val_rate" in errors,
                            enabled = !status.active,
                            modifier = Modifier.weight(1.2f)
                        )
                        RtpNumberField(
                            label = stringResource("rtp_rx_channels"),
                            value = draft.channels,
                            onValue = { onDraftChange(draft.copy(channels = it)) },
                            isError = "rtp_val_channels" in errors,
                            enabled = !status.active,
                            modifier = Modifier.weight(0.9f)
                        )
                        RtpNumberField(
                            label = stringResource("rtp_rx_pt"),
                            value = draft.payloadType,
                            onValue = { onDraftChange(draft.copy(payloadType = it)) },
                            isError = "rtp_val_pt" in errors,
                            enabled = !status.active,
                            modifier = Modifier.weight(0.9f)
                        )
                    }

                    errors.forEach {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // ── Stato dal vivo ──────────────────────────────────────
                    AnimatedVisibility(status.active || status.state == RtpState.ERROR) {
                        RtpStatusPanel(status)
                    }

                    // ── Azioni ──────────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onSave(draft); justSaved = true },
                            enabled = !status.active && errors.isEmpty()
                        ) {
                            Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (justSaved) "rtp_rx_saved_ok" else "rtp_rx_save"))
                        }
                        Spacer(Modifier.weight(1f))
                        if (status.active) {
                            Button(
                                onClick = onStop,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource("rtp_rx_stop"))
                            }
                        } else {
                            Button(onClick = { onListen(draft) }, enabled = canListen) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource("rtp_rx_listen"))
                            }
                        }
                    }
                    if (!outputReady) {
                        Text(
                            stringResource("rtp_rx_need_output"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(draft) { justSaved = false }

    if (showPaste) {
        PasteSdpDialog(
            onDismiss = { showPaste = false },
            onAccept = { text -> applyParse(text); showPaste = false }
        )
    }
}

@Composable
private fun PasteSdpDialog(onDismiss: () -> Unit, onAccept: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("rtp_rx_paste_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource("rtp_rx_paste_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text(
                            "v=0\no=- 0 0 IN IP4 127.0.0.1\ns=stream\nc=IN IP4 239.255.0.1/4\nt=0 0\nm=audio 9094 RTP/AVP 96\na=rtpmap:96 L16/48000/2",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAccept(text) }, enabled = text.isNotBlank()) {
                Text(stringResource("rtp_rx_analyze"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("cancel")) } }
    )
}

@Composable
private fun SavedSourceRow(
    source: RtpSource,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onForget: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onClick() }
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.Bookmark, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(
                    source.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${if (source.address.isBlank()) "*" else source.address}:${source.port} · ${source.formatSummary()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onForget, enabled = enabled) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource("rtp_rx_forget"),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun RtpStatusPanel(status: RtpStatus) {
    val isError = status.state == RtpState.ERROR
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isError -> MaterialTheme.colorScheme.errorContainer
            status.state == RtpState.PLAYING -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                when {
                    isError -> Icons.Outlined.ErrorOutline
                    status.state == RtpState.PLAYING -> Icons.Outlined.GraphicEq
                    else -> Icons.Outlined.HourglassEmpty
                },
                contentDescription = null
            )
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        isError -> stringResource(status.errorKey ?: "rtp_err_socket")
                        status.state == RtpState.PLAYING -> stringResource("rtp_rx_state_playing")
                        else -> stringResource("rtp_rx_state_waiting")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val detail = when {
                    isError -> status.errorDetail
                    status.state == RtpState.PLAYING -> stringResource(
                        "rtp_rx_stats",
                        status.packets,
                        status.bytes / 1024,
                        status.lostPackets
                    )
                    else -> stringResource(
                        if (status.source?.isMulticast == true) "rtp_rx_hint_multicast"
                        else "rtp_rx_hint_firewall"
                    )
                }
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RtpLiveDot(playing: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.size(10.dp)
    ) {}
}

@Composable
private fun RtpBanner(icon: ImageVector, text: String, error: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RtpNumberField(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
    isError: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(6)
            text = digits
            digits.toIntOrNull()?.let(onValue)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/** Selettore file nativo: la app usa gia' java.awt.FileDialog altrove. */
private fun pickSdpFile(): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, Strings.get("rtp_rx_import_file"), java.awt.FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".sdp", ignoreCase = true) }
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file ?: return null
    return java.io.File(dir ?: "", file).takeIf { it.isFile }
}

/**
 * Schermata di sessione mentre si riceve RTP.
 *
 * Deve essere impossibile scambiarla per una sessione WFAS: l'icona
 * dell'antenna, la sigla RTP a caratteri grandi e una riga che lo dice a
 * parole. Sotto, i dati veri dello stream — quelli che servono quando qualcosa
 * non torna: da dove arriva, in che formato, per quale percorso di decodifica,
 * su quale uscita, e quanti pacchetti sono arrivati o persi.
 */
@Composable
fun RtpSessionPanel(
    status: RtpStatus,
    outputName: String?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val src = status.source ?: return
    val playing = status.state == RtpState.PLAYING

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiary
                ) {
                    Icon(
                        Icons.Outlined.SettingsInputAntenna,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(10.dp).size(26.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "RTP",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (playing) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (playing) Icons.Outlined.GraphicEq else Icons.Outlined.HourglassEmpty,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = if (playing) MaterialTheme.colorScheme.onTertiary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(
                                        if (playing) "rtp_rx_state_playing" else "rtp_rx_state_waiting"
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (playing) MaterialTheme.colorScheme.onTertiary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        stringResource("rtp_sess_not_wfas"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource("rtp_rx_stop"))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f))

            if (src.name.isNotBlank()) {
                Text(
                    src.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RtpInfoRow(
                    Icons.Outlined.Language,
                    stringResource("rtp_sess_lbl_address"),
                    "${if (src.address.isBlank()) "*" else src.address}:${src.port}",
                    stringResource(if (src.isMulticast) "rtp_rx_multicast" else "rtp_rx_unicast")
                )
                // Il formato mostrato e' quello misurato sul flusso, non
                // quello scritto nel modulo: mentre si ascolta conta cosa sta
                // suonando. Quando i due non coincidono, accanto resta scritto
                // cos'era stato impostato, o i numeri sembrerebbero comparsi
                // dal nulla.
                val realRate = status.detectedSampleRate
                val realCh   = status.detectedChannels
                val corrected = realRate != null && realCh != null &&
                        (realRate != src.sampleRate || realCh != src.channels)
                RtpInfoRow(
                    Icons.Outlined.Tune,
                    stringResource("rtp_sess_lbl_format"),
                    if (corrected)
                        "${src.encoding} $realRate Hz · ${if (realCh == 1) "mono" else "$realCh ch"}"
                    else src.formatSummary(),
                    if (corrected) stringResource("rtp_rx_format_was", src.formatSummary()) else null
                )
                RtpInfoRow(
                    if (status.nativePath) Icons.Outlined.Bolt else Icons.Outlined.Memory,
                    stringResource("rtp_sess_lbl_path"),
                    stringResource(if (status.nativePath) "rtp_rx_path_native" else "rtp_rx_path_ffmpeg"),
                    null
                )
                RtpInfoRow(
                    Icons.Outlined.Speaker,
                    stringResource("rtp_sess_lbl_output"),
                    outputName ?: stringResource("rtp_sess_output_default"),
                    null
                )
                RtpInfoRow(
                    Icons.Outlined.Timer,
                    stringResource("rtp_sess_lbl_buffer"),
                    stringResource("rtp_sess_buffer_ms", status.bufferMs),
                    if (playing && status.bufferMs < 25) stringResource("rtp_sess_buffer_low") else null
                )
                RtpInfoRow(
                    Icons.Outlined.Inbox,
                    stringResource("rtp_sess_lbl_received"),
                    stringResource("rtp_rx_stats", status.packets, status.bytes / 1024, status.lostPackets),
                    null
                )
            }

            if (!playing) {
                Text(
                    stringResource(
                        if (src.isMulticast) "rtp_rx_hint_multicast" else "rtp_rx_hint_firewall"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            } else if (status.bufferMs < 25) {
                Text(
                    stringResource("rtp_sess_buffer_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun RtpInfoRow(icon: ImageVector, label: String, value: String, badge: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon, null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.width(96.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
