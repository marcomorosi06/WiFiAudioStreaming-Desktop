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
 * Module: DesktopKit
 *
 * The building blocks of the window, Material 3 Expressive at desktop
 * density: motion, colour families, the morphing shapes ("blobs") and the
 * device avatars, panes and rows, connected button groups, tiles, folds,
 * and the questions drawn inside the window instead of popups (so they look
 * the same through Swing on Wayland as anywhere else, and can never open
 * behind another window). No page logic lives here.
 *
 * Adapted from the interfaces of WiFi Screen Streaming (desktop) and of the
 * Android app, by the same author and under the same licence (EUPL 1.2).
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.CardColors
import androidx.compose.material3.Switch
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// -- motion ------------------------------------------------------------------------

/** The expressive spring tokens, at desktop pace. */
object Motion {
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
}

// -- colour families ---------------------------------------------------------------

/** A family of colours: the shape, what sits on it, and a strong accent for rings and dots. */
@Immutable
data class Tone(val container: Color, val onContainer: Color, val accent: Color)

enum class Hue { Primary, Secondary, Tertiary, Amber, Violet, Green, Pink, Red, Teal, Neutral }

/**
 * True when the user turned on the Material colours in the settings: the
 * sections then get colours of their own. False (the default): every
 * colour comes from the theme, so the window follows the chosen colour.
 */
val LocalMaterialHues: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * The colours of a family in the current scheme.
 *
 * By default they all come from the theme. The names say what a family is
 * for: Violet and Pink go with the primary roles, Teal and Green with the
 * secondary; Amber (what asks for attention) takes the tertiary, and Red is
 * the error colour.
 *
 * With [LocalMaterialHues] on, the fixed hues get colours of their own, as
 * in the Material 3 Expressive palettes. They lean a little towards the
 * primary of the scheme, so they sit well with any seed colour.
 */
@Composable
fun toneOf(hue: Hue): Tone {
    val scheme = MaterialTheme.colorScheme
    val primary = Tone(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
    val secondary = Tone(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
    val tertiary = Tone(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
    when (hue) {
        Hue.Primary -> return primary
        Hue.Secondary -> return secondary
        Hue.Tertiary -> return tertiary
        Hue.Red -> return Tone(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
        Hue.Neutral -> return Tone(scheme.surfaceContainerHighest, scheme.onSurface, scheme.outline)
        else -> Unit
    }
    if (!LocalMaterialHues.current) {
        return when (hue) {
            Hue.Teal, Hue.Green -> secondary
            Hue.Amber -> tertiary
            else -> primary
        }
    }
    val dark = scheme.background.luminance() < 0.5f
    fun fixed(light: Tone, darkTone: Tone): Tone {
        val t = if (dark) darkTone else light
        return Tone(
            lerp(t.container, scheme.primaryContainer, 0.12f),
            t.onContainer,
            lerp(t.accent, scheme.primary, 0.12f)
        )
    }
    return when (hue) {
        Hue.Amber -> fixed(
            Tone(Color(0xFFFFE08B), Color(0xFF3A2C00), Color(0xFF7A5900)),
            Tone(Color(0xFF5A4300), Color(0xFFFFE08B), Color(0xFFF2C14A))
        )
        Hue.Violet -> fixed(
            Tone(Color(0xFFEADDFF), Color(0xFF21005D), Color(0xFF6750A4)),
            Tone(Color(0xFF4F378B), Color(0xFFEADDFF), Color(0xFFD0BCFF))
        )
        Hue.Green -> fixed(
            Tone(Color(0xFFB7F2C2), Color(0xFF00210B), Color(0xFF1E6C35)),
            Tone(Color(0xFF005226), Color(0xFFB7F2C2), Color(0xFF8BD89A))
        )
        Hue.Pink -> fixed(
            Tone(Color(0xFFFFD8E8), Color(0xFF3B0727), Color(0xFF8E4A6D)),
            Tone(Color(0xFF71335A), Color(0xFFFFD8E8), Color(0xFFFFAFD6))
        )
        else -> fixed(
            Tone(Color(0xFFB8EAFF), Color(0xFF001F29), Color(0xFF00677F)),
            Tone(Color(0xFF004E60), Color(0xFFB8EAFF), Color(0xFF5FD4FD))
        )
    }
}

/** The colour of a pane: a step above the page in the dark theme, white on a grey page in the light one. */
@Composable
fun paneColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() > 0.5f) scheme.surfaceContainerLowest else scheme.surfaceContainer
}

/** The colour of a tile or a field inside a pane. */
@Composable
fun tileColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() > 0.5f) scheme.surfaceContainer else scheme.surfaceContainerHigh
}

