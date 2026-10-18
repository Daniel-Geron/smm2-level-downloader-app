@file:OptIn(ExperimentalTextApi::class)

package io.github.daniel_geron.smm2injector

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Colors taken from Super Mario Maker 2's menus. */
object Smm {
    val Yellow = Color(0xFFFCC917)
    val YellowDark = Color(0xFFDDA000)
    val YellowLight = Color(0xFFFFDC4A)
    val Cream = Color(0xFFFFF7E2)
    val CreamDark = Color(0xFFF9E6C2)
    val Beige = Color(0xFFE3CFA2)
    val Maroon = Color(0xFF5B1A1A)
    val MaroonDark = Color(0xFF380D0D)
    val Orange = Color(0xFFFF5A14)
    val Navy = Color(0xFF2B2D45)
    val Green = Color(0xFF3AAA45)
    val Amber = Color(0xFFF5A623)
    val Red = Color(0xFFD8322B)
    val DisabledArrow = Color(0xFFB9C6D8)
}

/** Nunito is a rounded font close to the game's (SIL Open Font License, see THIRD_PARTY_NOTICES.md). */
private val Nunito = FontFamily(
    listOf(400, 600, 700, 800, 900).map {
        Font(R.font.nunito, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
    }
)

@Composable
fun SmmTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.n(weight: Int) = copy(fontFamily = Nunito, fontWeight = FontWeight(weight), letterSpacing = 0.sp)
    val typography = Typography(
        displayLarge = base.displayLarge.n(900),
        displayMedium = base.displayMedium.n(900),
        displaySmall = base.displaySmall.n(900),
        headlineLarge = base.headlineLarge.n(900),
        headlineMedium = base.headlineMedium.n(900),
        headlineSmall = base.headlineSmall.n(900),
        titleLarge = base.titleLarge.n(900),
        titleMedium = base.titleMedium.n(800),
        titleSmall = base.titleSmall.n(800),
        bodyLarge = base.bodyLarge.n(700),
        bodyMedium = base.bodyMedium.n(700),
        bodySmall = base.bodySmall.n(600),
        labelLarge = base.labelLarge.n(800),
        labelMedium = base.labelMedium.n(800),
        labelSmall = base.labelSmall.n(800),
    )
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Smm.Maroon,
            onPrimary = Color.White,
            primaryContainer = Smm.Yellow,
            onPrimaryContainer = Smm.Maroon,
            secondary = Smm.Orange,
            background = Smm.Cream,
            onBackground = Smm.Maroon,
            surface = Color.White,
            onSurface = Smm.Maroon,
            surfaceVariant = Smm.CreamDark,
            onSurfaceVariant = Smm.Maroon.copy(alpha = 0.7f),
            outline = Smm.Beige,
            error = Smm.Red,
        ),
        typography = typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides Smm.Maroon, content = content)
    }
}

/** The game's orange selection cursor: four triangles around the corners, gently pulsing. */
fun Modifier.cursorCorners(visible: Boolean): Modifier = if (!visible) this else composed {
    val pulse by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 2f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "cursorPulse",
    )
    drawWithContent {
        drawContent()
        val o = pulse.dp.toPx()
        val l = 13.dp.toPx()
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) = drawPath(
            Path().apply { moveTo(x, y); lineTo(x + dx, y); lineTo(x, y + dy); close() },
            Smm.Orange,
        )
        corner(-o, -o, l, l)
        corner(w + o, -o, -l, l)
        corner(-o, h + o, l, -l)
        corner(w + o, h + o, -l, -l)
    }
}

enum class SmmButtonStyle(val face: Color, val edge: Color, val text: Color) {
    Yellow(Smm.Yellow, Smm.YellowDark, Smm.Maroon),
    White(Color.White, Smm.Beige, Smm.Maroon),
    Maroon(Smm.Maroon, Smm.MaroonDark, Color.White),
}

