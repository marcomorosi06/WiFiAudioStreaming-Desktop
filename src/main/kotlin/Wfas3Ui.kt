/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

/*
 * Module: Wfas3Ui
 *
 * WFAS v3 in the window, built like the Android app: plug and play first
 * (pairing that goes on by itself, one click for how it sounds, a server
 * that plays as soon as it is paired) and everything else one fold away.
 * Every text comes from the properties files (keys w3_* and w3u_*).
 *
 * Questions (a device that asks to pair, the pairing from this PC, the
 * notices) are drawn inside the window by Wfas3Host, never as popups: the
 * same through Swing on Wayland as anywhere else, and never behind another
 * window. Every question that lets something in puts No first.
 *
 * Only the interface lives here: the protocol and its engine are reached
 * through Wfas3Desktop, unchanged.
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import wfas3.W3
import wfas3.Wfas3Beacon
import wfas3.Wfas3ClientState
import wfas3.Wfas3Config
import wfas3.Wfas3PairInfo
import wfas3.Wfas3Peer

// -- texts ---------------------------------------------------------------------

fun wfas3PairFailText(reason: Int, race: Boolean): String {
    val base = when (reason) {
        W3.PAIRF_TIMEOUT -> Strings.get("w3_pf_timeout")
        W3.PAIRF_WRONG -> Strings.get("w3_pf_wrong")
        W3.PAIRF_REFUSED -> Strings.get("w3_pf_refused")
        W3.PAIRF_KEY -> Strings.get("w3_pf_key")
        W3.PAIRF_USED -> Strings.get("w3_pf_used")
        W3.PAIRF_FULL -> Strings.get("w3_pf_full")
        W3.PAIRF_CANCELLED -> Strings.get("w3_pf_cancelled")
        W3.PAIRF_CODE -> Strings.get("w3_pf_code")
        else -> Strings.get("w3_pf_protocol")
    }
    return if (race) base + "\n\n" + Strings.get("w3_pf_race") else base
}

fun wfas3FormatText(c: Wfas3Config): String {
    val parts = ArrayList<String>()
    parts += if (c.opus) Strings.get("w3_fmt_opus", (c.payloadBytes * 8L * 48000L / c.frameSamples / 1000L).toInt())
    else Strings.get("w3_fmt_pcm", c.sampleRate)
    if (c.sync) parts += Strings.get("w3_fmt_sync")
    if (c.multicast) parts += Strings.get("w3_fmt_multicast")
    return parts.joinToString(", ")
}

fun wfas3ClientStateText(state: Wfas3ClientState, pairs: List<Wfas3PairInfo>): String {
    fun nm(p: Int) = pairs.firstOrNull { it.slot == p }?.name?.ifBlank { null } ?: "?"
    return when (state) {
        is Wfas3ClientState.Idle -> Strings.get("status_inactive")
        is Wfas3ClientState.Searching -> Strings.get("w3_state_searching", nm(state.pair))
        is Wfas3ClientState.Connecting -> Strings.get("w3_state_connecting", nm(state.pair))
        is Wfas3ClientState.Connected -> Strings.get("w3_state_connected", nm(state.pair), wfas3FormatText(state.config))
        is Wfas3ClientState.Waiting -> reasonText(state.reason, nm(state.pair))
        is Wfas3ClientState.Stopped -> reasonText(state.reason, nm(state.pair))
    }
}

private fun reasonText(r: Wfas3ClientState.Reason, name: String): String = when (r) {
    Wfas3ClientState.Reason.NOT_FOUND -> Strings.get("w3_state_not_found", name)
    Wfas3ClientState.Reason.BUSY -> Strings.get("w3_state_busy", name)
    Wfas3ClientState.Reason.NOT_PERMITTED -> Strings.get("w3_state_not_permitted", name)
    Wfas3ClientState.Reason.SUSPECT -> Strings.get("w3_state_suspect", name)
    Wfas3ClientState.Reason.REMOVED -> Strings.get("w3_state_removed", name)
    Wfas3ClientState.Reason.GUEST_ENDED -> Strings.get("w3_state_guest_ended", name)
    Wfas3ClientState.Reason.LOST -> Strings.get("w3_state_lost", name)
    Wfas3ClientState.Reason.UNSUPPORTED -> Strings.get("w3_state_unsupported", name)
}

private fun frameMs(samples: Int): String = when (samples) {
    120 -> "2.5"
    240 -> "5"
    480 -> "10"
    else -> "20"
}

private val OPUS_KBPS_CHOICES = listOf(64, 96, 128, 160, 192, 256, 320, 384, 510)
private val LATENCY_CHOICES = listOf(20, 30, 40, 60, 80, 100, 150, 200, 300)

/** Which pair the client state is about, if any. */
private fun Wfas3ClientState.pairOrNull(): Int? = when (this) {
    is Wfas3ClientState.Searching -> pair
    is Wfas3ClientState.Connecting -> pair
    is Wfas3ClientState.Connected -> pair
    is Wfas3ClientState.Waiting -> pair
    is Wfas3ClientState.Stopped -> pair
    else -> null
}

// -- state shared by the pieces --------------------------------------------------

/** What the v3 pieces of the window share: the pairing panel, and a server to play once paired. */
object Wfas3UiState {
    val clientPairOpen = MutableStateFlow(false)
    /** The pairing to renew, when the panel was opened for one. */
    val replace = MutableStateFlow<Wfas3PairInfo?>(null)
    /** A server just paired from the panel: the list of servers connects to it by itself. */
    val connectRequest = MutableStateFlow<Int?>(null)

    fun openClientPair(replace: Wfas3PairInfo? = null) {
        this.replace.value = replace
        connectRequest.value = null
        Wfas3Desktop.resetPairing()
        clientPairOpen.value = true
    }
}

// -- small pieces ----------------------------------------------------------------

/** The little "v2" mark shown in front of a server that still uses the previous protocol. */
@Composable
fun V2Badge(modifier: Modifier = Modifier) {
    KitChip(Strings.get("w3_legacy_badge"), Icons.Outlined.History, Hue.Tertiary, modifier)
}

@Composable
private fun PairChips(p: Wfas3PairInfo) {
    if (!p.guest && !p.microphone && !p.suspect) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
        if (p.guest) KitChip(Strings.get("w3_badge_guest"), Icons.Outlined.PersonOutline, Hue.Neutral)
        if (p.microphone) KitChip(Strings.get("w3_badge_mic"), Icons.Outlined.Mic, Hue.Pink)
        if (p.suspect) KitChip(Strings.get("w3_badge_suspect"), Icons.Outlined.Autorenew, Hue.Red)
    }
}

/** Remove, asked once more in place: Keep comes first. */
@Composable
private fun RemoveInPlace(p: Wfas3PairInfo, onDone: () -> Unit = {}) {
    var confirm by remember(p.slot) { mutableStateOf(false) }
    AnimatedContent(confirm, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false) }, label = "remove") { asking ->
        if (asking) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(Strings.get("w3_paired_remove_body"))
                AnswerRow(
                    no = Strings.get("w3_notice_keep"),
                    yes = Strings.get("w3_paired_remove"),
                    onNo = { confirm = false },
                    onYes = { confirm = false; Wfas3Desktop.removePair(p.slot); onDone() },
                    yesColors = dangerColors()
                )
            }
        } else {
            FilledTonalButton(onClick = { confirm = true }, shape = groupShape(0, 1)) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Strings.get("w3_paired_remove"))
            }
        }
    }
}

// -- settings: protocol ------------------------------------------------------------

