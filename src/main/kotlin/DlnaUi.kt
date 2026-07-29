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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun DlnaSettingsSection(
    appSettings: AppSettings,
    audioSettings: AudioSettings_V1,
    onAppSettingsChange: (AppSettings) -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val discovery by DlnaDiscoveryService.flow.collectAsState()
    val targets by NetworkHandler_v1.dlnaTargets.collectAsState()

    SwitchSetting(
        title = stringResource("protocol_dlna_title"),
        description = stringResource("protocol_dlna_desc"),
        icon = Icons.Outlined.Cast,
        checked = appSettings.dlnaEnabled,
        onCheckedChange = { enabled ->
            onAppSettingsChange(appSettings.copy(dlnaEnabled = enabled))
        }
    )

    LaunchedEffect(appSettings.dlnaEnabled) {
        if (appSettings.dlnaEnabled && discovery.renderers.isEmpty()) {
            DlnaDiscoveryService.scan(NetworkHandler_v1.getActiveNetworkInterface(appSettings.networkInterface))
        }
    }

    AnimatedVisibility(appSettings.dlnaEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = appSettings.dlnaPort,
                onValueChange = {
                    if (it.all(Char::isDigit) && it.length <= 5) {
                        onAppSettingsChange(appSettings.copy(dlnaPort = it))
                    }
                },
                label = { Text(stringResource("dlna_port")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource("dlna_port_hint")) }
            )

            Text(stringResource("dlna_format_label"), style = MaterialTheme.typography.labelLarge)
            val availableCodecs = remember(audioSettings.sampleRate, audioSettings.channels) {
                DlnaCodecSupport.available(audioSettings.sampleRate.toInt(), audioSettings.channels)
            }
            DlnaFormatPicker(
                selected = DlnaFormatPreference.fromId(appSettings.dlnaFormat),
                available = availableCodecs,
                onSelected = { onAppSettingsChange(appSettings.copy(dlnaFormat = it.id)) }
            )
            Text(
                text = dlnaFormatDescription(DlnaFormatPreference.fromId(appSettings.dlnaFormat)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource("dlna_devices_label"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (discovery.scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            DlnaDiscoveryService.scan(
                                NetworkHandler_v1.getActiveNetworkInterface(appSettings.networkInterface)
                            )
                        }
                    },
                    enabled = !discovery.scanning
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource("dlna_rescan"), modifier = Modifier.padding(start = 6.dp))
                }
            }

            val entries = dlnaVisibleEntries(appSettings.dlnaDevices, discovery.renderers)

            if (entries.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (discovery.scanning) stringResource("dlna_scanning")
                        else stringResource("dlna_no_devices"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    entries.forEach { entry ->
                        DlnaDeviceRow(
                            entry = entry,
                            checked = DlnaSelection.contains(appSettings.dlnaDevices, entry.udn),
                            status = targets.firstOrNull { it.udn == entry.udn },
                            onToggle = {
                                val updated = if (DlnaSelection.contains(appSettings.dlnaDevices, entry.udn)) {
                                    appSettings.dlnaDevices.filterNot { DlnaSelection.udnOf(it) == entry.udn }
                                } else {
                                    appSettings.dlnaDevices + DlnaSelection.encode(entry.udn, entry.name)
                                }
                                onAppSettingsChange(appSettings.copy(dlnaDevices = updated))
                            }
                        )
                    }
                }
            }

            Text(
                text = stringResource("dlna_selection_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(DlnaDiagnostics.report()))
                    }
                ) {
                    Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource("dlna_copy_diagnostics"), modifier = Modifier.padding(start = 6.dp))
                }
                if (appSettings.dlnaDevices.isNotEmpty()) {
                    TextButton(onClick = { onAppSettingsChange(appSettings.copy(dlnaDevices = emptyList())) }) {
                        Text(stringResource("dlna_clear_selection"))
                    }
                }
            }
        }
    }
}