/** A chunky game-style button that sinks when pressed. [highlighted] shows the cursor permanently. */
@Composable
fun SmmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SmmButtonStyle = SmmButtonStyle.Yellow,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    small: Boolean = false,
    large: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth = if (small) 3.dp else 4.dp
    val sink = if (pressed && enabled) depth - 1.dp else 0.dp
    Box(
        modifier
            .cursorCorners(enabled && (highlighted || pressed))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .drawBehind {
                val r = CornerRadius(10.dp.toPx())
                val d = depth.toPx()
                val face = Size(size.width, size.height - d)
                drawRoundRect(style.edge, Offset(0f, d), face, r)
                drawRoundRect(style.face, Offset(0f, sink.toPx()), face, r)
                if (style == SmmButtonStyle.White) {
                    drawRoundRect(style.edge, Offset(0f, sink.toPx()), face, r, style = Stroke(1.dp.toPx()))
                }
            }
            .padding(bottom = depth)
            .offset(y = sink)
            .defaultMinSize(minHeight = if (small) 34.dp else if (large) 58.dp else 46.dp)
            .padding(horizontal = if (small) 12.dp else 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, null, tint = style.text, modifier = Modifier.size(if (small) 16.dp else 20.dp)) }
            Text(
                text,
                color = style.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (small) 14.sp else if (large) 20.sp else 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A white course-list card with a soft beige edge underneath. */
@Composable
fun SmmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Color.White,
    padding: Dp = 14.dp,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier
            .cursorCorners(pressed)
            .drawBehind {
                val r = CornerRadius(12.dp.toPx())
                val d = 3.dp.toPx()
                val face = Size(size.width, size.height - d)
                drawRoundRect(Smm.Beige, Offset(0f, d), face, r)
                drawRoundRect(color, Offset.Zero, face, r)
            }
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .padding(bottom = 3.dp)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun SmmSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SmmCard(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
        content()
    }
}

/** The full-screen yellow menu frame: header, tab row and the cream content panel. */
@Composable
fun SmmFrame(
    title: String,
    tabs: List<String>,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Smm.Yellow)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                BackButton(onBack)
                Spacer(Modifier.width(10.dp))
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { i, label ->
                val selected = i == selectedTab
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .cursorCorners(selected)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(if (selected) Smm.Maroon else Smm.YellowLight)
                        .border(
                            1.5.dp,
                            if (selected) Smm.MaroonDark else Smm.YellowDark,
                            RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp),
                        )
                        .clickable(role = Role.Tab) { onTab(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) Smm.Yellow else Smm.Maroon,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Smm.Cream)
                .border(2.dp, Smm.YellowDark.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        ) {
            content()
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF7D8696))
            .border(2.dp, Color(0xFFB6BDC9), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** The game's black square close button. */
@Composable
fun SmmCloseButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, "Close", tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

/** The dark rounded info pill from the top of the game's menus ("Courses uploaded 1/32"). */
@Composable
fun StatusPill(dot: Color, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Smm.Navy)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(dot, CircleShape)
        )
        Spacer(Modifier.width(7.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** A cream dialog with the game's yellow band at the top and big buttons at the bottom. */
@Composable
fun SmmDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    header: (@Composable () -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    scrollable: Boolean = true,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Smm.CreamDark),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(Smm.Yellow)
            )
            if (title != null || header != null || onClose != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (header != null) header() else Text(title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.width(8.dp))
                    onClose?.let { SmmCloseButton(it) }
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            if (buttons != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 18.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    content = buttons,
                )
            }
        }
    }
}

