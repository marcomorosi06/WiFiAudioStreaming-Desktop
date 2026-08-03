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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapcastSettingsSection(
    appSettings: AppSettings,
    audioSettings: AudioSettings_V1,
    onAppSettingsChange: (AppSettings) -> Unit
) {
    val session by NetworkHandler_v1.snapcastSession.collectAsState()

    SwitchSetting(
        title = stringResource("protocol_snapcast_title"),
        description = stringResource("protocol_snapcast_desc"),
        icon = Icons.Outlined.Speaker,
        checked = appSettings.snapcastEnabled,
        onCheckedChange = { enabled ->
            onAppSettingsChange(appSettings.copy(snapcastEnabled = enabled))
        }
    )

    AnimatedVisibility(appSettings.snapcastEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = appSettings.snapcastPort,
                    onValueChange = {
                        if (it.all(Char::isDigit) && it.length <= 5) {
                            onAppSettingsChange(appSettings.copy(snapcastPort = it))
                        }
                    },
                    label = { Text(stringResource("snapcast_port")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = appSettings.snapcastControlPort,
                    onValueChange = {
                        if (it.all(Char::isDigit) && it.length <= 5) {
                            onAppSettingsChange(appSettings.copy(snapcastControlPort = it))
                        }
                    },
                    label = { Text(stringResource("snapcast_control_port")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(stringResource("snapcast_codec_label"), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SnapcastCodecs.ALL.forEachIndexed { index, codec ->
                    SegmentedButton(
                        selected = appSettings.snapcastCodec == codec,
                        onClick = { onAppSettingsChange(appSettings.copy(snapcastCodec = codec)) },
                        shape = SegmentedButtonDefaults.itemShape(index, SnapcastCodecs.ALL.size),
                        icon = { Icon(Icons.Outlined.GraphicEq, null, Modifier.size(ButtonDefaults.IconSize)) }
                    ) { Text(codec.uppercase()) }
                }
            }
            Text(
                when (appSettings.snapcastCodec) {
                    SnapcastCodecs.FLAC -> stringResource("snapcast_codec_flac_desc")
                    SnapcastCodecs.OPUS -> stringResource("snapcast_codec_opus_desc")
                    else -> stringResource("snapcast_codec_pcm_desc")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (appSettings.snapcastCodec == SnapcastCodecs.OPUS &&
                (audioSettings.sampleRate.toInt() != 48000 || audioSettings.channels != 2)
            ) {
                SnapcastNotice(stringResource("snapcast_opus_requirement"))
            }

            Text(stringResource("snapcast_chunk_label"), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SnapcastDefaults.CHUNK_CHOICES.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = appSettings.snapcastChunkMs == value,
                        onClick = { onAppSettingsChange(appSettings.copy(snapcastChunkMs = value)) },
                        shape = SegmentedButtonDefaults.itemShape(index, SnapcastDefaults.CHUNK_CHOICES.size)
                    ) { Text("$value ms") }
                }
            }

            OutlinedTextField(
                value = appSettings.snapcastBufferMs.toString(),
                onValueChange = { text ->
                    if (text.all(Char::isDigit) && text.length <= 4) {
                        val parsed = text.toIntOrNull() ?: SnapcastDefaults.BUFFER_MS
                        onAppSettingsChange(appSettings.copy(snapcastBufferMs = parsed))
                    }
                },
                label = { Text(stringResource("snapcast_buffer")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource("snapcast_buffer_hint")) }
            )

            OutlinedTextField(
                value = appSettings.snapcastStreamName,
                onValueChange = { onAppSettingsChange(appSettings.copy(snapcastStreamName = it.trim())) },
                label = { Text(stringResource("snapcast_stream_name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (session.running) {
                if (session.lastError.isNotBlank()) {
                    SnapcastNotice(stringResource("snapcast_codec_fallback", session.lastError, session.codec))
                }
                Text(
                    stringResource(
                        "snapcast_live_summary",
                        session.codec,
                        session.sampleFormat,
                        session.clients.count { it.connected },
                        session.controlConnections
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                session.clients.forEach { client -> SnapcastClientRow(client) }
            } else {
                SnapcastNotice(stringResource("snapcast_hint"))
            }
        }
    }
}

@Composable
private fun SnapcastClientRow(client: SnapcastClientView) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (client.connected) 0.6f else 0.25f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (client.muted) Icons.Outlined.VolumeOff else Icons.Outlined.Speaker,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    client.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    stringResource(
                        "snapcast_client_detail",
                        client.ip,
                        client.volumePercent,
                        client.latency
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (client.connected) stringResource("snapcast_client_online")
                else stringResource("snapcast_client_offline"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SnapcastNotice(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SnapcastServerBanner() {
    val session by NetworkHandler_v1.snapcastSession.collectAsState()
    if (!session.running) return

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val ip = session.serverIp.ifBlank { "<ip>" }
    val connectCommand = "snapclient -h $ip -p ${session.streamPort}"
    val healthy = session.streamBound && session.controlBound
    val connected = session.clients.count { it.connected }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (healthy) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        val onContainer = if (healthy) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onErrorContainer

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (healthy) Icons.Outlined.Speaker else Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource("snapcast_card_title"),
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainer
                    )
                    Text(
                        stringResource("snapcast_card_connections", connected),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = onContainer
                    )
                }
            }

            SnapcastStatusLine(
                label = stringResource("snapcast_card_endpoint"),
                value = if (session.streamBound) "$ip:${session.streamPort}"
                else stringResource("snapcast_port_blocked", session.streamPort),
                ok = session.streamBound,
                color = onContainer
            )
            SnapcastStatusLine(
                label = stringResource("snapcast_card_control"),
                value = if (session.controlBound) "$ip:${session.controlPort}"
                else stringResource("snapcast_port_blocked", session.controlPort),
                ok = session.controlBound,
                color = onContainer
            )
            SnapcastStatusLine(
                label = stringResource("snapcast_card_discovery"),
                value = if (session.discoveryActive) stringResource("snapcast_discovery_on")
                else stringResource("snapcast_discovery_off"),
                ok = session.discoveryActive,
                color = onContainer
            )
            SnapcastStatusLine(
                label = stringResource("snapcast_card_codec"),
                value = "${session.codec} ${session.sampleFormat}",
                ok = true,
                color = onContainer
            )

            if (session.bindError.isNotBlank()) {
                Text(
                    stringResource("snapcast_bind_error", session.bindError),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer
                )
            }

            HorizontalDivider(color = onContainer.copy(alpha = 0.2f))

            Text(
                stringResource("snapcast_how_to_connect"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer
            )
            Text(
                stringResource(
                    if (session.discoveryActive) "snapcast_how_to_connect_auto"
                    else "snapcast_how_to_connect_manual"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.85f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = onContainer.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        connectCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = onContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(connectCommand))
                        copied = true
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (copied) Icons.Outlined.CheckCircle else Icons.Outlined.ContentCopy,
                        contentDescription = stringResource("copy_url"),
                        tint = onContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                stringResource("snapcast_how_to_connect_apps"),
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.85f)
            )

            if (session.clients.isNotEmpty()) {
                HorizontalDivider(color = onContainer.copy(alpha = 0.2f))
                session.clients.forEach { client ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (client.muted) Icons.Outlined.VolumeOff else Icons.Outlined.Speaker,
                            contentDescription = null,
                            tint = onContainer.copy(alpha = if (client.connected) 1f else 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            client.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer.copy(alpha = if (client.connected) 1f else 0.5f),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${client.volumePercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainer.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapcastStatusLine(label: String, value: String, ok: Boolean, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = color.copy(alpha = if (ok) 0.7f else 1f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.75f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}
