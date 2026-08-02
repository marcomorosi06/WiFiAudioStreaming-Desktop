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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val REJECT_COOLDOWN_MS = 2800L

@Composable
fun QrScannerWindow(
    onScanned: (String) -> Unit,
    onNoCamera: () -> Unit,
    onClose: () -> Unit
) {
    val running = remember { AtomicBoolean(true) }
    val latestFrame = remember { AtomicReference<BufferedImage?>(null) }
    val decoded = remember { AtomicReference<String?>(null) }
    val rejectUntil = remember { AtomicReference(0L) }

    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var notWfas by remember { mutableStateOf(false) }
    var expiredHint by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { running.set(false) }
    }

    LaunchedEffect(Unit) {
        val opened = withContext(Dispatchers.IO) {
            var frameIndex = 0
            WebcamSource.capture(running) { image ->
                latestFrame.set(image)
                frameIndex++
                if (frameIndex % 3 == 0 && decoded.get() == null) {
                    QrDecoder.decode(image)?.let { decoded.set(it) }
                }
                running.get()
            }
        }
        if (!opened) {
            running.set(false)
            onNoCamera()
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        while (running.get()) {
            latestFrame.getAndSet(null)?.let { preview = it.toComposeImageBitmap() }

            decoded.getAndSet(null)?.let { raw ->
                val payload = WfasPairingUri.parse(raw)
                when {
                    payload != null -> {
                        running.set(false)
                        onScanned(raw)
                        return@LaunchedEffect
                    }
                    System.currentTimeMillis() >= rejectUntil.get() -> {
                        rejectUntil.set(System.currentTimeMillis() + REJECT_COOLDOWN_MS)
                        expiredHint = WfasPairingUri.isExpiredUri(raw)
                        notWfas = true
                    }
                }
            }
            delay(33)
        }
    }

    LaunchedEffect(notWfas) {
        if (!notWfas) return@LaunchedEffect
        delay(REJECT_COOLDOWN_MS)
        notWfas = false
    }

    DialogWindow(
        onCloseRequest = { running.set(false); onClose() },
        state = rememberDialogState(width = 720.dp, height = 620.dp),
        title = Strings.get("qr_scan_title")
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            preview?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            ScannerReticle(rejecting = notWfas)

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (notWfas) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.SentimentDissatisfied,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = Strings.get(
                                if (expiredHint) "qr_scan_expired_hint" else "qr_scan_not_wfas"
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = Strings.get("qr_scan_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }

                TextButton(onClick = { running.set(false); onClose() }) {
                    Text(Strings.get("qr_scan_close"), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ScannerReticle(rejecting: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val alarm = MaterialTheme.colorScheme.error
    val frame = if (rejecting) alarm else accent

    Canvas(modifier = Modifier.fillMaxSize()) {
        val side = kotlin.math.min(size.width, size.height) * 0.62f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val scrim = Color.Black.copy(alpha = 0.5f)

        drawRect(color = scrim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(
            color = scrim,
            topLeft = Offset(0f, top + side),
            size = Size(size.width, size.height - top - side)
        )
        drawRect(color = scrim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(
            color = scrim,
            topLeft = Offset(left + side, top),
            size = Size(size.width - left - side, side)
        )

        drawRoundRect(
            color = frame,
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(side * 0.10f, side * 0.10f),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
