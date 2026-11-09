package com.drivelog.app.feature.car

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.drivelog.app.core.recording.haversineKm
import kotlin.math.cos
import kotlin.math.max

val DriveCardBackground = Color(0xFF131417)
val DriveCardPressed = Color(0xFF313236)
val DriveCardText = Color.White
val DriveCardMuted = Color(0xFFB4B6BA)

private const val GAP_MS = 60_000L
private const val MAX_DRAWN_POINTS = 240

/** Evenly thins a long drive for drawing, always keeping both ends. Storage keeps every point. */
fun <T> List<T>.thinnedForDrawing(limit: Int = MAX_DRAWN_POINTS): List<T> {
    if (size <= limit) return this
    val step = (size - 1).toDouble() / (limit - 1)
    return List(limit) { this[(it * step).toInt().coerceAtMost(lastIndex)] }
}

/** A recording gap is not driving, so a drive is split at each gap and only the pieces are drawn solid. */
fun splitAtGaps(points: List<MapPoint>, gapMillis: Long = GAP_MS): List<List<MapPoint>> {
    val pieces = mutableListOf<MutableList<MapPoint>>()
    points.forEach { point ->
        val current = pieces.lastOrNull()
        if (current == null || point.atMillis - current.last().atMillis > gapMillis) pieces.add(mutableListOf(point))
        else current.add(point)
    }
    return pieces
}

/**
 * A pin far beyond where the drive ended would shrink the route to a speck, so the destination joins the
 * frame only when it is about as close as the drive was long.
 */
fun destinationFitsFrame(points: List<MapPoint>, destination: MapPoint?): Boolean {
    if (destination == null) return false
    val last = points.lastOrNull() ?: return true
    val driven = points.zipWithNext().sumOf { (a, b) -> haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) }
    return haversineKm(last.latitude, last.longitude, destination.latitude, destination.longitude) <= max(1.0, driven)
}

/** Chaikin corner cutting: rounds GPS corners for display only, and keeps each piece's real start and end. */
fun smooth(line: List<Offset>, passes: Int = 2): List<Offset> {
    var current = line
    repeat(passes) {
        if (current.size < 3) return current
        val next = ArrayList<Offset>(current.size * 2)
        next.add(current.first())
        current.zipWithNext().forEach { (a, b) ->
            next.add(a * 0.75f + b * 0.25f)
            next.add(a * 0.25f + b * 0.75f)
        }
        next.add(current.last())
        current = next
    }
    return current
}

private fun pathOf(line: List<Offset>) = Path().apply {
    line.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
}

/** The first [fraction] of the lines' combined length, in order. The jumps between lines take no time. */
fun trimLines(lines: List<List<Offset>>, fraction: Float): List<List<Offset>> {
    if (fraction >= 1f) return lines
    val total = lines.sumOf { line -> line.zipWithNext().sumOf { (a, b) -> (b - a).getDistance().toDouble() } }
    var left = total * fraction.coerceAtLeast(0f)
    val kept = ArrayList<List<Offset>>(lines.size)
    lines.filter { it.isNotEmpty() }.forEach { line ->
        val part = arrayListOf(line.first())
        line.zipWithNext().forEach { (a, b) ->
            val length = (b - a).getDistance().toDouble()
            if (left < length) {
                part.add(lerp(a, b, (left / length).toFloat()))
                kept.add(part)
                return kept
            }
            part.add(b)
            left -= length
        }
        kept.add(part)
    }
    return kept
}

/**
 * The recorded GPS track, never the navigation app's planned line, which the SDK does not expose. The dot
 * grid is plainly not a map, so nothing here can be mistaken for geography the app does not have.
 * With [drawRoute] the track traces itself from start to end and the destination pin lands last; a new [replay]
 * value traces it again, more slowly, as a replay of the drive. Over a [map] the track sits where it was driven.
 */
