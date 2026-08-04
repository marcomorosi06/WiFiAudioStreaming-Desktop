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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

const val EXPIRED_QR_PAYLOAD = "https://www.marcomorosi.eu/wifi-audio-streaming/expired/"

data class QrInvite(
    val uri: String,
    val key: String,
    val ip: String,
    val port: Int,
    val multicast: Boolean,
    val expEpochSeconds: Long,
    val encryptionForced: Boolean = false
)

private fun formatRemaining(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun QrInviteDialog(
    invite: QrInvite,
    onRegenerate: () -> Unit,
    onRegenerateGroupKey: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val ttl = (invite.expEpochSeconds - (System.currentTimeMillis() / 1000)).coerceAtLeast(1L)
    var remaining by remember(invite.uri) { mutableStateOf(ttl) }
    var revealed by remember(invite.uri) { mutableStateOf(false) }
    var copied by remember(invite.uri) { mutableStateOf(false) }
    var plain by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    LaunchedEffect(invite.uri) {
        while (true) {
            val left = invite.expEpochSeconds - (System.currentTimeMillis() / 1000)
            remaining = left.coerceAtLeast(0L)
            if (left <= 0L) break
            delay(500)
        }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(1800); copied = false }
    }

    val expired = remaining <= 0L
    val plate = if (plain || expired) Color.White
    else lerp(scheme.primaryContainer, Color.White, 0.93f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = Strings.get(
                    if (expired) "qr_pairing_expired_title" else "qr_invite_title"
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = Strings.get(
                        when {
                            expired -> "qr_pairing_expired_body"
                            invite.multicast -> "qr_invite_subtitle_multicast"
                            else -> "qr_invite_subtitle_unicast"
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = scheme.onSurfaceVariant
                )

                Spacer(Modifier.height(18.dp))

                QrPlate(
                    invite = invite,
                    expired = expired,
                    plain = plain,
                    plate = plate,
                    remaining = remaining,
                    onTogglePlain = { if (!expired) plain = !plain }
                )

                Spacer(Modifier.height(14.dp))

                if (!expired) {
                    Text(
                        text = Strings.get("qr_invite_countdown", formatRemaining(remaining)),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (remaining <= 15L) scheme.error else scheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = Strings.get("qr_invite_endpoint", invite.ip, invite.port),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = Strings.get(
                            if (plain) "qr_invite_tap_expressive" else "qr_invite_tap_plain"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = scheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(16.dp))

                    KeyRow(
                        key = invite.key,
                        revealed = revealed,
                        copied = copied,
                        onToggleReveal = { revealed = !revealed },
                        onCopy = {
                            clipboard.setText(AnnotatedString(invite.key))
                            copied = true
                        }
                    )

                    if (invite.multicast && invite.encryptionForced) {
                        Spacer(Modifier.height(12.dp))
                        InfoNote(Strings.get("qr_invite_encryption_note"))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onRegenerate) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    Strings.get(
                        if (expired) "qr_pairing_expired_primary"
                        else if (invite.multicast) "qr_invite_button_multicast"
                        else "qr_invite_button"
                    )
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRegenerateGroupKey != null && !expired) {
                    TextButton(
                        onClick = { confirmRegenerate = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Strings.get("qr_regenerate_button"))
                    }
                }
                TextButton(onClick = onDismiss) { Text(Strings.get("qr_invite_close")) }
            }
        }
    )

    if (confirmRegenerate && onRegenerateGroupKey != null) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(Strings.get("qr_pairing_regenerate_confirm_title")) },
            text = { Text(Strings.get("qr_pairing_regenerate_confirm_body")) },
            confirmButton = {
                Button(
                    onClick = { confirmRegenerate = false; onRegenerateGroupKey() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.onError
                    )
                ) { Text(Strings.get("qr_pairing_regenerate_confirm_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) {
                    Text(Strings.get("qr_pairing_regenerate_confirm_cancel"))
                }
            }
        )
    }
}