@Composable
fun dangerColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError
)

// -- blobs -------------------------------------------------------------------------

/** A rounded star: [lobes] bumps of relative [depth] around a circle. 0 lobes is a circle. */
data class Blob(val lobes: Int, val depth: Float) {
    companion object {
        val CIRCLE = Blob(0, 0f)
        /** Waiting for something: a many-sided cookie, turning. */
        val WAITING = Blob(9, 0.07f)
        /** Something is live: a four-leaf clover. */
        val LIVE = Blob(4, 0.13f)
        /** Something needs attention: a tight, spiky outline. */
        val ALERT = Blob(12, 0.06f)
        /** Looking around on the network. */
        val LOOKING = Blob(6, 0.09f)
        /** Done, all well: a soft flower. */
        val DONE = Blob(8, 0.09f)
    }
}

private fun radius(blob: Blob, angle: Double): Double =
    if (blob.lobes == 0 || blob.depth == 0f) 1.0
    else 1.0 - blob.depth + blob.depth * cos(blob.lobes * angle)

private fun outline(from: Blob, to: Blob, mix: Float, size: Size, turnDegrees: Float): Path {
    val path = Path()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = min(cx, cy)
    val turn = turnDegrees * PI / 180.0
    val steps = 160
    for (i in 0 until steps) {
        val a = 2.0 * PI * i / steps
        val k = (radius(from, a) + (radius(to, a) - radius(from, a)) * mix).coerceIn(0.3, 1.08)
        val x = cx + (r * k * cos(a + turn)).toFloat()
        val y = cy + (r * k * sin(a + turn)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * A shape that morphs to [blob] whenever it changes, with a little
 * overshoot, in [color], turning slowly while [spin] is on. The content (an
 * icon, a letter) stays upright in the middle. Drawn from a formula: no
 * shape library is needed.
 */
@Composable
fun MorphingBlob(
    blob: Blob,
    color: Color,
    modifier: Modifier = Modifier,
    spin: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {}
) {
    var from by remember { mutableStateOf(blob) }
    var to by remember { mutableStateOf(blob) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(blob) {
        if (blob != to) {
            // Halfway or more into the last change counts as arrived there.
            if (mix.value >= 0.5f) from = to
            to = blob
            mix.snapTo(0f)
            mix.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
        }
    }
    val turn = remember { Animatable(0f) }
    LaunchedEffect(spin) {
        // When it stops, it stays where it is: no jump back.
        while (spin && isActive) {
            turn.snapTo(turn.value % 360f)
            turn.animateTo(turn.value + 360f, tween(16_000, easing = LinearEasing))
        }
    }
    val tint by animateColorAsState(color, tween(450), label = "blobColour")
    Box(
        modifier.drawBehind { drawPath(outline(from, to, mix.value, size, turn.value), tint) },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** An icon on a blob, in a tone. */
@Composable
fun BlobIcon(icon: ImageVector, tone: Tone, blob: Blob = Blob.WAITING, size: Dp = 40.dp, spin: Boolean = false, filled: Boolean = false) {
    MorphingBlob(blob, if (filled) tone.accent else tone.container, Modifier.size(size), spin) {
        Icon(icon, contentDescription = null, tint = if (filled) tone.container else tone.onContainer, modifier = Modifier.size(size * 0.5f))
    }
}

/** A stable, non-negative number from a name: the same name always looks the same. */
private fun signature(name: String): Int = Math.floorMod(name.fold(17) { acc, c -> acc * 31 + c.code }, 1_000_003)

private val AVATAR_SHAPES = listOf(Blob(4, 0.12f), Blob(5, 0.11f), Blob(6, 0.09f), Blob(8, 0.07f), Blob(9, 0.06f), Blob(12, 0.05f))

/**
 * A device: its initial in a shape picked from its name, in one of the
 * containers of the theme. [live] makes it turn, in the primary colour.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, live: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val sign = remember(name) { signature(name) }
    val (back, front) = when {
        live -> scheme.primary to scheme.onPrimary
        sign % 3 == 0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        sign % 3 == 1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    MorphingBlob(
        blob = if (live) Blob.LIVE else AVATAR_SHAPES[sign % AVATAR_SHAPES.size],
        color = back,
        spin = live,
        modifier = modifier.size(size)
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = if (size >= 48.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = front
        )
    }
}

// -- panes -------------------------------------------------------------------------

/**
 * The list on the left, what is selected on the right. The list is about
 * two fifths of the width, never narrower than [minList] nor wider than
 * [maxList]; on a narrow window the two stack.
 */
@Composable
fun TwoPanes(
    modifier: Modifier = Modifier,
    minList: Dp = 300.dp,
    maxList: Dp = 400.dp,
    list: @Composable ColumnScope.() -> Unit,
    detail: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth < 720.dp) {
            ScrollPane(Modifier.fillMaxSize()) {
                list()
                Spacer(Modifier.height(12.dp))
                detail()
            }
        } else {
            val listWidth = (maxWidth * 0.4f).coerceIn(minList, maxList)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollPane(Modifier.width(listWidth).fillMaxHeight(), content = list)
                ScrollPane(Modifier.weight(1f).fillMaxHeight(), content = detail)
            }
        }
    }
}

/** A rounded pane whose content scrolls when it is taller than the window. */
@Composable
fun ScrollPane(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = paneColor()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 18.dp))
        }
    }
}

