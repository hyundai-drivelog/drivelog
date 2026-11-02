package com.drivelog.app.feature.car

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.drivelog.app.R
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
    align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE,
) = Text(
    keepWordsTogether(text), modifier, color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.28f,
)

@Composable
fun PrimaryButton(text: String, tag: String, modifier: Modifier = Modifier, chevron: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    CarButton(text, tag, modifier, true, chevron, enabled, onClick)

@Composable
fun SecondaryButton(text: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    CarButton(text, tag, modifier, false, false, enabled, onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarButton(text: String, tag: String, modifier: Modifier, primary: Boolean, chevron: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val shape = RoundedCornerShape(d.u(12))
    val colors = MaterialTheme.colorScheme
    val content = (if (primary) colors.onPrimary else colors.onBackground).copy(alpha = if (enabled || primary) 1f else 0.35f)
    Row(
        modifier.heightIn(min = maxOf(d.u(56), MinTouch)).clip(shape)
            .then(if (primary) Modifier.background(colors.primary.copy(alpha = if (enabled) 1f else 0.35f)) else Modifier.border(d.u(1.5f), colors.onBackground.copy(alpha = if (enabled) 1f else 0.35f), shape))
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = d.u(16)).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        T(text, d.t(21), color = content, bold = true, maxLines = 1)
        if (chevron) {
            Spacer(Modifier.width(d.u(10)))
            CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(16), content)
        }
    }
}

/** "‹ 여정" header. Holding the title opens SDK diagnostics, which is parked-only like everything else. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopBar(onBack: (() -> Unit)?, onTitleHold: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val d = LocalCarDimens.current
    Row(Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(44), MinTouch)), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(maxOf(d.u(44), MinTouch))
                .then(if (onBack != null) Modifier.combinedClickable(role = Role.Button, onClickLabel = "뒤로", onClick = onBack).semantics { contentDescription = "뒤로" }.testTag("back") else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (onBack != null) CarIcon(CarGlyph.CHEVRON_LEFT, d.u(20), fg()) }
        Box(Modifier.heightIn(min = MinTouch).combinedClickable(onClick = {}, onLongClick = onTitleHold).padding(horizontal = d.u(4)).testTag("title"), contentAlignment = Alignment.Center) {
            T("여정", d.t(18), bold = true)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Two columns in a wide window, one scrolling column in a narrow one. */
@Composable
fun Panes(leftWeight: Float, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    val d = LocalCarDimens.current
    if (d.stacked) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(16))) { left(); right() }
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

private val Mono = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun PlacePhoto(name: String, modifier: Modifier = Modifier, bitmap: ImageBitmap? = null) {
    val d = LocalCarDimens.current
    val shaped = modifier.clip(RoundedCornerShape(d.u(10))).background(soft())
    if (bitmap != null) Image(bitmap, name, shaped, contentScale = ContentScale.Crop)
    else Image(painterResource(placeImage(name)), name, shaped, contentScale = ContentScale.Crop, colorFilter = Mono)
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
            T(value, d.t(44), bold = true, maxLines = 1)
            if (unit.isNotEmpty()) T(unit, d.t(28), Modifier.padding(start = d.u(5), bottom = d.u(3)), bold = true)
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
    T("시뮬레이션 데이터", d.t(14), Modifier.clip(RoundedCornerShape(d.u(6))).background(soft()).padding(horizontal = d.u(10), vertical = d.u(4)).testTag("sim-badge"), bold = true)
}

enum class CarGlyph { CHEVRON_LEFT, CHEVRON_RIGHT, CHECK, PIN, INFO, CLOUD_OFF, REFRESH, DOCUMENT, PHOTO }

@Composable
fun CarIcon(glyph: CarGlyph, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.background
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun poly(vararg p: Pair<Float, Float>) = Path().apply {
            moveTo(p[0].first * w, p[0].second * w)
            p.drop(1).forEach { lineTo(it.first * w, it.second * w) }
        }
        when (glyph) {
            CarGlyph.CHEVRON_LEFT -> drawPath(poly(.65f to .1f, .25f to .5f, .65f to .9f), color, style = stroke)
            CarGlyph.CHEVRON_RIGHT -> drawPath(poly(.35f to .1f, .75f to .5f, .35f to .9f), color, style = stroke)
            CarGlyph.CHECK -> drawPath(poly(.2f to .52f, .42f to .74f, .82f to .28f), color, style = stroke)
            CarGlyph.PIN -> drawPin(Offset(w / 2, w), w, color, surface)
            CarGlyph.INFO -> {
                drawCircle(color, w / 2)
                drawCircle(surface, w * .06f, Offset(w / 2, w * .28f))
                drawLine(surface, Offset(w / 2, w * .44f), Offset(w / 2, w * .76f), w * .1f, StrokeCap.Round)
            }
            CarGlyph.CLOUD_OFF -> {
                drawPath(Path().apply {
                    moveTo(w * .26f, w * .76f)
                    cubicTo(w * .02f, w * .76f, w * .02f, w * .42f, w * .26f, w * .42f)
                    cubicTo(w * .3f, w * .14f, w * .68f, w * .14f, w * .72f, w * .4f)
                    cubicTo(w * 1f, w * .4f, w * 1f, w * .76f, w * .74f, w * .76f)
                    close()
                }, color, style = Stroke(w * .05f, join = StrokeJoin.Round))
                drawLine(color, Offset(w * .14f, w * .1f), Offset(w * .86f, w * .9f), w * .05f, StrokeCap.Round)
            }
            CarGlyph.REFRESH -> {
                drawArc(color, 40f, 290f, false, Offset(w * .12f, w * .12f), Size(w * .76f, w * .76f), style = stroke)
                drawPath(poly(.86f to .1f, .86f to .36f, .6f to .36f), color, style = stroke)
            }
            CarGlyph.DOCUMENT -> {
                drawPath(poly(.2f to .06f, .62f to .06f, .82f to .28f, .82f to .94f, .2f to .94f, .2f to .06f), color, style = Stroke(w * .08f, join = StrokeJoin.Round))
                drawLine(color, Offset(w * .34f, w * .55f), Offset(w * .68f, w * .55f), w * .07f)
                drawLine(color, Offset(w * .34f, w * .73f), Offset(w * .68f, w * .73f), w * .07f)
            }
            CarGlyph.PHOTO -> {
                drawRoundRect(color, Offset(w * .08f, w * .16f), Size(w * .84f, w * .68f), style = Stroke(w * .08f))
                drawCircle(color, w * .08f, Offset(w * .32f, w * .36f))
                drawPath(poly(.14f to .76f, .42f to .5f, .58f to .64f, .7f to .52f, .88f to .72f), color, style = Stroke(w * .08f, join = StrokeJoin.Round))
            }
        }
    }
}

fun DrawScope.drawPin(tip: Offset, h: Float, color: Color, hole: Color) {
    val r = h * 0.36f
    val cy = tip.y - h + r
    drawPath(Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - r * cos(Math.toRadians(25.0)).toFloat(), cy + r * sin(Math.toRadians(25.0)).toFloat())
        arcTo(androidx.compose.ui.geometry.Rect(tip.x - r, cy - r, tip.x + r, cy + r), 155f, 230f, false)
        close()
    }, color)
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
