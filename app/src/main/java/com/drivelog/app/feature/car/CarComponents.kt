package com.drivelog.app.feature.car

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.drivelog.app.R
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// Touch targets never drop below this, however small the window gets.
private val MinTouch = 64.dp

@Composable fun fg() = MaterialTheme.colorScheme.onBackground
@Composable fun muted() = MaterialTheme.colorScheme.onSurfaceVariant
@Composable fun soft() = MaterialTheme.colorScheme.surfaceVariant
@Composable fun line() = MaterialTheme.colorScheme.outline

/** word-break: keep-all. Korean otherwise wraps between any two syllables, stranding a single character. */
fun keepWordsTogether(text: String): String = buildString(text.length * 2) {
    text.forEachIndexed { index, char ->
        append(char)
        val next = text.getOrNull(index + 1)
        if (next != null && char in '\uAC00'..'\uD7A3' && next in '\uAC00'..'\uD7A3') append('\u2060')
    }
}

@Composable
fun T(
    text: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = fg(), bold: Boolean = false,
    align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, display: Boolean = false, tracking: Float = 0f,
) = Text(
    keepWordsTogether(text), modifier, color = color, fontSize = size,
    // Display text is what the mockups lead with: the heaviest weight, set slightly tight.
    fontWeight = if (display) FontWeight.ExtraBold else if (bold) FontWeight.Bold else FontWeight.Normal,
    letterSpacing = (if (display) -0.02f else tracking).em,
    textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = size * (if (display) 1.18f else 1.28f),
)

/** Pleos Basic Button sizes: height and corner radius from the guide, the label and padding measured from its drawing. */
enum class ButtonSize(val height: Dp, val radius: Dp, val padding: Dp, val label: TextUnit, val icon: Dp) {
    LARGE(100.dp, 32.dp, 44.dp, 32.sp, 44.dp),
    MEDIUM(80.dp, 24.dp, 32.dp, 28.sp, 36.dp),
    SMALL(64.dp, 16.dp, 24.dp, 24.sp, 28.dp),
}

/** The emphasised action: Pleos's filled button. */
@Composable
fun PrimaryButton(
    text: String, tag: String, modifier: Modifier = Modifier, chevron: Boolean = false, enabled: Boolean = true, loading: Boolean = false,
    size: ButtonSize = ButtonSize.LARGE, icon: CarGlyph? = null, onClick: () -> Unit,
) = CarButton(text, tag, modifier, true, chevron, enabled, loading, size, icon, false, onClick)

/** Everything else: Pleos's basic button, a light tint of the ink. */
@Composable
fun SecondaryButton(
    text: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
    size: ButtonSize = ButtonSize.LARGE, icon: CarGlyph? = null, danger: Boolean = false, onClick: () -> Unit,
) = CarButton(text, tag, modifier, false, false, enabled, loading, size, icon, danger, onClick)

/**
 * The armed state of a two-tap action that cannot be undone. It disarms by itself, so a tap forgotten a while ago
 * cannot turn a later one into a deletion.
 */
@Composable
fun rememberConfirmState(key: Any? = Unit): MutableState<Boolean> {
    val armed = remember(key) { mutableStateOf(false) }
    LaunchedEffect(armed.value) {
        if (armed.value) {
            delay(CONFIRM_DISARM_MS)
            armed.value = false
        }
    }
    return armed
}

private const val CONFIRM_DISARM_MS = 5_000L
private val MinButtonLabel = 16.sp