/** A filter toggle: maroon with a yellow check when selected, like the game's search filters. */
@Composable
fun SmmChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .cursorCorners(pressed)
            .clip(shape)
            .background(if (selected) Smm.Maroon else Color.White)
            .border(1.5.dp, if (selected) Smm.MaroonDark else Smm.Beige, shape)
            .clickable(interaction, indication = null, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = Smm.Yellow, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = if (selected) Color.White else Smm.Maroon, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** "Difficulty ◀ All ▶": steps through [options], with "All" (null) first when [allowAll]. */
@Composable
fun SmmSelector(label: String, options: List<String>, selected: Int?, allowAll: Boolean = true, onSelect: (Int?) -> Unit) {
    val min = if (allowAll) -1 else 0
    val pos = selected ?: -1
    fun go(p: Int) = onSelect(if (p < 0) null else p)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(92.dp))
        SelectorArrow(pointsLeft = true, enabled = pos > min) { go(pos - 1) }
        Box(
            Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFEFECE4)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (pos < 0) "All" else options[pos], fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
        SelectorArrow(pointsLeft = false, enabled = pos < options.size - 1) { go(pos + 1) }
    }
}

@Composable
private fun SelectorArrow(pointsLeft: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Canvas(
        Modifier
            .size(38.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (pointsLeft) { moveTo(w, 0f); lineTo(0f, h / 2); lineTo(w, h) } else { moveTo(0f, 0f); lineTo(w, h / 2); lineTo(0f, h) }
            close()
        }
        drawPath(path, if (enabled) Smm.Yellow else Smm.DisabledArrow)
        if (enabled) drawPath(path, Smm.YellowDark, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
fun SmmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        placeholder = { Text(placeholder, style = textStyle, color = Smm.Maroon.copy(alpha = 0.35f)) },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Smm.Orange,
            unfocusedBorderColor = Smm.Beige,
            cursorColor = Smm.Maroon,
            focusedTextColor = Smm.Maroon,
            unfocusedTextColor = Smm.Maroon,
        ),
    )
}

@Composable
fun SmmProgressBar(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction },
        color = Smm.Orange,
        trackColor = Smm.CreamDark,
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50)),
    )
}

@Composable
fun smmSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Smm.Yellow,
    checkedTrackColor = Smm.Maroon,
    checkedBorderColor = Smm.MaroonDark,
    uncheckedThumbColor = Smm.Beige,
    uncheckedTrackColor = Color.White,
    uncheckedBorderColor = Smm.Beige,
)

@Composable
fun smmSliderColors() = SliderDefaults.colors(
    thumbColor = Smm.Orange,
    activeTrackColor = Smm.Maroon,
    inactiveTrackColor = Smm.Beige,
)

// ------------------------------------------------------------------------------- course card parts

