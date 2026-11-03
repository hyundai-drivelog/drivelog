package com.drivelog.app.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.drivelog.app.core.recording.haversineKm
import kotlin.math.cos
import kotlin.math.max

val DriveCardBackground = Color(0xFF111214)
val DriveCardText = Color.White
val DriveCardMuted = Color(0xFFB4B6BA)
private val GridDot = Color.White.copy(alpha = 0.09f)
private val RouteGlow = Color.White.copy(alpha = 0.16f)

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

/**
 * The recorded GPS track, never the navigation app's planned line, which the SDK does not expose. The dot
 * grid is plainly not a map, so nothing here can be mistaken for geography the app does not have.
 */
@Composable
fun DriveRouteArt(points: List<MapPoint>, destination: MapPoint?, destinationLabel: String?, modifier: Modifier = Modifier) {
    val d = LocalCarDimens.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = DriveCardText, fontSize = d.t(15), fontWeight = FontWeight.Bold)
    val pieces = remember(points) { splitAtGaps(points.thinnedForDrawing()) }
    val pin = remember(points, destination) { destination.takeIf { destinationFitsFrame(points, it) } }
    Box(modifier.clip(RoundedCornerShape(d.u(10))).semantics { contentDescription = "주행 경로" }) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(DriveCardBackground)
            val spacing = 22f * d.scale * density
            var y = spacing / 2
            while (y < size.height) {
                var x = spacing / 2
                while (x < size.width) { drawCircle(GridDot, 1.2f * density, Offset(x, y)); x += spacing }
                y += spacing
            }
            val all = pieces.flatten() + listOfNotNull(pin)
            if (all.isEmpty()) return@Canvas
            val width = 4f * d.scale * density
            val project = fitToFrame(all, size.width, size.height, 46f * d.scale * density)
            val lines = pieces.map { piece -> piece.map(project) }
            lines.zipWithNext().forEach { (before, after) ->
                drawLine(DriveCardMuted, before.last(), after.first(), width * 0.6f, StrokeCap.Round, dashedEffect(width))
            }
            lines.forEach { line ->
                val path = pathOf(smooth(line))
                drawPath(path, RouteGlow, style = Stroke(width * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, DriveCardText, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            lines.firstOrNull()?.firstOrNull()?.let { start ->
                drawCircle(DriveCardBackground, width * 2.4f, start)
                drawCircle(DriveCardText, width * 2.4f, start, style = Stroke(width * 0.8f))
                drawCircle(DriveCardText, width * 0.9f, start)
            }
            if (pin != null) {
                val tip = project(pin)
                drawPin(tip, 38f * d.scale * density, DriveCardText, DriveCardBackground)
                if (destinationLabel != null) {
                    val layout = measurer.measure(destinationLabel, labelStyle, maxLines = 1)
                    val gap = 16f * d.scale * density
                    val right = tip.x + gap
                    val x = if (right + layout.size.width <= size.width - 4f) right else (tip.x - gap - layout.size.width).coerceAtLeast(4f)
                    drawText(layout, topLeft = Offset(x, (tip.y - 34f * d.scale * density).coerceAtLeast(4f)))
                }
            } else lines.lastOrNull()?.lastOrNull()?.let { end ->
                drawCircle(DriveCardText, width * 1.8f, end)
            }
        }
    }
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