/** The big line at the top of a pane, with an optional second line and something on its right. */
@Composable
fun PaneTitle(title: String, detail: String? = null, mono: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        actions()
    }
}

/** The small heading of a group of rows. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = color,
        modifier = modifier.padding(top = 10.dp, start = 4.dp)
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A rounded block in the colour of a tile, for content that belongs together. */
@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = tileColor(), corner: Dp = 20.dp, content: @Composable () -> Unit) {
    val shown by animateColorAsState(color, Motion.defaultEffects(), label = "panel")
    Surface(shape = RoundedCornerShape(corner), color = shown, modifier = modifier.fillMaxWidth()) { content() }
}

/** A short note with an icon, in a tone. */
@Composable
fun NotePanel(text: String, icon: ImageVector, hue: Hue = Hue.Neutral, modifier: Modifier = Modifier) {
    val tone = toneOf(hue)
    val back = if (hue == Hue.Neutral) tileColor() else tone.container
    val front = if (hue == Hue.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else tone.onContainer
    Surface(shape = RoundedCornerShape(14.dp), color = back, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (hue == Hue.Neutral) MaterialTheme.colorScheme.primary else front, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = front)
        }
    }
}

/** A small label with an icon, in a tone. */
@Composable
fun KitChip(text: String, icon: ImageVector?, hue: Hue, modifier: Modifier = Modifier) {
    val tone = toneOf(hue)
    Surface(shape = CircleShape, color = tone.container, contentColor = tone.onContainer, modifier = modifier) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A dot that beats: something is live. */
@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier) {
    val beat = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            beat.animateTo(1.35f, tween(520))
            beat.animateTo(1f, tween(680))
        }
    }
    Box(
        modifier
            .size(10.dp)
            .graphicsLayer { scaleX = beat.value; scaleY = beat.value }
            .clip(CircleShape)
            .background(color)
    )
}

