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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.MaterialTheme
import kotlin.math.cos
import kotlin.random.Random

data class MapPoint(val latitude: Double, val longitude: Double, val atMillis: Long = 0)

fun List<List<Double>>.toMapPoints() = map { MapPoint(it[0], it[1], it.getOrNull(2)?.toLong() ?: 0) }

/**
 * Real recorded coordinates over a decorative backdrop. The PRD leaves the map provider undecided, so the
 * streets behind the line are generated art and carry no geographic meaning; only the line and pins are data.
 * A gap in recording is drawn dashed, and an unfinished end is a dashed link to the destination labelled as
 * having no location. Neither is ever drawn as if it had been driven.
 */
@Composable
fun RouteMap(
    points: List<MapPoint>, destination: MapPoint?, destinationLabel: String?, modifier: Modifier = Modifier,
    unfinished: Boolean = false, seed: Int = 7,
) {
    val d = LocalCarDimens.current
    val colors = MaterialTheme.colorScheme
    val ink = colors.onBackground
    val base = colors.surfaceVariant
    val road = colors.background
    val water = colors.outline.copy(alpha = 0.55f)
    val surface = colors.background
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = ink, fontSize = d.t(14), fontWeight = FontWeight.Bold)
    val roads = remember(seed) { generateRoads(seed) }
    Box(modifier.clip(RoundedCornerShape(d.u(14))).semantics { contentDescription = "주행 경로 지도" }) {
        Canvas(Modifier.matchParentSize()) {
            drawBackdrop(base, water, road, roads)
            val all = points + listOfNotNull(destination)
            if (all.isEmpty()) return@Canvas
            val pad = 44f * d.scale * density
            val project = projector(all, size.width, size.height, pad)
            val routeWidth = 4.5f * d.scale * density
            val style = Stroke(routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val dashed = Stroke(routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashedEffect(routeWidth))
            val solid = Path()
            val gaps = Path()
            points.forEachIndexed { index, point ->
                val at = project(point)
                if (index == 0) solid.moveTo(at.x, at.y)
                else {
                    val previous = points[index - 1]
                    if (point.atMillis - previous.atMillis > GAP_MS) {
                        val from = project(previous)
                        gaps.moveTo(from.x, from.y); gaps.lineTo(at.x, at.y)
                        solid.moveTo(at.x, at.y)
                    } else solid.lineTo(at.x, at.y)
                }
            }
            drawPath(solid, ink, style = style)
            drawPath(gaps, ink, style = dashed)
            val last = points.lastOrNull()
            if (unfinished && last != null && destination != null) {
                val from = project(last)
                val to = project(destination)
                drawLine(ink, from, to, routeWidth, StrokeCap.Round, dashedEffect(routeWidth))
                val mid = Offset((from.x + to.x) / 2, (from.y + to.y) / 2)
                drawCircle(surface, routeWidth * 1.4f, from)
                drawCircle(ink, routeWidth * 1.4f, from, style = Stroke(routeWidth * 0.5f))
                val layout = measurer.measure("위치 정보 없음", labelStyle)
                drawText(layout, topLeft = Offset((mid.x - layout.size.width).coerceAtLeast(pad / 4), (mid.y - layout.size.height * 1.8f).coerceAtLeast(0f)))
            }
            points.firstOrNull()?.let { first ->
                val at = project(first)
                drawCircle(ink, routeWidth * 2f, at)
                drawCircle(surface, routeWidth * 0.9f, at)
            }
            if (destination != null) {
                val tip = project(destination)
                drawPin(tip, 40f * d.scale * density, ink, surface)
                if (destinationLabel != null) {
                    val layout = measurer.measure(destinationLabel, labelStyle)
                    val x = (tip.x + 18f * d.scale * density).coerceAtMost(size.width - layout.size.width - 6f)
                    val inside = if (x < tip.x) tip.y + 6f else tip.y - 38f * d.scale * density
                    drawText(layout, topLeft = Offset(x, inside.coerceAtLeast(2f)))
                }
            } else if (last != null) {
                val at = project(last)
                drawCircle(ink, routeWidth * 2f, at)
            }
        }
    }
}

private const val GAP_MS = 60_000L

private fun projector(points: List<MapPoint>, width: Float, height: Float, pad: Float): (MapPoint) -> Offset {
    val k = cos(Math.toRadians(points.map { it.latitude }.average()))
    val xs = points.map { it.longitude * k }
    val ys = points.map { -it.latitude }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val spanX = (maxX - minX).coerceAtLeast(1e-6)
    val spanY = (maxY - minY).coerceAtLeast(1e-6)
    val scale = minOf((width - pad * 2) / spanX, (height - pad * 2) / spanY)
    val ox = (width - spanX * scale) / 2
    val oy = (height - spanY * scale) / 2
    return { p -> Offset((ox + (p.longitude * k - minX) * scale).toFloat(), (oy + (-p.latitude - minY) * scale).toFloat()) }
}

private class Road(val points: List<Offset>, val major: Boolean)

private fun generateRoads(seed: Int): List<Road> {
    val random = Random(seed)
    return List(26) { index ->
        val horizontal = index % 2 == 0
        val position = random.nextFloat()
        val steps = 5
        Road(List(steps + 1) { step ->
            val along = step.toFloat() / steps
            val wobble = (random.nextFloat() - 0.5f) * 0.05f
            if (horizontal) Offset(along, position + wobble) else Offset(position + wobble, along)
        }, major = index % 7 == 0)
    }
}

private fun DrawScope.drawBackdrop(base: Color, water: Color, road: Color, roads: List<Road>) {
    drawRect(base)
    val w = size.width
    val h = size.height
    roads.forEach { item ->
        val path = Path().apply {
            item.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * w, p.y * h) else lineTo(p.x * w, p.y * h) }
        }
        drawPath(path, road, style = Stroke((if (item.major) 3.5f else 1.6f) * density, join = StrokeJoin.Round))
    }
    drawPath(Path().apply {
        moveTo(w, h * 0.12f)
        cubicTo(w * 0.86f, h * 0.3f, w * 0.72f, h * 0.42f, w * 0.56f, h * 0.62f)
        cubicTo(w * 0.42f, h * 0.76f, w * 0.26f, h * 0.84f, w * 0.1f, h)
        lineTo(w, h)
        close()
    }, water)
}