@Composable
private fun QrPlate(
    invite: QrInvite,
    expired: Boolean,
    plain: Boolean,
    plate: Color,
    remaining: Long,
    onTogglePlain: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val progress by animateFloatAsState(
        targetValue = (remaining.toFloat() / WfasPairingUri.PAIRING_TTL_SECONDS.toFloat())
            .coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "QrCountdown"
    )
    val ringColor = if (remaining <= 15L) scheme.error else scheme.primary
    val emptyRing = scheme.outlineVariant
    val spentInk = lerp(scheme.onSurfaceVariant, plate, 0.52f)
    val palette = remember(scheme) {
        listOf(
            scheme.primary, scheme.secondary, scheme.tertiary,
            scheme.onPrimaryContainer, scheme.onSecondaryContainer, scheme.onTertiaryContainer
        )
    }

    Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(280.dp)) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = if (expired) emptyRing else ringColor.copy(alpha = 0.18f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (!expired) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(if (plain || expired) 18.dp else 46.dp))
                .background(plate)
                .clickable(enabled = !expired, onClick = onTogglePlain),
            contentAlignment = Alignment.Center
        ) {
            QrCodeCanvas(
                content = if (expired) EXPIRED_QR_PAYLOAD else invite.uri,
                modifier = Modifier.size(184.dp),
                palette = if (expired) listOf(spentInk) else palette,
                ringColor = if (expired) spentInk else scheme.primary,
                eyeColor = if (expired) spentInk else scheme.tertiary,
                plateColor = plate,
                plain = plain,
                minContrast = if (expired) 1f else 5f
            )

            if (expired) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(scheme.errorContainer)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = Strings.get("qr_invite_expired_pill"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    key: String,
    revealed: Boolean,
    copied: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shown = remember(key, revealed) {
        if (revealed) WfasAuth.groupKeyForDisplay(key)
        else WfasAuth.groupKeyForDisplay("•".repeat(key.length))
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Strings.get("qr_invite_key_label"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (copied) {
                    Icon(
                        Icons.Filled.Check, contentDescription = null,
                        modifier = Modifier.size(15.dp), tint = scheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = Strings.get("qr_invite_copied"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shown,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (revealed) scheme.onSurface else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (revealed) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                        contentDescription = Strings.get(
                            if (revealed) "qr_invite_key_hide" else "qr_invite_key_reveal"
                        ),
                        tint = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = Strings.get("qr_invite_copy"),
                        tint = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.tertiaryContainer.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Outlined.Info, contentDescription = null,
            modifier = Modifier.size(17.dp), tint = scheme.onTertiaryContainer
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onTertiaryContainer
        )
    }
}

@Composable
fun QrScanButton(modifier: Modifier = Modifier) {
    OutlinedButton(onClick = { QrPairingState.requestScan() }, modifier = modifier) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(Strings.get("qr_scan_button"))
    }
}

@Composable
fun QrSecurityInfoCard() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.primary.copy(alpha = 0.12f))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Outlined.Info, contentDescription = null,
            modifier = Modifier.size(18.dp), tint = scheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = Strings.get("sec_mode_qr_info_title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.primary
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = Strings.get("sec_mode_qr_info_body"),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EncryptedBadge() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Lock, contentDescription = null,
            modifier = Modifier.size(15.dp), tint = scheme.onTertiaryContainer
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = Strings.get("sec_encrypted"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.onTertiaryContainer
        )
    }
}

@Composable
fun QrPairingBar(
    appSettings: AppSettings,
    isServer: Boolean,
    isStreaming: Boolean,
    isMulticastMode: Boolean,
    localIp: String,
    streamingPort: String,
    onAppSettingsChange: (AppSettings) -> Unit,
    onPairingReady: (PairingPayload) -> Unit,
    showScanButton: Boolean = true
) {
    val invite by QrPairingState.invite.collectAsState()
    val pendingPairing by QrPairingState.pendingPairing.collectAsState()
    val pairingError by QrPairingState.pairingError.collectAsState()
    val noCamera by QrPairingState.noCameraVisible.collectAsState()
    val encrypted by NetworkHandler_v1.sessionEncryptedLive.collectAsState()
    val peerConnected by NetworkHandler_v1.unicastPeerConnected.collectAsState()
    val epochMismatch by NetworkHandler_v1.pendingEpochMismatch.collectAsState()
    val inviteRejected by NetworkHandler_v1.pendingInviteRejected.collectAsState()

    val qrEnabled = appSettings.qrPairingEnabled &&
        SecurityMode.requiresKey(appSettings.securityMode)
    val port = streamingPort.toIntOrNull() ?: 9090

    val scannerVisible by QrPairingState.scannerVisible.collectAsState()

    LaunchedEffect(Unit) {
        PendingDeepLink.take()?.let { QrPairingState.submitDeepLink(it) }
    }

    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            QrPairingState.restoreForcedEncryption(
                appSettings,
                onAppSettingsChange,
                isMulticastMode
            )
        }
    }

    LaunchedEffect(peerConnected, invite?.multicast) {
        if (peerConnected && invite?.multicast == false) QrPairingState.dismissInvite()
    }

    if (encrypted || (isServer && isStreaming && qrEnabled && (isMulticastMode || !peerConnected))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isServer && isStreaming && qrEnabled && (isMulticastMode || !peerConnected)) {
                Button(onClick = {
                    QrPairingState.generateInvite(
                        settings = appSettings,
                        localIp = localIp,
                        port = port,
                        multicast = isMulticastMode,
                        forceNewKey = false,
                        applySettings = onAppSettingsChange
                    )
                }) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Strings.get(
                            if (isMulticastMode) "qr_invite_button_multicast" else "qr_invite_button"
                        )
                    )
                }
            }
            if (encrypted) EncryptedBadge()
        }
    }

    if (!isServer && !isStreaming && showScanButton) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            QrScanButton()
        }
    }

    if (scannerVisible) {
        QrScannerWindow(
            onScanned = { raw ->
                QrPairingState.submitScanned(raw)?.let {
                    QrPairingState.prepareClientForPairing(it)
                    onPairingReady(it)
                }
            },
            onNoCamera = { QrPairingState.noCameraVisible.value = true },
            onClose = { QrPairingState.closeScanner() }
        )
    }

    invite?.let { current ->
        QrInviteDialog(
            invite = current,
            onRegenerate = {
                QrPairingState.generateInvite(
                    appSettings, localIp, port, current.multicast,
                    forceNewKey = false, applySettings = onAppSettingsChange
                )
            },
            onRegenerateGroupKey = if (current.multicast) {
                {
                    QrPairingState.generateInvite(
                        appSettings, localIp, port, true,
                        forceNewKey = true, applySettings = onAppSettingsChange
                    )
                }
            } else null,
            onDismiss = { QrPairingState.dismissInvite() }
        )
    }

    pendingPairing?.let { payload ->
        QrSimpleDialog(
            titleKey = "qr_pairing_deeplink_confirm_title",
            titleArg = NetAddr.display(payload.ip),
            bodyKey = if (payload.isMulticast) "qr_pairing_deeplink_confirm_body_multicast"
            else "qr_pairing_deeplink_confirm_body_unicast",
            confirmKey = "qr_pairing_deeplink_confirm_connect",
            dismissKey = "qr_pairing_deeplink_confirm_ignore",
            onConfirm = {
                QrPairingState.confirmPending()?.let {
                    QrPairingState.prepareClientForPairing(it)
                    onPairingReady(it)
                }
            },
            onDismiss = { QrPairingState.dismissPendingPairing() }
        )
    }

    when (pairingError) {
        PairingError.INVALID -> QrSimpleDialog(
            titleKey = "qr_pairing_invalid_title",
            bodyKey = "qr_pairing_invalid_body",
            confirmKey = "qr_pairing_invalid_cancel",
            onConfirm = { QrPairingState.clearError() },
            onDismiss = { QrPairingState.clearError() }
        )
        PairingError.EXPIRED -> QrSimpleDialog(
            titleKey = "qr_pairing_expired_title",
            bodyKey = "qr_pairing_expired_body",
            confirmKey = "qr_pairing_expired_secondary",
            onConfirm = { QrPairingState.clearError() },
            onDismiss = { QrPairingState.clearError() }
        )
        PairingError.SELF -> QrSimpleDialog(
            titleKey = "qr_pairing_self_title",
            bodyKey = "qr_pairing_self_body",
            confirmKey = "qr_pairing_self_button",
            onConfirm = { QrPairingState.clearError() },
            onDismiss = { QrPairingState.clearError() }
        )
        null -> Unit
    }

    if (epochMismatch) {
        QrSimpleDialog(
            titleKey = "qr_pairing_epoch_mismatch_title",
            bodyKey = "qr_pairing_epoch_mismatch_body",
            confirmKey = "qr_pairing_epoch_mismatch_button",
            onConfirm = { NetworkHandler_v1.clearEpochMismatch() },
            onDismiss = { NetworkHandler_v1.clearEpochMismatch() }
        )
    }

    if (inviteRejected) {
        QrSimpleDialog(
            titleKey = "qr_pairing_superseded_title",
            bodyKey = "qr_pairing_superseded_body",
            confirmKey = "qr_pairing_superseded_dismiss",
            onConfirm = { NetworkHandler_v1.clearInviteRejected() },
            onDismiss = { NetworkHandler_v1.clearInviteRejected() }
        )
    }

    if (noCamera) {
        QrSimpleDialog(
            titleKey = "qr_pairing_no_camera_title",
            bodyKey = "qr_pairing_no_camera_body",
            confirmKey = "qr_pairing_no_camera_close",
            onConfirm = { QrPairingState.dismissNoCamera() },
            onDismiss = { QrPairingState.dismissNoCamera() }
        )
    }
}

@Composable
fun QrSimpleDialog(
    titleKey: String,
    bodyKey: String,
    confirmKey: String,
    dismissKey: String? = null,
    titleArg: String? = null,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.HourglassEmpty, contentDescription = null) },
        title = {
            Text(if (titleArg != null) Strings.get(titleKey, titleArg) else Strings.get(titleKey))
        },
        text = { Text(Strings.get(bodyKey)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(
                    containerColor = scheme.error, contentColor = scheme.onError
                ) else ButtonDefaults.buttonColors()
            ) { Text(Strings.get(confirmKey)) }
        },
        dismissButton = dismissKey?.let {
            { TextButton(onClick = onDismiss) { Text(Strings.get(it)) } }
        }
    )
}