@Composable
private fun CarButton(
    text: String, tag: String, modifier: Modifier, filled: Boolean, chevron: Boolean, enabled: Boolean, loading: Boolean,
    size: ButtonSize, icon: CarGlyph?, danger: Boolean, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onBackground
    val shape = RoundedCornerShape(size.radius)
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val inactive = !enabled || loading
    val filledPressed = if (colors.background.luminance() > 0.5f) Color(0xFF313236) else Color(0xFFC7C9CE)
    val fill = when {
        filled && inactive -> ink.copy(alpha = 0.05f)
        filled -> if (pressed) filledPressed else colors.primary
        inactive -> Color.Transparent
        else -> ink.copy(alpha = if (pressed) 0.1f else 0.05f)
    }
    val content = when {
        inactive -> ink.copy(alpha = 0.32f)
        filled -> colors.onPrimary
        danger -> colors.error
        else -> ink
    }
    Row(
        modifier.heightIn(min = size.height).clip(shape).background(fill)
            .then(if (!filled && inactive) Modifier.border(4.dp, ink.copy(alpha = 0.1f), shape) else Modifier)
            .pressClick(enabled = !inactive, source = press, indication = null, onClick = onClick).padding(horizontal = size.padding).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) LoadingIndicator(LoadingStyle.LINEAR, size.icon, ink)
        else {
            if (icon != null) {
                CarIcon(icon, size.icon, content)
                Spacer(Modifier.width(size.padding / 3))
            }
            // The label is a fixed size while the layout scales, so it shrinks to fit rather than being cut off.
            BasicText(
                keepWordsTogether(text), Modifier.weight(1f, fill = false),
                style = TextStyle(color = content, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(MinButtonLabel, size.label, 1.sp),
            )
            if (chevron) {
                Spacer(Modifier.width(size.padding / 3))
                CarIcon(CarGlyph.CHEVRON_RIGHT, size.icon, content)
            }
        }
    }
}

/** "‹ 여정" header. Holding the title opens SDK diagnostics, which is parked-only like everything else. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopBar(onBack: (() -> Unit)?, onTitleHold: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, title: String = "여정") {
    val d = LocalCarDimens.current
    Row(Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(44), MinTouch)), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(maxOf(d.u(44), MinTouch)).clip(RoundedCornerShape(16.dp))
                .then(if (onBack != null) Modifier.pressClick(onClick = onBack).semantics { contentDescription = "뒤로" }.testTag("back") else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (onBack != null) CarIcon(CarGlyph.CHEVRON_LEFT, 48.dp, fg()) }
        Box(Modifier.heightIn(min = MinTouch).combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = onTitleHold).padding(horizontal = d.u(4)).testTag("title"), contentAlignment = Alignment.Center) {
            T(title, d.t(18), bold = true)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Two columns in a wide window, one scrolling column in a narrow one. */
@Composable
fun Panes(leftWeight: Float, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit, rightFirstWhenStacked: Boolean = false) {
    val d = LocalCarDimens.current
    if (d.stacked) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(16))) {
            if (rightFirstWhenStacked) { right(); left() } else { left(); right() }
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.u(28))) {
            Column(Modifier.weight(leftWeight).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) { left() }
            Column(Modifier.weight(1f - leftWeight).fillMaxHeight()) { right() }
        }
    }
}

/** A visual that fills what remains in a wide pane, or takes a fixed slice of a scrolling column. */
@Composable
fun ColumnScope.paneFill(stackedHeight: Int = 300): Modifier {
    val d = LocalCarDimens.current
    return if (d.stacked) Modifier.fillMaxWidth().height(d.u(stackedHeight)) else Modifier.fillMaxWidth().weight(1f)
}

@DrawableRes
fun placeImage(name: String): Int = when {
    name.contains("광안리") -> R.drawable.place_gwangalli
    name.contains("송정") -> R.drawable.place_songjeong
    name.contains("다대포") -> R.drawable.place_dadaepo
    else -> R.drawable.demo_coastal_road
}

/** A photo beside a place must really be of that place, so anything else keeps the map. */
fun placeHasPhoto(name: String) = placeImage(name) != R.drawable.demo_coastal_road

/** A photo sharp enough to fill a whole poster. The other bundled ones are small and only suit a banner. */
@DrawableRes
fun posterImage(name: String): Int? = if (name.contains("광안리")) R.drawable.place_gwangalli_poster else null

@Composable
fun PlacePhoto(name: String, modifier: Modifier = Modifier, bitmap: ImageBitmap? = null) {
    val d = LocalCarDimens.current
    val shaped = modifier.clip(RoundedCornerShape(d.u(10))).background(soft())
    if (bitmap != null) Image(bitmap, name, shaped, contentScale = ContentScale.Crop)
    else Image(painterResource(placeImage(name)), name, shaped, contentScale = ContentScale.Crop)
}

