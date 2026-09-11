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
 * SnapcastClientUi.kt
 *
 * Interfaccia del client Snapcast.
 *
 * Un server Snapcast e un server WFAS non sono la stessa cosa e non devono
 * sembrarlo: WFAS e' un collegamento diretto a una macchina, Snapcast e' una
 * stanza dentro un impianto multi-room dove altri stanno gia' ascoltando. Nella
 * lista dei dispositivi trovati la sezione Snapcast e' quindi staccata,
 * colorata diversamente e marchiata, e ogni riga dice quello che conta li':
 * quanti client e quale stream, non "porta e IP".
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Quanto a lungo il valore scelto dall'utente resta padrone dopo un comando.
 *
 * Il server risponde con le sue notifiche, ma puo' anche spedire una fotografia
 * dello stato scattata PRIMA che il comando arrivasse: applicarla riporterebbe
 * lo slider al valore vecchio, per poi rimandarlo avanti un attimo dopo. E'
 * quello che si vede come lampeggio.
 */
private const val SNAP_HOLD_AFTER_SET_MS = 900L

/* ═══════════════════════════════════════════════════════════════════════════
 *  Sezione dentro la card dei dispositivi trovati
 * ═══════════════════════════════════════════════════════════════════════════*/

@Composable
fun SnapcastDiscoverySection(
    servers: List<SnapcastServerRef>,
    onConnect: (SnapcastServerRef) -> Unit,
    enabled: Boolean
) {
    if (servers.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HorizontalDivider(Modifier.weight(1f))
            Icon(
                Icons.Outlined.SpeakerGroup, null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                stringResource("snap_section_title"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            HorizontalDivider(Modifier.weight(1f))
        }

        servers.forEach { s ->
            SnapcastServerRow(server = s, enabled = enabled, onClick = { onConnect(s) })
        }
    }
}

@Composable
private fun SnapcastServerRow(
    server: SnapcastServerRef,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icona piena in cerchio: stacca a colpo d'occhio dalle righe WFAS.
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.SpeakerGroup, null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        server.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    SnapBadge()
                }
                Text(
                    stringResource("snap_row_subtitle", server.host, server.streamPort),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                Icons.Filled.PlayArrow, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun SnapBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondary
    ) {
        Text(
            "SNAPCAST",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Connessione manuale (sopra la card RTP)
 * ═══════════════════════════════════════════════════════════════════════════*/

@Composable
fun SnapcastConnectCard(
    host: String,
    onHostChange: (String) -> Unit,
    streamPort: Int,
    onStreamPortChange: (Int) -> Unit,
    controlPort: Int,
    onControlPortChange: (Int) -> Unit,
    saved: List<SnapcastServerRef>,
    onSave: (SnapcastServerRef) -> Unit,
    onForget: (SnapcastServerRef) -> Unit,
    onConnect: (SnapcastServerRef) -> Unit,
    busy: Boolean,
    outputReady: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val valid = host.isNotBlank() && streamPort in 1..65535 && controlPort in 1..65535

    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    Icons.Outlined.SpeakerGroup, null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource("snap_manual_title"), style = MaterialTheme.typography.titleMedium)
                        SnapBadge()
                    }
                    Text(
                        if (saved.isNotEmpty()) stringResource("snap_manual_saved", saved.size)
                        else stringResource("snap_manual_subtitle"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HorizontalDivider()

                    if (saved.isNotEmpty()) {
                        Text(
                            stringResource("snap_manual_saved_title"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        saved.forEach { s ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !busy) { onConnect(s) }
                                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Outlined.Bookmark, null, Modifier.size(18.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            s.displayName(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "${s.host}:${s.streamPort}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onForget(s) }, enabled = !busy) {
                                        Icon(Icons.Outlined.Close, stringResource("snap_forget"),
                                             Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }

                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text(stringResource("snap_host")) },
                        placeholder = { Text("192.168.1.10") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SnapNumberField(
                            stringResource("snap_stream_port"), streamPort, onStreamPortChange,
                            !busy, Modifier.weight(1f)
                        )
                        SnapNumberField(
                            stringResource("snap_control_port"), controlPort, onControlPortChange,
                            !busy, Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource("snap_ports_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                onSave(SnapcastServerRef("", host, streamPort, controlPort, discovered = false))
                            },
                            enabled = valid && !busy
                        ) {
                            Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource("snap_save"))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                onConnect(SnapcastServerRef("", host, streamPort, controlPort, discovered = false))
                            },
                            enabled = valid && !busy && outputReady
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource("snap_connect"))
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
}

@Composable
private fun SnapNumberField(
    label: String, value: Int, onValue: (Int) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(5)
            text = digits
            digits.toIntOrNull()?.let(onValue)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Schermata di sessione: stato + mixer di tutto l'impianto
 * ═══════════════════════════════════════════════════════════════════════════*/

@Composable
fun SnapcastSessionPanel(
    stream: SnapStreamStatus,
    control: SnapControlStatus,
    /** Il nostro id, per marcare "questo computer" nella lista. */
    selfClientId: String,
    onStop: () -> Unit,
    onSetVolume: (clientId: String, percent: Int, muted: Boolean) -> Unit,
    onSetName: (clientId: String, name: String) -> Unit,
    onSetLatency: (clientId: String, ms: Int) -> Unit,
    onGroupMute: (groupId: String, mute: Boolean) -> Unit,
    onGroupStream: (groupId: String, streamId: String) -> Unit,
    onGroupName: (groupId: String, name: String) -> Unit,
    /** Sposta un client in un altro gruppo: in Snapcast un client sta in uno solo. */
    onMoveClient: (clientId: String, targetGroupId: String) -> Unit,
    /** Stacca un client dal suo gruppo mettendolo da solo in uno nuovo. */
    onSplitClient: (clientId: String) -> Unit,
    /**
     * false quando si e' scoperto che questo server non sa rialloggiare un
     * client staccato. Non si puo' sapere in anticipo: si prova una volta, si
     * vede com'e' andata, e da li' in poi non si propone piu'.
     */
    splitSupported: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playing = stream.state == SnapStreamState.PLAYING

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ── Intestazione ────────────────────────────────────────────────────
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.secondary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.SpeakerGroup, null,
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "SNAPCAST",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (playing) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    stringResource(
                                        when (stream.state) {
                                            SnapStreamState.PLAYING   -> "snap_state_playing"
                                            SnapStreamState.BUFFERING -> "snap_state_buffering"
                                            SnapStreamState.CONNECTING -> "snap_state_connecting"
                                            SnapStreamState.ERROR     -> "snap_state_error"
                                            else -> "snap_state_idle"
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (playing) MaterialTheme.colorScheme.onSecondary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(
                            stringResource("snap_sess_not_wfas"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
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

                if (stream.state == SnapStreamState.ERROR && stream.errorKey != null) {
                    Text(
                        stringResource(stream.errorKey) +
                            (stream.errorDetail?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f)
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SnapInfoRow(Icons.Outlined.Dns, stringResource("snap_lbl_server"),
                        stream.server?.let { "${it.displayName()} · ${it.host}:${it.streamPort}" } ?: "—")
                    SnapInfoRow(Icons.Outlined.Tune, stringResource("snap_lbl_format"),
                        if (stream.sampleRate > 0)
                            "${stream.codec.uppercase()} · ${stream.sampleRate} Hz · ${stream.channels} ch"
                        else stream.codec.uppercase().ifBlank { "—" })
                    // Due numeri diversi: quello che il server ordina e quello
                    // che c'e' davvero in coda. Se il secondo balla verso lo
                    // zero, l'audio arriva a singhiozzo.
                    SnapInfoRow(Icons.Outlined.Timer, stringResource("snap_lbl_buffer"),
                        stringResource("snap_buffer_value", stream.bufferMs, stream.playoutBufferMs))
                    SnapInfoRow(Icons.Outlined.Sync, stringResource("snap_lbl_sync"),
                        stringResource("snap_sync_value", stream.syncErrorMs, stream.clockOffsetMs))
                }
            }
        }

        // ── Mixer dell'impianto ─────────────────────────────────────────────
        ElevatedCard(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(stringResource("snap_mixer_title"),
                             style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (control.state) {
                                SnapControlState.CONNECTED ->
                                    stringResource("snap_mixer_subtitle",
                                        control.status.allClients.size, control.status.groups.size)
                                SnapControlState.CONNECTING -> stringResource("snap_mixer_connecting")
                                SnapControlState.ERROR -> stringResource("snap_mixer_error")
                                else -> stringResource("snap_mixer_idle")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, stringResource("refresh"))
                    }
                }

                if (control.state != SnapControlState.CONNECTED) {
                    Text(
                        stringResource("snap_mixer_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Il perche' esatto, in chiaro. "Non raggiungibile" non
                    // distingue un rifiuto da un timeout da una caduta dopo
                    // l'apertura, e senza questo dettaglio l'unico modo di
                    // saperlo e' leggere il log dell'applicazione.
                    if (control.state == SnapControlState.ERROR || control.errorDetail != null) {
                        SnapControlDiagnostics(control)
                    }
                } else if (control.status.groups.isEmpty()) {
                    Text(
                        stringResource("snap_mixer_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (!splitSupported) {
                        Text(
                            stringResource("snap_split_unsupported"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    control.status.groups.forEach { group ->
                        SnapGroupBlock(
                            group = group,
                            allGroups = control.status.groups,
                            streams = control.status.streams,
                            selfClientId = selfClientId,
                            onSetVolume = onSetVolume,
                            onSetName = onSetName,
                            onSetLatency = onSetLatency,
                            onGroupMute = onGroupMute,
                            onGroupStream = onGroupStream,
                            onGroupName = onGroupName,
                            onMoveClient = onMoveClient,
                            onSplitClient = onSplitClient,
                            splitSupported = splitSupported
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapInfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp),
             tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
        Text(label, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
             modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
             color = MaterialTheme.colorScheme.onSecondaryContainer,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SnapGroupBlock(
    group: SnapGroupInfo,
    allGroups: List<SnapGroupInfo>,
    streams: List<SnapStreamInfo>,
    selfClientId: String,
    onSetVolume: (String, Int, Boolean) -> Unit,
    onSetName: (String, String) -> Unit,
    onSetLatency: (String, Int) -> Unit,
    onGroupMute: (String, Boolean) -> Unit,
    onGroupStream: (String, String) -> Unit,
    onGroupName: (String, String) -> Unit,
    onMoveClient: (String, String) -> Unit,
    onSplitClient: (String) -> Unit,
    splitSupported: Boolean
) {
    var streamMenu by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var draftGroupName by remember(group.name) { mutableStateOf(group.name) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.Workspaces, null, Modifier.size(18.dp),
                     tint = MaterialTheme.colorScheme.secondary)
                if (editingName) {
                    OutlinedTextField(
                        value = draftGroupName,
                        onValueChange = { draftGroupName = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            IconButton(onClick = {
                                editingName = false
                                if (draftGroupName != group.name) onGroupName(group.id, draftGroupName)
                            }) { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }
                        }
                    )
                } else {
                    Text(
                        group.displayName(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { draftGroupName = group.name; editingName = true },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Outlined.Edit, stringResource("snap_group_rename"), Modifier.size(15.dp))
                    }
                }

                // Stream sorgente del gruppo
                Box {
                    AssistChip(
                        onClick = { streamMenu = true },
                        label = {
                            Text(group.streamId.ifBlank { stringResource("snap_no_stream") },
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingIcon = { Icon(Icons.Outlined.QueueMusic, null, Modifier.size(16.dp)) }
                    )
                    DropdownMenu(expanded = streamMenu, onDismissRequest = { streamMenu = false }) {
                        streams.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(s.id)
                                        Text(s.status,
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { streamMenu = false; onGroupStream(group.id, s.id) }
                            )
                        }
                    }
                }

                IconButton(onClick = { onGroupMute(group.id, !group.muted) }) {
                    Icon(
                        if (group.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                        contentDescription = stringResource("snap_group_mute"),
                        tint = if (group.muted) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (group.clients.isEmpty()) {
                Text(stringResource("snap_group_empty"),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                group.clients.forEach { c ->
                    SnapClientRow(
                        client = c,
                        isSelf = c.id == selfClientId,
                        groupMuted = group.muted,
                        otherGroups = allGroups.filter { it.id != group.id },
                        // Staccarlo ha senso solo se non e' gia' solo: altrimenti
                        // lo si toglierebbe da un gruppo per rimetterlo in un
                        // gruppo nuovo altrettanto solitario.
                        // Per noi stessi si puo' sempre: se il server non
                        // rialloggia chi si stacca, ci ripresentiamo da capo
                        // e un gruppo nuovo ce lo da' lui. Per gli altri no:
                        // la riconnessione altrui non la comanda nessuno.
                        canSplit = group.clients.size > 1 &&
                                   (c.id == selfClientId || splitSupported),
                        onSetVolume = onSetVolume,
                        onSetName = onSetName,
                        onSetLatency = onSetLatency,
                        onMoveClient = onMoveClient,
                        onSplitClient = onSplitClient
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapClientRow(
    client: SnapClientInfo,
    isSelf: Boolean,
    groupMuted: Boolean,
    otherGroups: List<SnapGroupInfo>,
    canSplit: Boolean,
    onSetVolume: (String, Int, Boolean) -> Unit,
    onSetName: (String, String) -> Unit,
    onSetLatency: (String, Int) -> Unit,
    onMoveClient: (String, String) -> Unit,
    onSplitClient: (String) -> Unit
) {
    var moveMenu by remember { mutableStateOf(false) }
    // Lo slider segue il dito subito; il valore dal server riprende il comando
    // solo quando non stiamo trascinando, altrimenti scatterebbe sotto le dita.
    // Il valore locale comanda mentre si trascina e per un momento dopo aver
    // rilasciato; passata quella finestra si torna ad allinearsi al server, che
    // resta comunque l'ultima parola (puo' rifiutare o limitare un valore).
    var interacting by remember { mutableStateOf(false) }
    var holdUntil by remember { mutableStateOf(0L) }
    var localVolume by remember { mutableStateOf(client.volumePercent.toFloat()) }

    LaunchedEffect(client.volumePercent, interacting) {
        if (interacting) return@LaunchedEffect
        val wait = holdUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (!interacting) localVolume = client.volumePercent.toFloat()
    }

    var editing by remember { mutableStateOf(false) }
    var draftName by remember(client.name) { mutableStateOf(client.name) }
    var showLatency by remember { mutableStateOf(false) }

    var latInteracting by remember { mutableStateOf(false) }
    var latHoldUntil by remember { mutableStateOf(0L) }
    var latDraft by remember { mutableStateOf(client.latencyMs.toFloat()) }

    LaunchedEffect(client.latencyMs, latInteracting) {
        if (latInteracting) return@LaunchedEffect
        val wait = latHoldUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (!latInteracting) latDraft = client.latencyMs.toFloat()
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelf) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
               verticalArrangement = Arrangement.spacedBy(6.dp)) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (client.connected) Icons.Outlined.Speaker else Icons.Outlined.SpeakerNotesOff,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = if (client.connected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                if (editing) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            IconButton(onClick = {
                                editing = false
                                if (draftName != client.name) onSetName(client.id, draftName)
                            }) { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }
                        }
                    )
                } else {
                    Text(
                        client.displayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (isSelf) {
                        Surface(shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary) {
                            Text(
                                stringResource("snap_this_computer"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        "${localVolume.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { draftName = client.name; editing = true },
                               modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Edit, stringResource("snap_rename"), Modifier.size(15.dp))
                    }
                    IconButton(onClick = { showLatency = !showLatency },
                               modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.MoreTime, stringResource("snap_latency"), Modifier.size(15.dp))
                    }
                    // Il pulsante serve anche quando non ci sono altri gruppi:
                    // spostando via l'ultimo client il server cancella il gruppo
                    // vuoto, e senza la voce "gruppo nuovo" non resterebbe nulla
                    // verso cui spostarsi — il client rimarrebbe intrappolato.
                    if (otherGroups.isNotEmpty() || canSplit) {
                        Box {
                            IconButton(onClick = { moveMenu = true },
                                       modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Outlined.DriveFileMove,
                                     stringResource("snap_move_group"), Modifier.size(15.dp))
                            }
                            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                                Text(
                                    stringResource("snap_move_to"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                otherGroups.forEach { g ->
                                    DropdownMenuItem(
                                        text = { Text(g.displayName()) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Workspaces, null, Modifier.size(16.dp))
                                        },
                                        onClick = { moveMenu = false; onMoveClient(client.id, g.id) }
                                    )
                                }
                                if (canSplit) {
                                    if (otherGroups.isNotEmpty()) HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource("snap_move_new_group")) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(16.dp))
                                        },
                                        onClick = { moveMenu = false; onSplitClient(client.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = {
                        holdUntil = System.currentTimeMillis() + SNAP_HOLD_AFTER_SET_MS
                        onSetVolume(client.id, localVolume.toInt(), !client.muted)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (client.muted || groupMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                        contentDescription = stringResource("snap_mute"),
                        modifier = Modifier.size(18.dp),
                        tint = if (client.muted) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = localVolume,
                    onValueChange = { interacting = true; localVolume = it },
                    onValueChangeFinished = {
                        interacting = false
                        holdUntil = System.currentTimeMillis() + SNAP_HOLD_AFTER_SET_MS
                        onSetVolume(client.id, localVolume.toInt(), client.muted)
                    },
                    valueRange = 0f..100f,
                    enabled = client.connected,
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(showLatency) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource("snap_latency_value", latDraft.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(104.dp)
                        )
                        IconButton(
                            onClick = {
                                latDraft = (latDraft - 10f).coerceAtLeast(-500f)
                                latHoldUntil = System.currentTimeMillis() + SNAP_HOLD_AFTER_SET_MS
                                onSetLatency(client.id, latDraft.toInt())
                            },
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Outlined.Remove, null, Modifier.size(16.dp)) }
                        Slider(
                            value = latDraft,
                            onValueChange = { latInteracting = true; latDraft = it },
                            onValueChangeFinished = {
                                latInteracting = false
                                latHoldUntil = System.currentTimeMillis() + SNAP_HOLD_AFTER_SET_MS
                                onSetLatency(client.id, latDraft.toInt())
                            },
                            valueRange = -500f..500f,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                latDraft = (latDraft + 10f).coerceAtMost(500f)
                                latHoldUntil = System.currentTimeMillis() + SNAP_HOLD_AFTER_SET_MS
                                onSetLatency(client.id, latDraft.toInt())
                            },
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) }
                    }
                    Text(
                        stringResource("snap_latency_hint"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!client.connected) {
                Text(
                    stringResource("snap_client_offline"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dettaglio tecnico del canale di controllo.
 *
 * Sta nella UI e non solo nel log di proposito: chi incontra il problema —
 * adesso o fra un anno, magari senza avere il progetto aperto in un IDE — deve
 * poter dire cosa non funziona senza dover riavviare l'applicazione in modalita'
 * debug.
 */
@Composable
private fun SnapControlDiagnostics(control: SnapControlStatus) {
    var expanded by remember { mutableStateOf(false) }
    val target = if (control.port > 0) "${control.host}:${control.port}" else control.host
    val detail = control.errorDetail?.takeIf { it.isNotBlank() }
        ?: stringResource("snap_ctrl_no_detail")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.BugReport, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    stringResource("snap_ctrl_detail_title"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = if (expanded) 6 else 2,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SnapDiagLine(stringResource("snap_ctrl_diag_target"), target)
                    SnapDiagLine(
                        stringResource("snap_ctrl_diag_state"),
                        control.state.name.lowercase()
                    )
                    SnapDiagLine(
                        stringResource("snap_ctrl_diag_attempts"),
                        control.attempts.toString()
                    )
                    Text(
                        stringResource(
                            "snap_ctrl_diag_probe",
                            if (control.port > 0) "${control.host} ${control.port}" else "IP PORTA"
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapDiagLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