/** Game-style logo badge, by style index (SMB1, SMB3, SMW, NSMBU, SM3DW). */
@Composable
fun StyleBadge(style: Int) {
    val (label, bg, fg) = when (style) {
        0 -> Triple("SMB", Color(0xFFD9622B), Color.White)
        1 -> Triple("SMB3", Color(0xFFF6E3BC), Color(0xFFD22B2B))
        2 -> Triple("SMW", Color(0xFF3E9F46), Color.White)
        3 -> Triple("NSMBU", Color(0xFF1C86E0), Color.White)
        4 -> Triple("3D World", Color(0xFFE23A2E), Color.White)
        else -> Triple("?", Smm.Beige, Smm.Maroon)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** Same as [StyleBadge] but from the MariOver API's style name ("SMB1", "SM3DW"…). */
@Composable
fun StyleBadge(styleName: String) = StyleBadge(listOf("SMB1", "SMB3", "SMW", "NSMBU", "SM3DW").indexOf(styleName.uppercase()))

/**
 * Stand-in for a course thumbnail (the archive has none): the theme's sky and ground colors,
 * with the upload date across the bottom like in the game.
 */
@Composable
fun ThemeThumbnail(theme: Int, date: String?, modifier: Modifier = Modifier) {
    val (sky, ground) = when (theme) {
        0 -> Color(0xFF63B6F4) to Color(0xFFB8682A)
        1 -> Color(0xFF12153A) to Color(0xFF7A4B2A)
        2 -> Color(0xFF2C2B33) to Color(0xFF8C8A90)
        3 -> Color(0xFFF4A65C) to Color(0xFF6B4A2B)
        4 -> Color(0xFF1F6FC4) to Color(0xFFE8D79A)
        5 -> Color(0xFF2A2140) to Color(0xFF5B4A6E)
        6 -> Color(0xFFBFE3FA) to Color(0xFFF4F9FF)
        7 -> Color(0xFFF7D58B) to Color(0xFFD9A55B)
        8 -> Color(0xFF8ED3FA) to Color(0xFFFFFFFF)
        9 -> Color(0xFF2E7D46) to Color(0xFF6B4A2B)
        else -> Smm.Beige to Smm.CreamDark
    }
    Box(modifier.clip(RoundedCornerShape(4.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(sky)
            val groundTop = h * 0.62f
            drawRect(ground, Offset(0f, groundTop), Size(w, h - groundTop))
            if (theme == 0 || theme == 9) drawRect(Color(0xFF45B03E), Offset(0f, groundTop), Size(w, h * 0.06f))
            if (theme == 0 || theme == 8) {
                // A little cloud.
                val c = Offset(w * 0.28f, h * 0.3f)
                val r = h * 0.09f
                drawCircle(Color.White, r, c)
                drawCircle(Color.White, r * 1.3f, c + Offset(r * 1.2f, -r * 0.3f))
                drawCircle(Color.White, r, c + Offset(r * 2.4f, 0f))
            }
            // Goal pole.
            drawRect(Color.White.copy(alpha = 0.85f), Offset(w * 0.82f, h * 0.22f), Size(w * 0.018f, groundTop - h * 0.22f))
            drawCircle(Color.White.copy(alpha = 0.85f), w * 0.022f, Offset(w * 0.829f, h * 0.2f))
        }
        if (date != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(date, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HeartStat(count: Int) = Stat(count) { Icon(Icons.Filled.Favorite, "Likes", tint = Smm.Maroon, modifier = Modifier.size(22.dp)) }

@Composable
fun PlaysStat(count: Int) = Stat(count) { FootprintsIcon(Modifier.size(22.dp)) }

@Composable
private fun Stat(count: Int, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text("%,d".format(count), fontSize = 19.sp, fontWeight = FontWeight.Black)
    }
}

/** The two footprints the game uses for play counts. */
@Composable
fun FootprintsIcon(modifier: Modifier = Modifier, color: Color = Smm.Maroon) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun foot(cx: Float, top: Float) {
            drawOval(color, Offset(cx - w * 0.15f, top), Size(w * 0.3f, h * 0.42f))
            drawOval(color, Offset(cx - w * 0.11f, top + h * 0.47f), Size(w * 0.22f, h * 0.2f))
        }
        foot(w * 0.3f, h * 0.24f)
        foot(w * 0.7f, h * 0.06f)
    }
}

/** A course tag with the game's little tag icon, or "---" for no tag. */
@Composable
fun TagLabel(tag: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 16.dp, height = 11.dp)) {
            val w = size.width
            val h = size.height
            drawPath(
                Path().apply {
                    moveTo(0f, h / 2); lineTo(w * 0.32f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w * 0.32f, h); close()
                },
                Smm.Maroon,
            )
            drawCircle(Color.White, h * 0.14f, Offset(w * 0.3f, h / 2))
        }
        Spacer(Modifier.width(4.dp))
        Text(tag ?: "---", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One of the cream info cells under an opened course ("World Record", "Clear Rate", "Course ID"). */
@Composable
fun RowScope.InfoCell(label: String, value: String, weight: Float = 1f) {
    Column(
        Modifier
            .weight(weight)
            .clip(RoundedCornerShape(8.dp))
            .background(Smm.Cream)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Smm.Maroon.copy(alpha = 0.75f), maxLines = 1)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** The cream description box of an opened course. */
@Composable
fun DescriptionBox(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Smm.Cream)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
fun SmallNote(text: String, color: Color = Smm.Maroon.copy(alpha = 0.75f)) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