/**
 * The protocol section of the settings: the name shown when pairing and the
 * paired devices up front; the generation (v3 or v2), where the keys are and
 * the reset folded under Advanced. Turning v2 on asks first, in place.
 */
@Composable
fun Wfas3ProtocolSettings(app: AppSettings, onChange: (AppSettings) -> Unit) {
    val v3 = Wfas3Settings.isV3(app)
    val pairs by Wfas3Desktop.pairs.collectAsState()
    val protection by Wfas3Desktop.protection.collectAsState()
    val failure by Wfas3Desktop.failure.collectAsState()
    var askV2 by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    LaunchedEffect(v3) { if (v3) withContext(Dispatchers.IO) { Wfas3Desktop.ensure(app) } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (v3) {
            if (failure != null) NotePanel(Wfas3Desktop.failureText(), Icons.Outlined.ErrorOutline, Hue.Red)
            var name by remember(app.v3DeviceName) { mutableStateOf(app.v3DeviceName) }
            OutlinedTextField(
                value = name,
                onValueChange = { v -> name = v.take(32); onChange(app.copy(v3DeviceName = name)) },
                label = { Text(Strings.get("w3_device_name")) },
                placeholder = { Text(Wfas3Settings.deviceName(app.copy(v3DeviceName = ""))) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Hint(Strings.get("w3_device_name_hint", Wfas3Settings.deviceName(app.copy(v3DeviceName = ""))))
            Overline(Strings.get("w3_paired_title"))
            if (pairs.isEmpty()) Hint(Strings.get("w3_paired_empty"))
            pairs.forEach { p ->
                Panel {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name, size = 36.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.name.ifBlank { "?" }, fontWeight = FontWeight.SemiBold)
                            PairChips(p)
                        }
                        RemoveInPlace(p)
                    }
                }
            }
        }

        Disclosure(
            key = "w3.settings.advanced",
            icon = Icons.Outlined.Tune,
            title = Strings.get("w3u_adv_title"),
            detail = Strings.get(if (v3) "w3u_adv_summary_v3" else "w3u_adv_summary_v2"),
            startOpen = !v3
        ) {
            Overline(Strings.get("w3u_generation_title"))
            Hint(Strings.get("w3u_generation_desc"))
            GenerationRow(Strings.get("w3_v3_title"), Strings.get("w3_v3_desc"), Icons.Outlined.VerifiedUser, selected = v3, risky = false) {
                if (!v3) onChange(app.copy(wfasProtocol = Wfas3Settings.PROTOCOL_V3))
            }
            GenerationRow(Strings.get("w3_v2_title"), Strings.get("w3_v2_desc"), Icons.Outlined.History, selected = !v3, risky = true) {
                if (v3) askV2 = true
            }
            AnimatedVisibility(askV2, enter = expandVertically(Motion.defaultSpatial()) + fadeIn(), exit = shrinkVertically(Motion.defaultSpatial()) + fadeOut()) {
                Panel(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(Strings.get("w3_v2_enable_title"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(Strings.get("w3_v2_enable_body"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        AnswerRow(
                            no = Strings.get("w3_v2_enable_stay"),
                            yes = Strings.get("w3_v2_enable_confirm"),
                            onNo = { askV2 = false },
                            onYes = { askV2 = false; onChange(app.copy(wfasProtocol = Wfas3Settings.PROTOCOL_V2)) },
                            yesColors = dangerColors()
                        )
                    }
                }
            }
            if (v3) {
                if (protection.isNotBlank()) {
                    NotePanel(
                        Strings.get(
                            "w3_keys_where",
                            if (protection == Wfas3FileStore.PROTECTION_FILE) Strings.get("w3_keys_file") else protection
                        ),
                        Icons.Outlined.Key
                    )
                }
                Overline(Strings.get("w3_reset"))
                Hint(Strings.get("w3_reset_desc"))
                if (askReset) {
                    Hint(Strings.get("w3_reset_body"))
                    AnswerRow(
                        no = Strings.get("w3_cancel"),
                        yes = Strings.get("w3_reset_confirm"),
                        onNo = { askReset = false },
                        onYes = { askReset = false; Wfas3Desktop.eraseAll() },
                        yesColors = dangerColors()
                    )
                } else {
                    OutlinedButton(onClick = { askReset = true }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Strings.get("w3_reset") + "...")
                    }
                }
            }
        }
    }
}

/** One protocol generation, as a row to pick; the risky one turns red when chosen. */
@Composable
private fun GenerationRow(title: String, detail: String, icon: ImageVector, selected: Boolean, risky: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val back by animateColorAsState(
        when {
            selected && risky -> scheme.errorContainer
            selected -> scheme.secondaryContainer
            else -> paneColor()
        },
        Motion.fastEffects(),
        label = "generation"
    )
    Surface(selected = selected, onClick = onClick, shape = RoundedCornerShape(16.dp), color = back, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Icon(icon, contentDescription = null, tint = if (selected && risky) scheme.error else scheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Hint(detail)
            }
        }
    }
}

// -- server --------------------------------------------------------------------------

/**
 * The ready-made ways to stream. Each one sets codec, bitrate, packet size,
 * latency and synchronisation together; values set by hand that match none
 * of them show as Custom on the fold below.
 */
private enum class SoundPreset(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val hue: Hue,
    val blob: Blob,
    val codec: String,
    /** Null: not used by this codec, the stored value is kept. */
    val kbps: Int?,
    val frame: Int?,
    val latencyMs: Int,
    val sync: Boolean
) {
    HiFi("w3u_preset_hifi", "w3u_preset_hifi_desc", Icons.Outlined.GraphicEq, Hue.Violet, Blob(8, 0.09f), Wfas3Settings.CODEC_PCM, null, null, 60, false),
    Balanced("w3u_preset_balanced", "w3u_preset_balanced_desc", Icons.Outlined.Wifi, Hue.Teal, Blob(9, 0.06f), Wfas3Settings.CODEC_OPUS, 256, 480, 60, false),
    Quick("w3u_preset_quick", "w3u_preset_quick_desc", Icons.Outlined.Bolt, Hue.Amber, Blob(12, 0.08f), Wfas3Settings.CODEC_OPUS, 160, 120, 40, false),
    Together("w3u_preset_together", "w3u_preset_together_desc", Icons.Outlined.Groups, Hue.Pink, Blob.LIVE, Wfas3Settings.CODEC_OPUS, 256, 480, 120, true);
}

private fun presetOf(a: AppSettings): SoundPreset? = SoundPreset.entries.firstOrNull { p ->
    a.v3Codec == p.codec && a.v3LatencyMs == p.latencyMs && a.v3Sync == p.sync &&
        (p.kbps == null || a.v3OpusKbps == p.kbps) && (p.frame == null || a.v3OpusFrameSamples == p.frame)
}

private fun streamSummary(a: AppSettings): String {
    val codec = if (a.v3Codec == Wfas3Settings.CODEC_OPUS) Strings.get("w3u_summary_opus", a.v3OpusKbps) else Strings.get("w3_codec_pcm")
    val sync = if (a.v3Sync) Strings.get("w3u_summary_sync") else null
    return listOfNotNull(codec, sync).joinToString(" \u00B7 ")
}

/**
 * The v3 side of the server, before and while streaming: who listens (with
 * [live]), pairing, how it sounds, and the fine tuning folded. Changes apply
 * live; synchronisation and multicast make the clients reconnect by themselves.
 */
