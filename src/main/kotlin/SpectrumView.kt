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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SpectrumPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    groove: Float,
    onGrooveChange: (Float) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val inlineGroove = maxWidth >= 700.dp
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource("viz_title"),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (enabled && inlineGroove) {
                        GrooveControl(
                            groove = groove,
                            onGrooveChange = onGrooveChange,
                            sliderWidth = 200.dp
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }

                if (enabled && !inlineGroove) {
                    GrooveControl(
                        groove = groove,
                        onGrooveChange = onGrooveChange,
                        sliderWidth = null
                    )
                }

                AnimatedVisibility(visible = enabled) {
                    SpectrumCanvas(groove = groove)
                }
            }
        }
    }
}

@Composable
private fun GrooveControl(
    groove: Float,
    onGrooveChange: (Float) -> Unit,
    sliderWidth: Dp?
) {
    Row(
        modifier = if (sliderWidth == null) Modifier.fillMaxWidth() else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource("viz_groove_label", (groove * 100f).toInt()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Slider(
            value = groove,
            onValueChange = onGrooveChange,
            valueRange = 0f..SpectrumAnalyzer.GROOVE_MAX,
            modifier = if (sliderWidth == null) Modifier.weight(1f) else Modifier.width(sliderWidth)
        )
    }
}

@Composable
private fun SpectrumCanvas(groove: Float) {
    val barCount = SpectrumAnalyzer.barCount
    val bars = remember { FloatArray(barCount) }
    val peaks = remember { FloatArray(barCount) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(groove) {
        while (true) {
            SpectrumAnalyzer.snapshot(groove, bars, peaks)
            tick++
            delay(33L)
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val frame = tick

    Canvas(modifier = Modifier.fillMaxWidth().height(170.dp)) {
        if (frame < 0) return@Canvas

        val gap = 3f
        val slot = size.width / barCount
        val barW = (slot - gap).coerceAtLeast(1f)
        val radius = CornerRadius(barW / 2f, barW / 2f)

        for (b in 0 until barCount) {
            val x = b * slot
            drawRoundRect(
                color = track,
                topLeft = Offset(x, 0f),
                size = Size(barW, size.height),
                cornerRadius = radius
            )
        }

        for (b in 0 until barCount) {
            val v = bars[b].coerceIn(0f, 1f)
            if (v <= 0.001f) continue
            val h = size.height * v
            val x = b * slot
            val blend = (v * 0.85f).coerceIn(0f, 1f)
            drawRoundRect(
                color = androidx.compose.ui.graphics.lerp(primary, tertiary, blend),
                topLeft = Offset(x, size.height - h),
                size = Size(barW, h),
                cornerRadius = radius
            )
        }

        for (b in 0 until barCount) {
            val p = peaks[b].coerceIn(0f, 1f)
            if (p <= 0.02f) continue
            val y = size.height - size.height * p
            val x = b * slot
            drawRoundRect(
                color = primary.copy(alpha = 0.85f),
                topLeft = Offset(x, y),
                size = Size(barW, 2.5f),
                cornerRadius = CornerRadius(1.5f, 1.5f)
            )
        }
    }
}