/** A row of a list: something on the left, a title and a detail, something on the right. */
@Composable
fun ListRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    monoDetail: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val back by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        spring(stiffness = 1400f),
        label = "row"
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(back).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (monoDetail) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing()
    }
}

/** A row that states something, with an icon. */
@Composable
fun FactRow(icon: ImageVector, title: String, hint: String?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tileColor()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (hint != null) Hint(hint)
        }
        trailing()
    }
}

// -- cards and rows of the main screen ---------------------------------------------

/**
 * A card of the main screen: a flat rounded surface in the colour of the
 * panes, as in WiFi Screen Streaming, with no shadow. [colors] keeps the
 * signature of the old elevated cards, so a warning can still be red.
 */
@Composable
fun KitCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val back = colors?.containerColor ?: paneColor()
    val front = colors?.contentColor?.takeIf { it != Color.Unspecified } ?: contentColorFor(back)
    val shown by animateColorAsState(back, Motion.defaultEffects(), label = "kitCard")
    Surface(modifier = modifier, shape = shape, color = shown, contentColor = front) { Column(content = content) }
}

/** The head of a card: its shape with the icon, the title, a line under it, actions on the right. */
@Composable
fun CardTitle(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    hue: Hue = Hue.Primary,
    blob: Blob = Blob.WAITING,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // With the Material colours on, the shape is filled with the accent of its family.
        BlobIcon(icon, toneOf(hue), blob, size = 44.dp, filled = LocalMaterialHues.current && hue != Hue.Neutral)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Hint(detail)
        }
        actions()
    }
}

/**
 * A setting that is on or off, as a row: the whole row is the target, the
 * switch only shows the state. Its corners round a little more when on.
 */
@Composable
fun SwitchRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (checked) 22.dp else 16.dp, Motion.fastSpatial(), label = "switchCorner")
    val back by animateColorAsState(
        if (checked && enabled) scheme.secondaryContainer.copy(alpha = 0.7f) else tileColor(),
        Motion.fastEffects(),
        label = "switchBack"
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(back)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (checked && enabled) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.5f)
            )
            if (detail != null) Hint(detail)
        }
        // The row takes the click; the switch only shows it.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One option of a [ChoiceGroup]. */
data class Choice<T>(val value: T, val label: String, val icon: ImageVector)

/**
 * A choice among a few, as an M3 Expressive connected button group: the
 * chosen one filled and rounder, the others tonal, two points apart.
 */
@Composable
fun <T> ChoiceGroup(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ButtonGroup(modifier.fillMaxWidth()) {
        choices.forEachIndexed { i, c ->
            val on = c.value == selected
            val inner by animateDpAsState(if (on) 20.dp else 6.dp, Motion.fastSpatial(), label = "choiceCorner")
            val outer = 20.dp
            val shape = RoundedCornerShape(
                topStart = if (i == 0) outer else inner,
                bottomStart = if (i == 0) outer else inner,
                topEnd = if (i == choices.size - 1) outer else inner,
                bottomEnd = if (i == choices.size - 1) outer else inner
            )
            val content: @Composable RowScope.() -> Unit = {
                Icon(c.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (on) {
                Button(onClick = { onSelect(c.value) }, enabled = enabled, shape = shape, modifier = Modifier.weight(1f).heightIn(min = 44.dp), content = content)
            } else {
                FilledTonalButton(onClick = { onSelect(c.value) }, enabled = enabled, shape = shape, modifier = Modifier.weight(1f).heightIn(min = 44.dp), content = content)
            }
        }
    }
}

/** A blob that turns slowly while something is being looked for, with a line beside it. */
@Composable
fun SearchingRow(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MorphingBlob(Blob.LIVE, scheme.secondaryContainer, Modifier.size(28.dp), spin = true) {}
        Spacer(Modifier.width(12.dp))
        Hint(text)
    }
}

// -- buttons -----------------------------------------------------------------------

/**
 * The shape of the button at [index] of [count] in a connected group: the
 * outer ends fully round, the inner ones nearly square, as M3 Expressive
 * draws a button group.
 */
fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 20.dp
    val inner = 6.dp
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        bottomStart = if (first) outer else inner,
        topEnd = if (last) outer else inner,
        bottomEnd = if (last) outer else inner
    )
}