/** The whole poster's picture: the driver's own photo, or a bundled one. */
@Composable
fun PosterPhoto(name: String, bitmap: ImageBitmap?, @DrawableRes bundled: Int?, modifier: Modifier = Modifier) {
    if (bitmap != null) Image(bitmap, name, modifier, contentScale = ContentScale.Crop)
    else if (bundled != null) Image(painterResource(bundled), name, modifier, contentScale = ContentScale.Crop)
}

@Composable
fun ProgressDots(total: Int, done: Int, modifier: Modifier = Modifier) {
    val d = LocalCarDimens.current
    val on = fg(); val off = muted(); val surface = MaterialTheme.colorScheme.background
    Canvas(modifier.fillMaxWidth().height(d.u(20))) {
        if (total < 2) return@Canvas
        val r = 7f * d.scale * density
        val y = size.height / 2
        val step = (size.width - r * 2) / (total - 1)
        drawLine(off, Offset(r, y), Offset(size.width - r, y), 1.5f * density)
        for (i in 0 until total) {
            val c = Offset(r + step * i, y)
            drawCircle(surface, r, c)
            if (i < done) drawCircle(on, r, c) else drawCircle(off, r, c, style = Stroke(1.5f * density))
        }
    }
}

@Composable
fun Stat(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    val d = LocalCarDimens.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            T(value, d.t(54), maxLines = 1, display = true)
            if (unit.isNotEmpty()) T(unit, d.t(28), Modifier.padding(start = d.u(6), bottom = d.u(6)), bold = true)
        }
        T(label, d.t(17), color = muted())
    }
}

@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(line()))
}

@Composable
fun SimulationBadge() {
    val d = LocalCarDimens.current
    // Outlined, because a filled shape on these screens is something to press.
    T("시뮬레이션 데이터", d.t(14), Modifier.border(1.dp, muted(), RoundedCornerShape(d.u(6))).padding(horizontal = d.u(10), vertical = d.u(4)).testTag("sim-badge"), bold = true)
}

enum class CarGlyph { CHEVRON_LEFT, CHEVRON_RIGHT, CHECK, PIN, INFO, CLOUD_OFF, REFRESH, DOCUMENT, PHOTO, SEARCH, CLOCK, BOOKMARK, LIST, MIC }

/**
 * System icons on the Pleos grid: drawn at 48 units and scaled, Outlined at a 3.6 unit stroke with round caps and
 * joins, Filled with 1.5 unit corners. The guide pairs icons with text labels, so an icon rarely stands alone.
 */