@Composable
fun DlnaStatusCard(
    localIp: String,
    port: Int,
    formatPreference: DlnaFormatPreference,
    hasSelection: Boolean
) {
    val targets by NetworkHandler_v1.dlnaTargets.collectAsState()

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Cast,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource("dlna_card_title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(
                            "dlna_card_connections",
                            targets.count { it.status == DlnaTargetStatus.PLAYING }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                    )
                }
            }

            DlnaCardRow(
                label = stringResource("dlna_card_endpoint"),
                value = "http://${NetAddr.hostPort(localIp, port)}"
            )
            DlnaCardRow(
                label = stringResource("dlna_card_format"),
                value = targets.firstOrNull { it.codec != null }?.codec?.label
                    ?: dlnaFormatLabel(formatPreference)
            )

            if (!hasSelection) {
                Text(
                    text = stringResource("dlna_card_no_targets"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            } else {
                targets.forEach { target ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dlnaDotColor(target.status))
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = target.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = dlnaStatusLabel(target),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DlnaCardRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun dlnaStatusLabel(target: DlnaTargetState): String {
    val label = when (target.status) {
        DlnaTargetStatus.PLAYING -> stringResource("dlna_state_playing")
        DlnaTargetStatus.CONNECTING -> stringResource("dlna_state_connecting")
        DlnaTargetStatus.RETRYING -> stringResource("dlna_state_retrying")
        DlnaTargetStatus.ERROR -> stringResource("dlna_state_error")
        DlnaTargetStatus.OFFLINE -> stringResource("dlna_offline")
        DlnaTargetStatus.IDLE -> ""
    }
    val codec = target.codec?.label ?: return label
    return "$label · $codec"
}

@Composable
private fun dlnaDotColor(status: DlnaTargetStatus) = when (status) {
    DlnaTargetStatus.PLAYING -> MaterialTheme.colorScheme.primary
    DlnaTargetStatus.ERROR, DlnaTargetStatus.OFFLINE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

data class DlnaUiEntry(
    val udn: String,
    val name: String,
    val subtitle: String,
    val online: Boolean
)

private fun dlnaVisibleEntries(
    saved: List<String>,
    discovered: List<DlnaRenderer>
): List<DlnaUiEntry> {
    val online = discovered.map {
        DlnaUiEntry(it.udn, it.displayName, it.subtitle, true)
    }
    val onlineUdns = online.map { it.udn }.toSet()
    val offline = saved
        .map { DlnaSelection.udnOf(it) to DlnaSelection.nameOf(it) }
        .filter { it.first.isNotBlank() && it.first !in onlineUdns }
        .map { DlnaUiEntry(it.first, it.second, Strings.get("dlna_offline"), false) }
    return online + offline
}

@Composable
private fun DlnaDeviceRow(
    entry: DlnaUiEntry,
    checked: Boolean,
    status: DlnaTargetState?,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = dlnaRowSubtitle(entry, status),
                    style = MaterialTheme.typography.bodySmall,
                    color = dlnaStatusColor(status, entry.online)
                )
            }
        }
    }
}

@Composable
private fun dlnaRowSubtitle(entry: DlnaUiEntry, status: DlnaTargetState?): String {
    if (status == null) return entry.subtitle
    val label = when (status.status) {
        DlnaTargetStatus.PLAYING -> stringResource("dlna_state_playing")
        DlnaTargetStatus.CONNECTING -> stringResource("dlna_state_connecting")
        DlnaTargetStatus.RETRYING -> stringResource("dlna_state_retrying")
        DlnaTargetStatus.ERROR -> stringResource("dlna_state_error")
        DlnaTargetStatus.OFFLINE -> stringResource("dlna_offline")
        DlnaTargetStatus.IDLE -> entry.subtitle
    }
    val codec = status.codec?.label
    val negotiated = if (status.codec != null && !status.negotiated) " (${Strings.get("dlna_fallback")})" else ""
    return if (codec != null) "$label · $codec$negotiated" else label
}

@Composable
private fun dlnaStatusColor(status: DlnaTargetState?, online: Boolean) = when {
    status?.status == DlnaTargetStatus.PLAYING -> MaterialTheme.colorScheme.primary
    status?.status == DlnaTargetStatus.ERROR -> MaterialTheme.colorScheme.error
    !online -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DlnaFormatPicker(
    selected: DlnaFormatPreference,
    available: Set<DlnaCodec>,
    onSelected: (DlnaFormatPreference) -> Unit
) {
    val options = listOf(DlnaFormatPreference.AUTO) +
            DlnaFormatPreference.entries.filter { it.codec()?.let(available::contains) == true }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (option == selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth().clickable { onSelected(option) }
            ) {
                Text(
                    text = dlnaFormatLabel(option),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun dlnaFormatLabel(preference: DlnaFormatPreference): String = when (preference) {
    DlnaFormatPreference.AUTO -> Strings.get("dlna_format_auto")
    DlnaFormatPreference.LPCM -> "LPCM 16 bit"
    DlnaFormatPreference.WAV -> "WAV"
    DlnaFormatPreference.MP3 -> "MP3 320 kbps"
    DlnaFormatPreference.ADTS -> "AAC ADTS"
}

private fun dlnaFormatDescription(preference: DlnaFormatPreference): String = when (preference) {
    DlnaFormatPreference.AUTO -> Strings.get("dlna_format_auto_desc")
    DlnaFormatPreference.LPCM -> Strings.get("dlna_format_lpcm_desc")
    DlnaFormatPreference.WAV -> Strings.get("dlna_format_wav_desc")
    DlnaFormatPreference.MP3 -> Strings.get("dlna_format_mp3_desc")
    DlnaFormatPreference.ADTS -> Strings.get("dlna_format_adts_desc")
}