@Composable
fun Wfas3ServerCard(
    app: AppSettings,
    audio: AudioSettings_V1,
    isMulticast: Boolean,
    onChange: (AppSettings) -> Unit,
    live: Boolean = false,
    onMulticastChange: (Boolean) -> Unit = {},
) {
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Wfas3Desktop.ensure(app); Wfas3Desktop.refreshPairs() } }
    val preset = presetOf(app)

    KitCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(Strings.get("w3_server_title"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (live) ClientsPanel()
            PairingPanel(app)
            SoundPresets(preset) { pr ->
                onChange(
                    app.copy(
                        v3Codec = pr.codec,
                        v3OpusKbps = pr.kbps ?: app.v3OpusKbps,
                        v3OpusFrameSamples = pr.frame ?: app.v3OpusFrameSamples,
                        v3LatencyMs = pr.latencyMs,
                        v3Sync = pr.sync
                    )
                )
            }
            // The latency is the setting people reach for most: in view, not in the fold.
            LatencyPicker(app.v3LatencyMs) { onChange(app.copy(v3LatencyMs = it)) }
            // Chosen before starting: a running session keeps the way it began.
            val all = isMulticast || app.rtpEnabled || app.httpEnabled || app.dlnaEnabled || app.snapcastEnabled
            if (!live) DeliveryPicker(
                shared = isMulticast,
                lockedBy = ProtocolStatus.names(
                    wfas = false,
                    rtp = app.rtpEnabled,
                    http = app.httpEnabled,
                    dlna = app.dlnaEnabled,
                    snapcast = app.snapcastEnabled
                ),
                onChange = onMulticastChange
            )
            if (live) {
                NotePanel(
                    Strings.get(if (all) "w3u_delivery_live_all" else "w3u_delivery_live_each"),
                    if (all) Icons.Outlined.Groups else Icons.Outlined.Person
                )
                NotePanel(Strings.get("w3_change_live"), Icons.Outlined.Sync)
            }
            Disclosure(
                key = "w3.server.more",
                icon = Icons.Outlined.Tune,
                title = Strings.get("w3u_more_title"),
                detail = streamSummary(app),
                badge = if (preset == null) Strings.get("w3u_preset_custom") else null
            ) {
                StreamControls(app, audio, isMulticast, onChange)
                WhoCanConnect(app, onChange)
            }
        }
    }
}