@Composable
fun DriveRouteArt(
    points: List<MapPoint>, destination: MapPoint?, destinationLabel: String?, modifier: Modifier = Modifier, drawRoute: Boolean = false,
    map: MapFrame? = null, replay: Int = 0,
) {
    val d = LocalCarDimens.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = DriveCardText, fontSize = d.t(15), fontWeight = FontWeight.Bold)
    val route = remember(points) { styledRoute(points) }
    val pin = remember(points, destination) { destination.takeIf { destinationFitsFrame(points, it) } }
    val trace = rememberProgress(points to replay, tween(if (replay > 0) REPLAY_MS else TRACE_MS, easing = FastOutSlowInEasing), enabled = drawRoute)
    Box(modifier.clip(RoundedCornerShape(d.u(10))).semantics { contentDescription = if (map != null) "주행 경로 지도" else "주행 경로" }) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(DriveCardBackground)
            if (map != null) {
                val (scale, offset) = cropOf(map, size.width, size.height)
                drawImage(
                    map.image, dstOffset = IntOffset(offset.x.toInt(), offset.y.toInt()),
                    dstSize = IntSize((map.image.width * scale).toInt(), (map.image.height * scale).toInt()),
                )
                return@Canvas
            }
            // A soft light under the route, and dots that thin out towards the edges: no box, and plainly not a map.
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), center, size.maxDimension * 0.6f),
                size.maxDimension * 0.6f, center,
            )
            val spacing = 22f * d.scale * density
            val reach = kotlin.math.hypot(size.width, size.height) / 2f
            var y = spacing / 2
            while (y < size.height) {
                var x = spacing / 2
                while (x < size.width) {
                    val fade = 1f - (Offset(x, y) - center).getDistance() / reach
                    if (fade > 0.07f) drawCircle(Color.White.copy(alpha = 0.14f * fade), 1.2f * density, Offset(x, y))
                    x += spacing
                }
                y += spacing
            }
        }
        // Its own layer: tracing the route must not redraw the map or the dot grid on every frame.
        Canvas(Modifier.matchParentSize().graphicsLayer {}) {
            val all = route.pieces.flatten() + listOfNotNull(pin)
            if (all.isEmpty()) return@Canvas
            val traced = trace.value
            // A fine line, scaled to the frame so a small card is not crossed by a bar: about 0.6% of its shorter
            // side, kept between 2.2 and 3.4 units so it never vanishes nor turns heavy on a large one.
            val width = (minOf(size.width, size.height) * 0.006f).coerceIn(2.2f * d.scale * density, 3.4f * d.scale * density)
            val project: (MapPoint) -> Offset = if (map != null) {
                val (scale, offset) = cropOf(map, size.width, size.height)
                ({ point -> map.project(point) * scale + offset })
            } else fitToFrame(all, size.width, size.height, 46f * d.scale * density)
            val lines = trimLines(route.pieces.map { piece -> roundedRoute(piece.map(project)) }, traced)
            lines.zipWithNext().forEach { (before, after) ->
                drawLine(DriveCardMuted, before.last(), after.first(), width * 0.7f, StrokeCap.Round, dashedEffect(width * 1.4f))
            }
            // As the mockups draw it over the map: one clean white line, no glow and no marks along it. One colour,
            // whatever the speed. The km splits are listed beside the card instead.
            // Over a real map a faint dark casing keeps the thin line apart from the white roads under it. Still one
            // white line: the casing is a shadow, not a second colour.
            if (map != null) lines.forEach { line ->
                drawPath(pathOf(line), Color.Black.copy(alpha = 0.4f), style = Stroke(width * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            lines.forEach { line ->
                drawPath(pathOf(line), DriveCardText, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            lines.firstOrNull()?.firstOrNull()?.let { start ->
                drawCircle(DriveCardBackground, width * 2.1f, start)
                drawCircle(DriveCardText, width * 2.1f, start, style = Stroke(width * 0.9f))
            }
            val landing = ((traced - PIN_LANDS_AT) / (1f - PIN_LANDS_AT)).coerceIn(0f, 1f)
            if (pin != null && landing > 0f) {
                val tip = project(pin)
                drawPin(tip, 30f * d.scale * density * landing, DriveCardText, DriveCardBackground)
                if (destinationLabel != null) {
                    val layout = measurer.measure(destinationLabel, labelStyle, maxLines = 1)
                    val gap = 16f * d.scale * density
                    val right = tip.x + gap
                    val x = if (right + layout.size.width <= size.width - 4f) right else (tip.x - gap - layout.size.width).coerceAtLeast(4f)
                    drawText(layout, topLeft = Offset(x, (tip.y - 34f * d.scale * density).coerceAtLeast(4f)), alpha = landing)
                }
            }
            // The car's arrow, as the navigation mockups show it: moving along while tracing, and where the drive
            // stopped when no pin marks it.
            if (pin == null || traced < 1f) lines.lastOrNull()?.let { line ->
                val end = line.last()
                // Heading from a little way back, so a last jittery fix does not turn the arrow.
                val from = line.asReversed().firstOrNull { (it - end).getDistance() >= width * 5f } ?: lines.first().first()
                drawArrival(end, from, 10f * d.scale * density)
            }
        }
        // The map's data is credited in its own corner, small, only when a real map is shown.
        if (map != null) MapCornerCredit(Modifier.align(Alignment.BottomEnd))
    }
}

/** A white disc with a dark arrowhead pointing the way the car went. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrival(at: Offset, from: Offset, radius: Float) {
    val heading = Math.toDegrees(kotlin.math.atan2((at.y - from.y).toDouble(), (at.x - from.x).toDouble())).toFloat() + 90f
    drawCircle(Color.Black.copy(alpha = 0.35f), radius * 1.18f, at)
    drawCircle(DriveCardText, radius, at)
    rotate(heading, at) {
        val r = radius * 0.55f
        val arrow = Path().apply {
            moveTo(at.x, at.y - r)
            lineTo(at.x + r * 0.78f, at.y + r * 0.85f)
            lineTo(at.x, at.y + r * 0.42f)
            lineTo(at.x - r * 0.78f, at.y + r * 0.85f)
            close()
        }
        drawPath(arrow, DriveCardBackground)
    }
}

private const val PIN_LANDS_AT = 0.85f
private const val TRACE_MS = 1200
private const val REPLAY_MS = 3200

/** How an image fills a frame of another shape while keeping its proportions, as a centred crop does. */
private fun cropOf(map: MapFrame, width: Float, height: Float): Pair<Float, Offset> {
    val scale = maxOf(width / map.image.width, height / map.image.height)
    return scale to Offset((width - map.image.width * scale) / 2, (height - map.image.height * scale) / 2)
}

private fun fitToFrame(points: List<MapPoint>, width: Float, height: Float, pad: Float): (MapPoint) -> Offset {
    val k = cos(Math.toRadians(points.map { it.latitude }.average()))
    val xs = points.map { it.longitude * k }
    val ys = points.map { -it.latitude }
    val minX = xs.min(); val minY = ys.min()
    val spanX = (xs.max() - minX).coerceAtLeast(1e-6)
    val spanY = (ys.max() - minY).coerceAtLeast(1e-6)
    val scale = minOf((width - pad * 2) / spanX, (height - pad * 2) / spanY)
    val offsetX = (width - spanX * scale) / 2
    val offsetY = (height - spanY * scale) / 2
    return { p -> Offset((offsetX + (p.longitude * k - minX) * scale).toFloat(), (offsetY + (-p.latitude - minY) * scale).toFloat()) }
}