/** The buttons of a connected group, two points apart. */
@Composable
fun ButtonGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A large button with an icon, whose corners close a little when it is pressed. */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 12.dp else 24.dp, Motion.fastSpatial(), label = "bigCorner")
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        interactionSource = interaction,
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The two answers of a question, in a connected group: the safe one first
 * and filled, so it is the easy one; [yes] tonal, or in [yesColors] when
 * saying yes lowers protection or erases something.
 */
@Composable
fun AnswerRow(
    no: String,
    yes: String,
    onNo: () -> Unit,
    onYes: () -> Unit,
    modifier: Modifier = Modifier,
    yesColors: ButtonColors? = null,
    yesEnabled: Boolean = true
) {
    ButtonGroup(modifier.fillMaxWidth()) {
        Button(onClick = onNo, shape = groupShape(0, 2), modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
            Text(no, maxLines = 2, textAlign = TextAlign.Center)
        }
        if (yesColors != null) {
            Button(onClick = onYes, enabled = yesEnabled, shape = groupShape(1, 2), colors = yesColors, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(yes, maxLines = 2, textAlign = TextAlign.Center)
            }
        } else {
            FilledTonalButton(onClick = onYes, enabled = yesEnabled, shape = groupShape(1, 2), modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Text(yes, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

// -- tiles -------------------------------------------------------------------------

/** A setting that is on or off, as a tile: the whole tile is the target. */
@Composable
fun ToggleTile(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (checked) 26.dp else 16.dp, Motion.fastSpatial(), label = "tileCorner")
    val back by animateColorAsState(
        when {
            !enabled -> scheme.surfaceContainer
            checked -> scheme.primaryContainer
            else -> tileColor()
        },
        Motion.fastEffects(),
        label = "tileBack"
    )
    val front by animateColorAsState(if (checked) scheme.onPrimaryContainer else scheme.onSurface, Motion.fastEffects(), label = "tileFront")
    Surface(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        color = back,
        contentColor = front,
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MorphingBlob(if (checked) Blob.LIVE else Blob.WAITING, if (checked) scheme.primary else scheme.surfaceContainerHighest, Modifier.size(36.dp)) {
                Icon(icon, contentDescription = null, tint = if (checked) scheme.onPrimary else scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = front.copy(alpha = 0.8f))
        }
    }
}

/**
 * A ready-made choice: a tile with its own shape and colour that morphs
 * and fills when chosen. Used for presets, where one click sets several values.
 */
@Composable
fun PresetTile(
    icon: ImageVector,
    title: String,
    detail: String,
    hue: Hue,
    blob: Blob,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = toneOf(hue)
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 28.dp else 18.dp, Motion.fastSpatial(), label = "presetCorner")
    val back by animateColorAsState(if (selected) tone.container else tileColor(), Motion.fastEffects(), label = "presetBack")
    val front by animateColorAsState(if (selected) tone.onContainer else scheme.onSurface, Motion.fastEffects(), label = "presetFront")
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = back,
        contentColor = front,
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // With the Material colours on, the tiles not chosen keep a hint of their family.
                val hued = LocalMaterialHues.current
                MorphingBlob(
                    if (selected) blob else Blob.CIRCLE,
                    when {
                        selected -> tone.accent
                        hued -> tone.container
                        else -> scheme.surfaceContainerHighest
                    },
                    Modifier.size(38.dp),
                    spin = selected
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = when {
                            selected -> tone.container
                            hued -> tone.onContainer
                            else -> scheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(visible = selected, enter = fadeIn() + scaleIn(), exit = fadeOut()) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = tone.accent, modifier = Modifier.size(18.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = front.copy(alpha = 0.78f), minLines = 2, maxLines = 3)
        }
    }
}

/**
 * Everything a curious user may tune, folded away by default: a header with
 * a short summary and a chevron that turns, and the content that opens with
 * a spring. [key] keeps the open state, per place.
 */
@Composable
fun Disclosure(
    key: String,
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.fastSpatial(), label = "moreTurn")
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = tileColor(), modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BlobIcon(icon, toneOf(Hue.Tertiary), Blob.WAITING, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (badge != null) {
                            Spacer(Modifier.width(8.dp))
                            KitChip(badge, null, Hue.Tertiary)
                        }
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(Motion.defaultSpatial()) + fadeIn(),
                exit = shrinkVertically(Motion.defaultSpatial()) + fadeOut()
            ) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

/** A code in tiles, in groups of [group], one character each. */
@Composable
fun CodeTiles(code: String, group: Int, accent: Color, onAccent: Color, tile: Dp = 44.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { i, c ->
            if (i > 0 && i % group == 0) Spacer(Modifier.width(10.dp))
            val appear = remember(code) { Animatable(0f) }
            LaunchedEffect(code) {
                delay(35L * i)
                appear.animateTo(1f, Motion.fastSpatial())
            }
            Box(
                Modifier
                    .size(width = tile, height = tile * 1.25f)
                    .graphicsLayer {
                        val v = appear.value
                        scaleX = 0.6f + 0.4f * v
                        scaleY = 0.6f + 0.4f * v
                        alpha = v.coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Text(c.toString(), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = onAccent)
            }
        }
    }
}

/** The time left of something that closes by itself: a bar that empties, and a line under it. */
@Composable
fun Countdown(closesAtMs: Long, totalMs: Long, color: Color, label: (Long) -> String) {
    if (closesAtMs <= 0L) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(closesAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= closesAtMs) break
            delay(1000)
        }
    }
    val left = (closesAtMs - now).coerceAtLeast(0L)
    val fraction by animateFloatAsState(
        (left.toFloat() / totalMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
        tween(1000, easing = LinearEasing),
        label = "left"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(color.copy(alpha = 0.18f))) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .graphicsLayer {
                        scaleX = fraction
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(label(left / 1000), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -- questions ---------------------------------------------------------------------

/** The dimmed window behind a question; clicks on it do nothing. */
@Composable
fun Scrim(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * The card of a question, drawn inside the window: a status shape on top
 * that morphs as the question moves on, the title, an optional detail, and
 * the body. [secure] is a reminder for the reader of the code: what is on
 * this card is a secret for as long as it is shown.
 */
@Composable
fun QuestionCard(
    title: String,
    detail: String?,
    icon: ImageVector,
    tone: Tone,
    blob: Blob = Blob.WAITING,
    spin: Boolean = false,
    width: Dp = 520.dp,
    body: @Composable ColumnScope.() -> Unit
) {
    val grow = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.defaultSpatial()) }
    Surface(
        modifier = Modifier.widthIn(max = width).padding(24.dp).graphicsLayer { scaleX = grow.value; scaleY = grow.value },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MorphingBlob(blob, tone.container, Modifier.size(64.dp), spin) {
                Icon(icon, contentDescription = null, tint = tone.onContainer, modifier = Modifier.size(28.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(2.dp))
            body()
        }
    }
}

/** A plain question: title, lines, and the two answers with the safe one first. */
@Composable
fun Question(
    title: String,
    detail: String?,
    icon: ImageVector,
    hue: Hue,
    no: String,
    yes: String,
    onNo: () -> Unit,
    onYes: () -> Unit,
    yesDanger: Boolean = false
) {
    Scrim {
        QuestionCard(title, detail, icon, toneOf(hue), Blob.ALERT) {
            AnswerRow(no = no, yes = yes, onNo = onNo, onYes = onYes, yesColors = if (yesDanger) dangerColors() else null)
        }
    }
}

/** A dot in the corner of a rail item while what it leads to is live. */
@Composable
fun BoxScope.CornerDot(color: Color) {
    Box(Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 3.dp).size(8.dp).clip(CircleShape).background(color))
}