/** Four tiles: one click and the stream sounds that way. Two by two when the card is narrow. */
@Composable
private fun SoundPresets(selected: SoundPreset?, onPick: (SoundPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(Strings.get("w3u_presets_title"))
        BoxWithConstraints {
            val perRow = if (maxWidth >= 640.dp) 4 else 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SoundPreset.entries.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { pr ->
                            PresetTile(
                                icon = pr.icon,
                                title = Strings.get(pr.title),
                                detail = Strings.get(pr.detail),
                                hue = pr.hue,
                                blob = pr.blob,
                                selected = pr == selected,
                                onClick = { onPick(pr) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The latency, next to the presets: a slider over the usual steps (and the
 * stored value, if it is none of them), quicker on the left, steadier on the
 * right. The value is saved when the slider is let go.
 */
@Composable
private fun LatencyPicker(latencyMs: Int, onPick: (Int) -> Unit) {
    val all = remember(latencyMs) { (LATENCY_CHOICES + latencyMs).distinct().sorted() }
    var index by remember(all, latencyMs) { mutableFloatStateOf(all.indexOf(latencyMs).coerceAtLeast(0).toFloat()) }
    val shown = all[index.roundToInt().coerceIn(0, all.lastIndex)]
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Timer, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(Strings.get("w3_latency"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                Text(
                    Strings.get("w3_latency_value", shown),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
        Slider(
            value = index,
            onValueChange = { index = it },
            onValueChangeFinished = { if (shown != latencyMs) onPick(shown) },
            valueRange = 0f..all.lastIndex.toFloat(),
            steps = (all.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth()) {
            Text(Strings.get("w3u_latency_quick"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(Strings.get("w3u_latency_steady"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Compression, its bitrate and packets, synchronisation. */
@Composable
private fun StreamControls(app: AppSettings, audio: AudioSettings_V1, isMulticast: Boolean, onChange: (AppSettings) -> Unit) {
    val opus = app.v3Codec == Wfas3Settings.CODEC_OPUS
    Overline(Strings.get("w3_stream_section"))
    ButtonGroup(Modifier.fillMaxWidth()) {
        val pcmBtn: @Composable RowScope.(Boolean, String, ImageVector, () -> Unit, Int) -> Unit = { on, label, icon, click, index ->
            if (on) {
                Button(onClick = click, shape = groupShape(index, 2), modifier = Modifier.weight(1f)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
                }
            } else {
                FilledTonalButton(onClick = click, shape = groupShape(index, 2), modifier = Modifier.weight(1f)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
                }
            }
        }
        pcmBtn(!opus, Strings.get("w3_codec_pcm"), Icons.Outlined.GraphicEq, { onChange(app.copy(v3Codec = Wfas3Settings.CODEC_PCM)) }, 0)
        pcmBtn(opus, Strings.get("w3_codec_opus"), Icons.Outlined.Compress, { onChange(app.copy(v3Codec = Wfas3Settings.CODEC_OPUS)) }, 1)
    }
    if (opus) {
        Hint(Strings.get("w3_codec_opus_desc"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ExposedDropdown(
                label = Strings.get("w3_opus_bitrate"),
                value = Strings.get("w3_opus_bitrate_value", app.v3OpusKbps),
                options = OPUS_KBPS_CHOICES.map { Strings.get("w3_opus_bitrate_value", it) to it },
                onOptionSelected = { onChange(app.copy(v3OpusKbps = it)) },
                modifier = Modifier.weight(1f)
            )
            ExposedDropdown(
                label = Strings.get("w3_opus_frame"),
                value = Strings.get("w3_opus_frame_value", frameMs(app.v3OpusFrameSamples)),
                options = Wfas3Settings.OPUS_FRAMES.map { Strings.get("w3_opus_frame_value", frameMs(it)) to it },
                onOptionSelected = { onChange(app.copy(v3OpusFrameSamples = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        Hint(Strings.get("w3u_frame_hint"))
    } else {
        val rate = audio.sampleRate.toInt().takeIf { it in Wfas3Settings.PCM_RATES } ?: 48000
        val kbps = rate.toLong() * audio.channels.coerceIn(1, 2) * 16 / 1000
        Hint(Strings.get("w3_codec_pcm_desc", rate, kbps.toInt()))
    }
    ToggleTile(
        icon = Icons.Outlined.Sync,
        title = Strings.get("w3_sync"),
        detail = Strings.get("w3_sync_desc"),
        checked = app.v3Sync,
        onChange = { onChange(app.copy(v3Sync = it)) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Ask before pairing, the microphone without asking, and new listeners while streaming. */
@Composable
private fun WhoCanConnect(app: AppSettings, onChange: (AppSettings) -> Unit) {
    Overline(Strings.get("w3u_who_title"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleTile(
            icon = Icons.Outlined.HowToReg,
            title = Strings.get("w3u_ask_tile"),
            detail = Strings.get(if (app.v3PairAsk) "w3u_ask_tile_on" else "w3u_ask_tile_off"),
            checked = app.v3PairAsk,
            onChange = { onChange(app.copy(v3PairAsk = it)) },
            modifier = Modifier.weight(1f)
        )
        ToggleTile(
            icon = Icons.Outlined.PersonAddAlt,
            title = Strings.get("w3u_new_tile"),
            detail = Strings.get(if (app.v3AcceptNew) "w3u_new_tile_on" else "w3u_new_tile_off"),
            checked = app.v3AcceptNew,
            onChange = { onChange(app.copy(v3AcceptNew = it)) },
            modifier = Modifier.weight(1f)
        )
    }
    AnimatedVisibility(!app.v3PairAsk, enter = expandVertically(Motion.defaultSpatial()) + fadeIn(), exit = shrinkVertically(Motion.defaultSpatial()) + fadeOut()) {
        val allow by Wfas3DesktopUiPrefs.allowMicNoAsk.collectAsState()
        ToggleTile(
            icon = Icons.Outlined.Mic,
            title = Strings.get("w3_pair_allow_mic"),
            detail = Strings.get(if (allow) "w3u_mic_noask_on" else "w3u_mic_noask_off"),
            checked = allow,
            onChange = { Wfas3DesktopUiPrefs.allowMicNoAsk.value = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
    Hint(Strings.get("w3u_who_note") + " " + Strings.get("w3_max_clients", W3.MAX_SESSIONS))
}

/** Choices of this run only, as before: not settings, they are gone when the program closes. */
object Wfas3DesktopUiPrefs {
    val allowMicNoAsk = MutableStateFlow(false)
}

/** Who is listening, with a shape that turns while somebody does. */
@Composable
private fun ClientsPanel() {
    val peers by Wfas3Desktop.peers.collectAsState()
    val listening = peers.count { it.streaming }
    val tone = toneOf(if (listening > 0) Hue.Green else Hue.Teal)
    Panel(color = tone.container) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MorphingBlob(if (listening > 0) Blob.LIVE else Blob.LOOKING, tone.accent, Modifier.size(48.dp), spin = listening > 0) {
                    Icon(if (listening > 0) Icons.Outlined.GraphicEq else Icons.Outlined.Sensors, contentDescription = null, tint = tone.container, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        if (peers.isEmpty()) Strings.get("w3_clients_none") else Strings.get("w3u_clients_count", peers.size, listening),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tone.onContainer
                    )
                    Text(
                        Strings.get(if (peers.isEmpty()) "w3u_clients_none_detail" else "w3u_clients_detail", W3.MAX_SESSIONS),
                        style = MaterialTheme.typography.bodySmall,
                        color = tone.onContainer.copy(alpha = 0.8f)
                    )
                }
            }
            peers.forEach { p -> key(p.session) { PeerRow(p, tone) } }
        }
    }
}

@Composable
private fun PeerRow(p: Wfas3Peer, tone: Tone) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.name, size = 36.dp, live = p.streaming)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.name.ifBlank { "?" }, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    KitChip(
                        Strings.get(if (p.streaming) "w3_client_streaming" else "w3_client_waiting"),
                        if (p.streaming) Icons.Outlined.GraphicEq else Icons.Outlined.HourglassTop,
                        if (p.streaming) Hue.Green else Hue.Amber
                    )
                    if (p.synchronised) KitChip(Strings.get("w3_client_sync"), Icons.Outlined.Sync, Hue.Violet)
                    if (p.multicast) KitChip(Strings.get("w3_client_multicast"), Icons.Outlined.Groups, Hue.Teal)
                    if (p.microphone) KitChip(Strings.get("w3_client_mic"), Icons.Outlined.Mic, Hue.Pink)
                }
            }
            if (p.streaming) LiveDot(tone.accent)
        }
    }
}

private enum class PairStage { None, Opening, Code, Pin, Paired }

/**
 * Pairing, in steps that replace each other: the offer, then the QR and the
 * code or the PIN while the window is open, then who paired. The result
 * stays a moment and the panel goes back to its offer by itself.
 */
@Composable
fun PairingPanel(app: AppSettings) {
    val w by Wfas3Desktop.window.collectAsState()
    val fails by Wfas3Desktop.windowFailures.collectAsState()
    val allowMic by Wfas3DesktopUiPrefs.allowMicNoAsk.collectAsState()
    var openFailed by remember { mutableStateOf(false) }
    val win = w
    val stage = when {
        win == null -> PairStage.None
        win.paired != null -> PairStage.Paired
        win.text.isEmpty() -> PairStage.Opening
        win.kind == W3.PAIR_PIN -> PairStage.Pin
        else -> PairStage.Code
    }
    LaunchedEffect(stage) {
        if (stage == PairStage.Paired) {
            delay(4000)
            Wfas3Desktop.windowDismissed()
        }
    }
    val violet = toneOf(Hue.Violet)
    val green = toneOf(Hue.Green)
    val back = when (stage) {
        PairStage.None -> tileColor()
        PairStage.Paired -> green.container
        else -> violet.container
    }
    Panel(color = back) {
        AnimatedContent(
            targetState = stage,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false) },
            label = "pairing"
        ) { shown ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (shown) {
                    PairStage.None -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BlobIcon(Icons.Outlined.Key, toneOf(Hue.Primary), Blob.WAITING, size = 40.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(Strings.get("w3u_pair_offer_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Hint(Strings.get("w3u_pair_offer_desc"))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigButton(
                                text = Strings.get("w3_pair_code_button"),
                                icon = Icons.Outlined.QrCode2,
                                onClick = { openFailed = !Wfas3Desktop.openWindow(app, W3.PAIR_CODE, app.v3PairAsk, allowMic) },
                                modifier = Modifier.weight(1f)
                            )
                            BigButton(
                                text = Strings.get("w3_pair_pin_button"),
                                icon = Icons.Outlined.Pin,
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                                // With a PIN the two users compare codes: the server always asks.
                                onClick = { openFailed = !Wfas3Desktop.openWindow(app, W3.PAIR_PIN, true, allowMic) },
                                modifier = Modifier.weight(0.8f)
                            )
                        }
                        if (openFailed) NotePanel(Wfas3Desktop.failureText(), Icons.Outlined.ErrorOutline, Hue.Red)
                    }
                    PairStage.Opening -> Row(verticalAlignment = Alignment.CenterVertically) {
                        MorphingBlob(Blob.WAITING, violet.accent, Modifier.size(36.dp), spin = true)
                        Spacer(Modifier.width(12.dp))
                        Text(Strings.get("w3u_pair_opening"), style = MaterialTheme.typography.titleMedium, color = violet.onContainer)
                    }
                    PairStage.Code -> {
                        val ww = win ?: return@Column
                        Overline(Strings.get("w3u_pair_scan_this"), color = violet.onContainer)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ww.qr?.let { qr ->
                                Box(Modifier.size(212.dp).clip(RoundedCornerShape(20.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                                    QrCodeCanvas(
                                        content = qr,
                                        modifier = Modifier.size(184.dp),
                                        palette = listOf(Color.Black),
                                        ringColor = Color.Black,
                                        eyeColor = Color.Black,
                                        plateColor = Color.White,
                                        plain = true,
                                        minContrast = 7f
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(Strings.get("w3u_pair_or_type"), style = MaterialTheme.typography.bodySmall, color = violet.onContainer)
                                Surface(shape = RoundedCornerShape(14.dp), color = violet.accent.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
                                    SelectableCode(ww.text, violet.onContainer)
                                }
                            }
                        }
                        WindowFooter(ww, fails, violet)
                    }
                    PairStage.Pin -> {
                        val ww = win ?: return@Column
                        Overline(Strings.get("w3u_pair_type_pin", Wfas3Settings.deviceName(app)), color = violet.onContainer)
                        CodeTiles(ww.text, 4, violet.accent, violet.container)
                        Text(Strings.get("w3u_pair_pin_compare"), style = MaterialTheme.typography.bodySmall, color = violet.onContainer)
                        WindowFooter(ww, fails, violet)
                    }
                    PairStage.Paired -> {
                        val ww = win ?: return@Column
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BlobIcon(Icons.Filled.CheckCircle, green, Blob.DONE, size = 44.dp, filled = true)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(Strings.get("w3_window_paired", ww.paired ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = green.onContainer)
                                Text(Strings.get("w3u_pair_paired_desc"), style = MaterialTheme.typography.bodySmall, color = green.onContainer)
                            }
                            TextButton(onClick = { Wfas3Desktop.windowDismissed() }) { Text(Strings.get("w3_close")) }
                        }
                    }
                }
            }
        }
    }
}

/** The code, selectable so it can be copied into the other device. */
@Composable
private fun SelectableCode(text: String, color: Color) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/** The time left, the failed attempts, the privacy line and Close, under an open window. */
@Composable
private fun WindowFooter(w: Wfas3Desktop.WindowState, fails: Int, tone: Tone) {
    Countdown(w.openedAtMs + 120_000L, 120_000L, tone.accent) { s ->
        if (s > 0) Strings.get("w3_window_expires", s.toInt()) else Strings.get("w3_window_expired")
    }
    if (fails > 0) NotePanel(Strings.get("w3_window_failures", fails), Icons.Outlined.Warning, Hue.Red)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = tone.onContainer, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(Strings.get("w3_window_private"), style = MaterialTheme.typography.bodySmall, color = tone.onContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = { Wfas3Desktop.closeWindow() }) { Text(Strings.get("w3u_pair_close"), color = tone.onContainer) }
    }
}

// -- client --------------------------------------------------------------------------

/**
 * The paired servers, one row each: click Listen and it plays. A server
 * paired a moment ago plays by itself. [enabled] reflects that an output
 * device is chosen.
 */
@Composable
fun Wfas3ServersSection(
    app: AppSettings,
    enabled: Boolean,
    onConnect: (Wfas3PairInfo) -> Unit,
    onDisconnect: () -> Unit,
) {
    val pairs by Wfas3Desktop.pairs.collectAsState()
    val state by Wfas3Desktop.clientState.collectAsState()
    val request by Wfas3UiState.connectRequest.collectAsState()

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Wfas3Desktop.ensure(app); Wfas3Desktop.refreshPairs() } }
    // Pairing means wanting to listen: the new server plays as soon as it is in the list.
    LaunchedEffect(request, pairs, enabled) {
        val slot = request ?: return@LaunchedEffect
        val p = pairs.firstOrNull { it.slot == slot } ?: return@LaunchedEffect
        Wfas3UiState.connectRequest.value = null
        if (enabled) onConnect(p)
    }

    KitCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(
                icon = Icons.Outlined.VerifiedUser,
                title = Strings.get("w3_servers_title"),
                blob = Blob.LIVE
            )
            if (pairs.isEmpty()) EmptyServers()
            pairs.forEach { p -> key(p.slot) { ServerRow(p, state, pairs, enabled, { onConnect(p) }, onDisconnect) } }
            if (!enabled && pairs.isNotEmpty()) Hint(Strings.get("w3u_need_output"))
            BigButton(
                text = Strings.get("w3_pair_server_button"),
                icon = Icons.Outlined.Add,
                onClick = { Wfas3UiState.openClientPair() },
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

/** Nothing paired yet: what to do, in three steps. */
@Composable
private fun EmptyServers() {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Strings.get("w3u_empty_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FactRow(Icons.Outlined.QrCode2, Strings.get("w3u_step1_title"), Strings.get("w3u_step1_desc"))
        FactRow(Icons.Outlined.ContentPaste, Strings.get("w3u_step2_title_desktop"), Strings.get("w3u_step2_desc_desktop"))
        FactRow(Icons.Outlined.Password, Strings.get("w3u_step3_title"), Strings.get("w3u_step3_desc"))
    }
}

@Composable
private fun ServerRow(
    p: Wfas3PairInfo,
    state: Wfas3ClientState,
    pairs: List<Wfas3PairInfo>,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val mine = state.pairOrNull() == p.slot
    val active = mine && state !is Wfas3ClientState.Stopped
    val live = mine && state is Wfas3ClientState.Connected
    val scheme = MaterialTheme.colorScheme
    val back by animateColorAsState(
        when {
            p.suspect -> scheme.errorContainer
            live -> scheme.secondaryContainer
            active -> tileColor()
            else -> Color.Transparent
        },
        Motion.defaultEffects(),
        label = "serverBack"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(back)
            .clickable(enabled = !active && (p.suspect || enabled)) { if (p.suspect) Wfas3UiState.openClientPair(p) else onConnect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(p.name, size = 44.dp, live = live)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(p.name.ifBlank { "?" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val line = when {
                p.suspect -> Strings.get("w3u_server_suspect")
                mine -> wfas3ClientStateText(state, pairs)
                else -> Strings.get("w3u_server_click")
            }
            AnimatedContent(line, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) using SizeTransform(clip = false) }, label = "serverLine") { text ->
                Hint(text)
            }
            PairChips(p)
        }
        if (live) {
            LiveDot(scheme.primary)
            Spacer(Modifier.width(10.dp))
        }
        when {
            p.suspect -> FilledTonalButton(onClick = { Wfas3UiState.openClientPair(p) }) { Text(Strings.get("w3_repair")) }
            active -> Button(onClick = onDisconnect, colors = dangerColors()) {
                Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Strings.get("w3_disconnect"))
            }
            else -> Button(onClick = onConnect, enabled = enabled) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Strings.get("w3u_listen"))
            }
        }
    }
}

// -- devices page ----------------------------------------------------------------------

/** What is selected on the devices page. */
private sealed class DevicePick {
    object None : DevicePick()
    object PairHere : DevicePick()
    data class Pair(val slot: Int) : DevicePick()
}

/**
 * The paired devices, as WiFi Screen Streaming shows them: the list on the
 * left (and the two ways of pairing one more), the selected one on the
 * right. [visible] slides it in over the main screen.
 */
@Composable
fun Wfas3DevicesPage(app: AppSettings, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it / 8 }) + fadeIn(tween(220)),
        exit = slideOutHorizontally(targetOffsetX = { it / 8 }) + fadeOut(tween(120))
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DevicesContent(app)
        }
    }
}

@Composable
private fun DevicesContent(app: AppSettings) {
    val pairs by Wfas3Desktop.pairs.collectAsState()
    val window by Wfas3Desktop.window.collectAsState()
    var pick by remember { mutableStateOf<DevicePick>(DevicePick.None) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Wfas3Desktop.ensure(app); Wfas3Desktop.refreshPairs() } }
    // A window opened elsewhere shows here too; a removed device is no longer selected.
    LaunchedEffect(window) { if (window != null) pick = DevicePick.PairHere }
    val shown = pick.let { p -> if (p is DevicePick.Pair && pairs.none { it.slot == p.slot }) DevicePick.None else p }

    TwoPanes(
        modifier = Modifier.padding(12.dp),
        list = {
            PaneTitle(Strings.get("w3_paired_title"), Strings.get("w3u_devices_count", pairs.size))
            Spacer(Modifier.height(4.dp))
            if (pairs.isEmpty()) Hint(Strings.get("w3_paired_empty"))
            pairs.forEach { p ->
                ListRow(
                    title = p.name.ifBlank { "?" },
                    detail = listOfNotNull(
                        if (p.guest) Strings.get("w3_badge_guest") else null,
                        if (p.microphone) Strings.get("w3_badge_mic") else null,
                        if (p.suspect) Strings.get("w3_badge_suspect") else null
                    ).joinToString(" \u00B7 ").ifEmpty { null },
                    selected = shown == DevicePick.Pair(p.slot),
                    onClick = { pick = DevicePick.Pair(p.slot) },
                    leading = { Avatar(p.name) }
                )
            }
            Overline(Strings.get("w3u_devices_pair_more"))
            ListRow(
                title = Strings.get("w3u_devices_pair_here"),
                detail = Strings.get("w3u_devices_pair_here_desc"),
                selected = shown == DevicePick.PairHere,
                onClick = { pick = DevicePick.PairHere },
                leading = { BlobIcon(Icons.Outlined.QrCode2, toneOf(Hue.Violet), Blob.WAITING, size = 40.dp) }
            )
            ListRow(
                title = Strings.get("w3_pair_server_button"),
                detail = Strings.get("w3u_devices_pair_server_desc"),
                selected = false,
                onClick = { Wfas3UiState.openClientPair() },
                leading = { BlobIcon(Icons.Outlined.Speaker, toneOf(Hue.Teal), Blob.LOOKING, size = 40.dp) }
            )
        },
        detail = {
            AnimatedContent(shown, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(90)) }, label = "deviceDetail") { now ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (now) {
                        DevicePick.None -> NoDevice(app)
                        DevicePick.PairHere -> {
                            PaneTitle(Strings.get("w3u_devices_pair_here"), Wfas3Settings.deviceName(app))
                            PairingPanel(app)
                            Overline(Strings.get("w3u_devices_how"))
                            FactRow(Icons.Outlined.QrCode2, Strings.get("w3u_fact_code_title"), Strings.get("w3u_fact_code_desc"))
                            FactRow(Icons.Outlined.Pin, Strings.get("w3u_fact_pin_title"), Strings.get("w3u_fact_pin_desc"))
                        }
                        is DevicePick.Pair -> pairs.firstOrNull { it.slot == now.slot }?.let { DeviceDetail(it) { pick = DevicePick.None } }
                    }
                }
            }
        }
    )
}

@Composable
private fun NoDevice(app: AppSettings) {
    val protection by Wfas3Desktop.protection.collectAsState()
    PaneTitle(Wfas3Settings.deviceName(app), Strings.get("w3u_devices_this"))
    Overline(Strings.get("w3u_devices_how"))
    FactRow(Icons.Outlined.Cast, Strings.get("w3u_fact_server_title"), Strings.get("w3u_fact_server_desc"))
    FactRow(Icons.Outlined.Speaker, Strings.get("w3u_fact_client_title"), Strings.get("w3u_fact_client_desc"))
    FactRow(Icons.Outlined.Shield, Strings.get("w3u_fact_both_title"), Strings.get("w3u_fact_both_desc"))
    if (protection.isNotBlank()) {
        FactRow(
            Icons.Outlined.Lock,
            Strings.get("w3u_keys_title"),
            Strings.get("w3_keys_where", if (protection == Wfas3FileStore.PROTECTION_FILE) Strings.get("w3_keys_file") else protection)
        )
    }
}

@Composable
private fun DeviceDetail(p: Wfas3PairInfo, onRemoved: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.name, size = 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            PaneTitle(p.name.ifBlank { "?" })
            PairChips(p)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        if (p.suspect) {
            Button(onClick = { Wfas3UiState.openClientPair(p) }) {
                Icon(Icons.Outlined.Autorenew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Strings.get("w3_repair"))
            }
        }
        RemoveInPlace(p, onRemoved)
    }
    Overline(Strings.get("w3u_devices_means"))
    if (p.suspect) FactRow(Icons.Outlined.Autorenew, Strings.get("w3_badge_suspect"), Strings.get("w3u_fact_suspect_desc"))
    FactRow(Icons.Outlined.Link, Strings.get("w3u_fact_pair_title"), Strings.get(if (p.guest) "w3u_fact_guest_desc" else "w3u_fact_pair_desc"))
    if (p.microphone) FactRow(Icons.Outlined.Mic, Strings.get("w3_badge_mic"), Strings.get("w3u_fact_mic_desc"))
    FactRow(Icons.Outlined.Shield, Strings.get("w3u_fact_kept_title"), Strings.get("w3u_fact_kept_desc"))
}

// -- questions, hosted once by the window ----------------------------------------------

/**
 * Everything v3 asks, drawn over the window: a device that asks to pair,
 * pairing with a server from this PC, the notices. Placed once, above the
 * pages, so a question is seen wherever the user is.
 */
@Composable
fun Wfas3Host(app: AppSettings) {
    val asks by Wfas3Desktop.asks.collectAsState()
    val open by Wfas3UiState.clientPairOpen.collectAsState()
    val notices by Wfas3Desktop.notices.collectAsState()
    val pairs by Wfas3Desktop.pairs.collectAsState()

    // One request at a time; the next one shows when this one is answered.
    asks.firstOrNull()?.let { a -> key(a.ask) { AskQuestion(a) } }
    if (asks.isEmpty() && open) ClientPairPanel(app)
    if (asks.isEmpty() && !open) notices.firstOrNull()?.let { n -> key(n.id) { NoticeQuestion(n, pairs) } }
}

/** A device asks to pair: who, the code to compare with a PIN, what a yes allows. No first. */
@Composable
private fun AskQuestion(a: Wfas3Desktop.AskState) {
    var allowMic by remember(a.ask) { mutableStateOf(false) }
    var guest by remember(a.ask) { mutableStateOf(a.wantsGuest) }
    val violet = toneOf(Hue.Violet)
    Scrim {
        QuestionCard(
            title = Strings.get("w3_ask_title", a.name.ifBlank { "?" }),
            detail = if (a.from.isBlank()) Strings.get("w3_ask_unknown")
                else Strings.get("w3_ask_from", a.from) + " " + Strings.get("w3_ask_unknown"),
            icon = Icons.Outlined.PersonAdd,
            tone = violet,
            blob = Blob.ALERT
        ) {
            if (a.pin) {
                Text(Strings.get("w3_ask_compare"), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                CodeTiles(a.code, 3, violet.accent, violet.container)
            }
            if (a.wantsGuest) NotePanel(Strings.get("w3_ask_wants_guest"), Icons.Outlined.PersonOutline)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.wantsMic) {
                    ToggleTile(
                        icon = Icons.Outlined.Mic,
                        title = Strings.get("w3_ask_allow_mic"),
                        detail = Strings.get("w3_ask_wants_mic"),
                        checked = allowMic,
                        onChange = { allowMic = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!a.wantsGuest) {
                    ToggleTile(
                        icon = Icons.Outlined.PersonOutline,
                        title = Strings.get("w3_ask_as_guest"),
                        detail = Strings.get(if (guest) "w3u_guest_on" else "w3u_guest_off"),
                        checked = guest,
                        onChange = { guest = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            AnswerRow(
                no = Strings.get("w3_ask_no"),
                yes = Strings.get("w3_ask_yes"),
                onNo = { Wfas3Desktop.answer(a, yes = false, allowMic = false, guest = false) },
                onYes = { Wfas3Desktop.answer(a, yes = true, allowMic = allowMic, guest = guest) }
            )
        }
    }
}

/**
 * Pairing with a server from this PC, in one panel whose shape follows the
 * step. It goes on by itself where it can: a pasted or scanned code starts
 * it, the only server with a PIN window nearby is chosen, the eighth digit
 * starts it, and once paired the panel closes and the server plays.
 */
@Composable
private fun ClientPairPanel(app: AppSettings) {
    val pairing by Wfas3Desktop.pairing.collectAsState()
    val beacons by Wfas3Desktop.beacons.collectAsState()
    val failure by Wfas3Desktop.failure.collectAsState()
    val replace by Wfas3UiState.replace.collectAsState()
    var pinTab by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Wfas3Beacon?>(null) }
    var mic by remember { mutableStateOf(false) }
    var guest by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var noCamera by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    // The code checked out here: the server's user still has to say yes.
    var confirmed by remember { mutableStateOf<String?>(null) }
    val slot = replace?.slot ?: -1

    fun close() {
        Wfas3UiState.clientPairOpen.value = false
        Wfas3UiState.replace.value = null
    }

    fun go() {
        if (started) return
        started = true
        if (pinTab) chosen?.let { Wfas3Desktop.pairWithPin(it, pin, guest, mic, slot) }
        else Wfas3Desktop.pairWithCode(app, code.trim(), guest, mic, slot)
    }

    DisposableEffect(Unit) {
        Wfas3Desktop.resetPairing()
        onDispose {
            Wfas3Desktop.stopPinSearch()
            if (Wfas3Desktop.pairing.value !is Wfas3Desktop.ClientPairing.Done) Wfas3Desktop.cancelPairing()
            Wfas3Desktop.resetPairing()
        }
    }
    LaunchedEffect(pinTab) { if (pinTab) Wfas3Desktop.startPinSearch(app) else Wfas3Desktop.stopPinSearch() }

    val p = pairing
    LaunchedEffect(p) {
        if (p is Wfas3Desktop.ClientPairing.Idle) started = false
        if (p !is Wfas3Desktop.ClientPairing.Compare) confirmed = null
        if (p is Wfas3Desktop.ClientPairing.Done) {
            // The result stays a moment; then the panel closes and the server plays.
            delay(1800)
            if (p.slot >= 0) Wfas3UiState.connectRequest.value = p.slot
            close()
        }
    }
    // A typed or pasted code starts the pairing as soon as it is complete and checks out.
    LaunchedEffect(code, pinTab) {
        if (pinTab || !Wfas3Desktop.codeValid(code.trim())) return@LaunchedEffect
        delay(700)
        if (Wfas3Desktop.pairing.value is Wfas3Desktop.ClientPairing.Idle) go()
    }
    // The only server with an open PIN window nearby is chosen by itself.
    LaunchedEffect(beacons, pinTab) {
        if (pinTab && chosen == null && beacons.size == 1 && !beacons.first().used) chosen = beacons.first()
        if (chosen != null && beacons.none { it.key == chosen?.key }) { chosen = null; pin = "" }
    }
    // Servers whose beacons stopped leave the list.
    LaunchedEffect(pinTab) {
        while (pinTab) { delay(1000); Wfas3Desktop.pruneBeacons() }
    }
    // And the eighth digit of the PIN starts it.
    LaunchedEffect(pin, chosen) {
        if (pinTab && chosen != null && pin.length == 8) {
            delay(250)
            if (Wfas3Desktop.pairing.value is Wfas3Desktop.ClientPairing.Idle) go()
        }
    }

    val sentYes = p is Wfas3Desktop.ClientPairing.Compare && confirmed == p.code
    val violet = toneOf(Hue.Violet)
    val (title, detail) = when (p) {
        is Wfas3Desktop.ClientPairing.Idle -> Strings.get("w3_cpair_title") to (replace?.let { Strings.get("w3_cpair_replace", it.name) } ?: Strings.get("w3u_cpair_idle_desc_desktop"))
        is Wfas3Desktop.ClientPairing.Waiting -> Strings.get("w3u_cpair_waiting_title") to Strings.get("w3_cpair_waiting")
        is Wfas3Desktop.ClientPairing.Compare ->
            if (sentYes) Strings.get("w3u_cpair_confirm_server_title") to Strings.get("w3u_cpair_confirm_server_desc")
            else Strings.get("w3u_cpair_compare_title") to Strings.get("w3_cpair_compare")
        is Wfas3Desktop.ClientPairing.Done -> Strings.get("w3u_cpair_done_title") to Strings.get(if (p.guest) "w3_cpair_done_guest" else "w3_cpair_done", p.name)
        is Wfas3Desktop.ClientPairing.Failed -> Strings.get("w3u_cpair_failed_title") to (if (p.noNetwork) Wfas3Desktop.failureText() else wfas3PairFailText(p.reason, p.race))
    }
    val (icon, tone, blob) = when (p) {
        is Wfas3Desktop.ClientPairing.Idle -> Triple(Icons.Outlined.Link, violet, Blob.WAITING)
        is Wfas3Desktop.ClientPairing.Waiting -> Triple(Icons.Outlined.HourglassTop, toneOf(Hue.Amber), Blob.WAITING)
        is Wfas3Desktop.ClientPairing.Compare -> Triple(if (sentYes) Icons.Outlined.HourglassTop else Icons.Outlined.CompareArrows, violet, Blob.ALERT)
        is Wfas3Desktop.ClientPairing.Done -> Triple(Icons.Filled.CheckCircle, toneOf(Hue.Green), Blob.DONE)
        is Wfas3Desktop.ClientPairing.Failed -> Triple(Icons.Outlined.ErrorOutline, toneOf(Hue.Red), Blob.ALERT)
    }

    Scrim {
        QuestionCard(
            title = title,
            detail = detail,
            icon = icon,
            tone = tone,
            blob = blob,
            spin = p is Wfas3Desktop.ClientPairing.Waiting || sentYes,
            width = 560.dp
        ) {
            when (p) {
                is Wfas3Desktop.ClientPairing.Idle -> {
                    if (failure != null) NotePanel(Wfas3Desktop.failureText(), Icons.Outlined.ErrorOutline, Hue.Red)
                    ButtonGroup(Modifier.fillMaxWidth()) {
                        val tab: @Composable RowScope.(Boolean, String, ImageVector, Int, () -> Unit) -> Unit = { on, label, ic, index, click ->
                            if (on) Button(onClick = click, shape = groupShape(index, 2), modifier = Modifier.weight(1f)) {
                                Icon(ic, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
                            } else FilledTonalButton(onClick = click, shape = groupShape(index, 2), modifier = Modifier.weight(1f)) {
                                Icon(ic, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
                            }
                        }
                        tab(!pinTab, Strings.get("w3_cpair_tab_code"), Icons.Outlined.QrCode2, 0) { pinTab = false }
                        tab(pinTab, Strings.get("w3_cpair_tab_pin"), Icons.Outlined.Pin, 1) { pinTab = true }
                    }
                    // What to ask for comes before the code, folded: the defaults suit most.
                    Disclosure(
                        key = "w3.cpair.options",
                        icon = Icons.Outlined.Tune,
                        title = Strings.get("w3u_cpair_options"),
                        detail = Strings.get(if (mic) "w3u_mic_tile_on" else "w3u_mic_tile_off") + " " +
                            Strings.get(if (guest) "w3u_guest_on" else "w3u_guest_off")
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToggleTile(Icons.Outlined.Mic, Strings.get("w3u_mic_tile"), Strings.get(if (mic) "w3u_mic_tile_on" else "w3u_mic_tile_off"), mic, { mic = it }, Modifier.weight(1f))
                            if (replace == null) {
                                ToggleTile(Icons.Outlined.PersonOutline, Strings.get("w3u_guest_tile"), Strings.get(if (guest) "w3u_guest_on" else "w3u_guest_off"), guest, { guest = it }, Modifier.weight(1f))
                            }
                        }
                    }
                    if (!pinTab) {
                        val bad = code.trim().length >= 20 && !Wfas3Desktop.codeValid(code.trim())
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.take(80) },
                            label = { Text(Strings.get("w3_cpair_code_label")) },
                            singleLine = true,
                            isError = bad,
                            supportingText = { Text(if (bad) Strings.get("w3_cpair_code_invalid") else Strings.get("w3_cpair_code_hint")) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (noCamera) NotePanel(Strings.get("w3_cpair_no_camera"), Icons.Outlined.Info)
                        OutlinedButton(onClick = { noCamera = false; scanning = true }) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(Strings.get("w3_cpair_scan"))
                        }
                    } else {
                        Hint(Strings.get("w3_cpair_pin_hint"))
                        if (beacons.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MorphingBlob(Blob.LOOKING, violet.container, Modifier.size(28.dp), spin = true)
                                Spacer(Modifier.width(10.dp))
                                Hint(Strings.get("w3_cpair_pin_searching"))
                            }
                        }
                        beacons.forEach { b ->
                            key(b.key) {
                                ListRow(
                                    title = b.name.ifBlank { "?" },
                                    detail = b.addr.hostText().let { at ->
                                        if (b.used) Strings.get("w3_cpair_pin_used", at) else at
                                    }.ifBlank { null },
                                    selected = chosen?.key == b.key,
                                    onClick = { chosen = b; pin = "" },
                                    leading = { Avatar(b.name, size = 36.dp, live = chosen?.key == b.key) }
                                )
                            }
                        }
                        if (beacons.isNotEmpty()) Hint(Strings.get("w3_cpair_pin_names_note"))
                        AnimatedVisibility(chosen != null, enter = expandVertically(Motion.defaultSpatial()) + fadeIn(), exit = shrinkVertically(Motion.defaultSpatial()) + fadeOut()) {
                            OutlinedTextField(
                                value = pin,
                                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) },
                                label = { Text(Strings.get("w3_cpair_pin_label")) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    val ready = if (pinTab) chosen != null && pin.length == 8 else Wfas3Desktop.codeValid(code.trim())
                    AnswerRow(
                        no = Strings.get("w3_cancel"),
                        yes = Strings.get("w3_cpair_go"),
                        onNo = { Wfas3Desktop.cancelPairing(); close() },
                        onYes = { go() },
                        yesEnabled = ready
                    )
                }
                is Wfas3Desktop.ClientPairing.Waiting -> {
                    OutlinedButton(onClick = { Wfas3Desktop.cancelPairing(); close() }) { Text(Strings.get("w3_cancel")) }
                }
                is Wfas3Desktop.ClientPairing.Compare -> {
                    CodeTiles(p.code, 3, violet.accent, violet.container)
                    if (!sentYes) {
                        AnswerRow(
                            no = Strings.get("w3_cpair_different"),
                            yes = Strings.get("w3_cpair_same"),
                            onNo = { Wfas3Desktop.confirmCode(false) },
                            onYes = { confirmed = p.code; Wfas3Desktop.confirmCode(true) }
                        )
                    } else {
                        // Waiting for the other side; a different code can still stop it here.
                        BigButton(
                            text = Strings.get("w3u_cpair_abort"),
                            icon = Icons.Outlined.Close,
                            container = MaterialTheme.colorScheme.error,
                            content = MaterialTheme.colorScheme.onError,
                            onClick = { Wfas3Desktop.cancelPairing(); close() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is Wfas3Desktop.ClientPairing.Done -> {
                    BigButton(
                        text = Strings.get("w3u_cpair_done_button"),
                        icon = Icons.Outlined.PlayArrow,
                        onClick = {
                            if (p.slot >= 0) Wfas3UiState.connectRequest.value = p.slot
                            close()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is Wfas3Desktop.ClientPairing.Failed -> {
                    AnswerRow(
                        no = Strings.get("w3_close"),
                        yes = Strings.get("w3_cpair_again"),
                        onNo = { close() },
                        onYes = {
                            code = ""; pin = ""; started = false
                            Wfas3Desktop.resetPairing()
                            if (pinTab) Wfas3Desktop.startPinSearch(app)
                        }
                    )
                }
            }
        }
    }

    if (scanning) {
        QrScannerWindow(
            onScanned = { raw -> scanning = false; pinTab = false; code = raw },
            onNoCamera = { scanning = false; noCamera = true },
            onClose = { scanning = false },
            accept = { raw -> raw.startsWith("WFAS3:") && Wfas3Desktop.codeValid(raw) }
        )
    }
}

/** What happened by itself: a pairing to renew, a server that forgot this PC, and the like. */
@Composable
private fun NoticeQuestion(n: Wfas3Desktop.Notice, pairs: List<Wfas3PairInfo>) {
    when (n) {
        is Wfas3Desktop.Notice.Suspect -> Question(
            title = Strings.get("w3_notice_suspect_title", n.name.ifBlank { "?" }),
            detail = Strings.get("w3_notice_suspect_body", n.name.ifBlank { "?" }),
            icon = Icons.Outlined.Autorenew,
            hue = Hue.Red,
            no = Strings.get("w3_later"),
            yes = Strings.get("w3_repair"),
            onNo = { Wfas3Desktop.dismiss(n) },
            onYes = {
                Wfas3Desktop.dismiss(n)
                Wfas3UiState.openClientPair(pairs.firstOrNull { it.slot == n.pair })
            }
        )
        is Wfas3Desktop.Notice.ForgetAsked -> Question(
            title = Strings.get("w3_notice_forget_title", n.name.ifBlank { "?" }),
            detail = Strings.get("w3_notice_forget_body"),
            icon = Icons.Outlined.LinkOff,
            hue = Hue.Amber,
            no = Strings.get("w3_notice_keep"),
            yes = Strings.get("w3_paired_remove"),
            onNo = { Wfas3Desktop.dismiss(n) },
            onYes = { Wfas3Desktop.dismiss(n); Wfas3Desktop.removePair(n.pair) },
            yesDanger = true
        )
        is Wfas3Desktop.Notice.MicRefused -> Scrim {
            QuestionCard(Strings.get("w3u_notice_mic_title"), Strings.get("w3_notice_mic_refused"), Icons.Outlined.MicOff, toneOf(Hue.Neutral), Blob.WAITING) {
                Button(onClick = { Wfas3Desktop.dismiss(n) }) { Text(Strings.get("w3_close")) }
            }
        }
        is Wfas3Desktop.Notice.Storage -> Scrim {
            QuestionCard(Strings.get("w3u_notice_storage_title"), Strings.get("w3_notice_storage"), Icons.Outlined.ErrorOutline, toneOf(Hue.Red), Blob.ALERT) {
                Button(onClick = { Wfas3Desktop.dismiss(n) }) { Text(Strings.get("w3_close")) }
            }
        }
    }
}

/** Shown in v3 mode before connecting to a server found by v2 discovery or typed by hand. Cancel first. */
@Composable
fun V2ConnectWarningDialog(serverName: String, onCancel: () -> Unit, onConnect: () -> Unit) {
    Scrim {
        QuestionCard(
            title = Strings.get("w3_v2_connect_title", serverName),
            detail = Strings.get("w3_v2_connect_body"),
            icon = Icons.Outlined.History,
            tone = toneOf(Hue.Tertiary),
            blob = Blob.ALERT
        ) {
            AnswerRow(
                no = Strings.get("w3_v2_connect_cancel"),
                yes = Strings.get("w3_v2_connect_go"),
                onNo = onCancel,
                onYes = onConnect
            )
        }
    }
}