@Composable
fun CarIcon(glyph: CarGlyph, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.background
    Canvas(modifier.size(size)) {
        val u = this.size.width / 48f
        val stroke = Stroke(3.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        fun poly(vararg p: Pair<Float, Float>, closed: Boolean = false) = Path().apply {
            moveTo(p[0].first * u, p[0].second * u)
            p.drop(1).forEach { lineTo(it.first * u, it.second * u) }
            if (closed) close()
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, ink: Color = color) = drawLine(ink, at(x1, y1), at(x2, y2), 3.6f * u, StrokeCap.Round)
        when (glyph) {
            CarGlyph.CHEVRON_LEFT -> drawPath(poly(29f to 10f, 15f to 24f, 29f to 38f), color, style = stroke)
            CarGlyph.CHEVRON_RIGHT -> drawPath(poly(19f to 10f, 33f to 24f, 19f to 38f), color, style = stroke)
            CarGlyph.CHECK -> drawPath(poly(9f to 25f, 19.5f to 35.5f, 39f to 13f), color, style = stroke)
            CarGlyph.PIN -> drawPin(at(24f, 45f), 42f * u, color, surface)
            CarGlyph.INFO -> {
                drawCircle(color, 20f * u, at(24f, 24f))
                drawCircle(surface, 2.6f * u, at(24f, 15f))
                line(24f, 22.5f, 24f, 34f, surface)
            }
            CarGlyph.CLOUD_OFF -> {
                drawPath(Path().apply {
                    moveTo(14f * u, 38f * u)
                    cubicTo(4f * u, 38f * u, 3f * u, 23f * u, 13f * u, 21f * u)
                    cubicTo(15f * u, 9f * u, 33f * u, 8f * u, 36f * u, 20f * u)
                    cubicTo(46f * u, 20f * u, 47f * u, 38f * u, 35f * u, 38f * u)
                    close()
                }, color, style = stroke)
                line(8f, 6f, 40f, 42f)
            }
            CarGlyph.REFRESH -> {
                drawArc(color, 40f, 290f, false, at(7f, 7f), Size(34f * u, 34f * u), style = stroke)
                drawPath(poly(40f to 6f, 40f to 17f, 29f to 17f), color, style = stroke)
            }
            CarGlyph.DOCUMENT -> {
                drawPath(poly(11f to 5f, 29f to 5f, 38f to 14f, 38f to 43f, 11f to 43f, closed = true), color, style = stroke)
                line(18f, 27f, 31f, 27f)
                line(18f, 34f, 31f, 34f)
            }
            CarGlyph.SEARCH -> {
                drawCircle(color, 13.2f * u, at(21f, 21f), style = stroke)
                line(31f, 31f, 41f, 41f)
            }
            CarGlyph.CLOCK -> {
                drawCircle(color, 18.2f * u, at(24f, 24f), style = stroke)
                drawPath(poly(24f to 14f, 24f to 25f, 31f to 29.5f), color, style = stroke)
            }
            CarGlyph.BOOKMARK -> drawPath(poly(12f to 6f, 36f to 6f, 36f to 42f, 24f to 33f, 12f to 42f, closed = true), color, style = stroke)
            CarGlyph.LIST -> listOf(12f, 24f, 36f).forEach { y ->
                drawCircle(color, 2.2f * u, at(8f, y))
                line(16f, y, 42f, y)
            }
            CarGlyph.PHOTO -> {
                drawRoundRect(color, at(5f, 9f), Size(38f * u, 30f * u), CornerRadius(5f * u), style = stroke)
                drawCircle(color, 3.2f * u, at(16f, 19f))
                drawPath(poly(8f to 35f, 19f to 26f, 27f to 32f, 34f to 25f, 41f to 33f), color, style = stroke)
            }
            CarGlyph.MIC -> {
                drawRoundRect(color, at(17f, 5f), Size(14f * u, 24f * u), CornerRadius(7f * u), style = stroke)
                drawArc(color, 0f, 180f, false, at(10f, 9f), Size(28f * u, 28f * u), style = stroke)
                line(24f, 37f, 24f, 43f)
            }
        }
    }
}

fun DrawScope.drawPin(tip: Offset, h: Float, color: Color, hole: Color) {
    val r = h * 0.36f
    val cy = tip.y - h + r
    val shape = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - r * cos(Math.toRadians(25.0)).toFloat(), cy + r * sin(Math.toRadians(25.0)).toFloat())
        arcTo(androidx.compose.ui.geometry.Rect(tip.x - r, cy - r, tip.x + r, cy + r), 155f, 230f, false)
        close()
    }
    drawPath(shape, color)
    // Rounds the point to the guide's Filled corner: 1.5 of 48 units.
    drawPath(shape, color, style = Stroke(h * 3f / 48f, join = StrokeJoin.Round))
    drawCircle(hole, r * 0.42f, Offset(tip.x, cy))
}

/** Rounded phone outline used by the pairing and hand-off screens. */
@Composable
fun PhoneFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val d = LocalCarDimens.current
    Box(
        modifier.clip(RoundedCornerShape(d.u(22))).background(MaterialTheme.colorScheme.background)
            .border(d.u(3), fg(), RoundedCornerShape(d.u(22))).padding(d.u(7)),
    ) {
        Box(Modifier.align(Alignment.TopCenter).width(d.u(34)).height(d.u(8)).clip(CircleShape).background(fg()))
        Box(Modifier.fillMaxSize().padding(top = d.u(12))) { content() }
    }
}

/** A dashed, not solid, path: used wherever the app must not imply a measured route. */
fun dashedEffect(unit: Float) = PathEffect.dashPathEffect(floatArrayOf(unit * 2.5f, unit * 2.5f))
