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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class QrMatrix(val size: Int, private val cells: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean =
        if (x < 0 || y < 0 || x >= size || y >= size) false else cells[y * size + x]

    companion object {
        fun encode(content: String): QrMatrix? {
            if (content.isBlank()) return null
            return runCatching {
                val hints = mapOf(
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
                )
                val matrix = Encoder.encode(content, ErrorCorrectionLevel.M, hints).matrix
                    ?: return@runCatching null
                val n = matrix.width
                val cells = BooleanArray(n * n)
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        cells[y * n + x] = matrix.get(x, y).toInt() == 1
                    }
                }
                QrMatrix(n, cells)
            }.getOrNull()
        }
    }
}

private fun QrMatrix.isFinder(x: Int, y: Int): Boolean {
    val edge = size - 7
    return (x < 7 && y < 7) || (x >= edge && y < 7) || (x < 7 && y >= edge)
}

private fun QrMatrix.isTiming(x: Int, y: Int): Boolean = x == 6 || y == 6

private fun cellHash(x: Int, y: Int): Int =
    abs((x * 73856093) xor (y * 19349663) xor ((x + y) * 83492791))

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

fun clampForContrast(color: Color, against: Color, minRatio: Float = 5f): Color {
    var out = color
    var guard = 0
    while (contrastRatio(out, against) < minRatio && guard < 40) {
        out = lerp(out, Color.Black, 0.07f)
        guard++
    }
    return out
}

private fun DrawScope.drawAnchorPlain(ox: Float, oy: Float, module: Float, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset(ox + module / 2f, oy + module / 2f),
        size = Size(module * 6f, module * 6f),
        style = Stroke(width = module)
    )
    drawRect(
        color = color,
        topLeft = Offset(ox + module * 2f, oy + module * 2f),
        size = Size(module * 3f, module * 3f)
    )
}

private fun DrawScope.drawAnchorSoft(
    ox: Float,
    oy: Float,
    module: Float,
    ringColor: Color,
    eyeColor: Color
) {
    val outer = module * 7f
    drawRoundRect(
        color = ringColor,
        topLeft = Offset(ox + module / 2f, oy + module / 2f),
        size = Size(outer - module, outer - module),
        cornerRadius = CornerRadius(module * 1.6f, module * 1.6f),
        style = Stroke(width = module)
    )
    drawRoundRect(
        color = eyeColor,
        topLeft = Offset(ox + module * 2f, oy + module * 2f),
        size = Size(module * 3f, module * 3f),
        cornerRadius = CornerRadius(module * 0.85f, module * 0.85f)
    )
}

@Composable
fun QrCodeCanvas(
    content: String,
    modifier: Modifier = Modifier,
    palette: List<Color>,
    ringColor: Color,
    eyeColor: Color,
    plateColor: Color = Color.White,
    plain: Boolean = false,
    minContrast: Float = 5f,
    quietZoneModules: Int = 0
) {
    val matrix = remember(content) { QrMatrix.encode(content) }

    val inks = remember(palette, plateColor, minContrast) {
        palette.map { clampForContrast(it, plateColor, minContrast) }
            .ifEmpty { listOf(Color.Black) }
    }
    val ring = remember(ringColor, plateColor, minContrast) {
        clampForContrast(ringColor, plateColor, minContrast + 1f)
    }
    val eye = remember(eyeColor, plateColor, minContrast) {
        clampForContrast(eyeColor, plateColor, minContrast + 1f)
    }

    Box(modifier = modifier.aspectRatio(1f)) {
        if (matrix == null) return@Box

        Canvas(modifier = Modifier.fillMaxSize()) {
            val total = matrix.size + quietZoneModules * 2
            val module = min(size.width, size.height) / total
            val originX = (size.width - module * total) / 2f + module * quietZoneModules
            val originY = (size.height - module * total) / 2f + module * quietZoneModules
            val edge = (matrix.size - 7) * module

            if (plain) {
                drawRect(color = Color.White, topLeft = Offset.Zero, size = size)
                for (y in 0 until matrix.size) {
                    for (x in 0 until matrix.size) {
                        if (!matrix[x, y] || matrix.isFinder(x, y)) continue
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(originX + x * module, originY + y * module),
                            size = Size(module, module)
                        )
                    }
                }
                drawAnchorPlain(originX, originY, module, Color.Black)
                drawAnchorPlain(originX + edge, originY, module, Color.Black)
                drawAnchorPlain(originX, originY + edge, module, Color.Black)
                return@Canvas
            }

            val dotRadius = module * 0.46f
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (!matrix[x, y] || matrix.isFinder(x, y)) continue
                    val centre = Offset(
                        originX + x * module + module / 2f,
                        originY + y * module + module / 2f
                    )
                    val color = if (matrix.isTiming(x, y)) ring
                    else inks[(cellHash(x, y) / 13) % inks.size]
                    drawCircle(color = color, radius = dotRadius, center = centre)
                }
            }

            drawAnchorSoft(originX, originY, module, ring, eye)
            drawAnchorSoft(originX + edge, originY, module, ring, eye)
            drawAnchorSoft(originX, originY + edge, module, ring, eye)
        }
    }
}
