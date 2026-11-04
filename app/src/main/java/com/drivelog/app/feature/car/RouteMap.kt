package com.drivelog.app.feature.car

import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.MaterialTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.withTimeoutOrNull

data class MapPoint(val latitude: Double, val longitude: Double, val atMillis: Long = 0)

fun List<List<Double>>.toMapPoints() = map { MapPoint(it[0], it[1], it.getOrNull(2)?.toLong() ?: 0) }

/** What lies behind the route: generated art, nothing yet while a real map loads, or the real map. */
sealed interface MapBackdrop {
    data object Art : MapBackdrop
    data object Loading : MapBackdrop
    class Real(val frame: MapFrame) : MapBackdrop
}

private const val LIVE_MAP_TIMEOUT_MS = 15_000L

/**
 * The route over a real map where one can be fetched, falling back to the labelled art. Only the screens that
 * render while parked use this, and leaving them cancels the request, so nothing is asked for while moving.
 */
@Composable
fun LiveRouteMap(
    cache: RouteMapCache, points: List<MapPoint>, destination: MapPoint?, destinationLabel: String?, modifier: Modifier = Modifier,
    unfinished: Boolean = false,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var size by remember { mutableStateOf(IntSize.Zero) }
    // The frame is framed once: a parked car's position jitter must not fetch the map again.
    val framed = remember { points + listOfNotNull(destination) }
    var backdrop by remember { mutableStateOf<MapBackdrop>(if (framed.isEmpty()) MapBackdrop.Art else MapBackdrop.Loading) }
    LaunchedEffect(size, dark) {
        if (framed.isEmpty() || size == IntSize.Zero) return@LaunchedEffect
        backdrop = MapBackdrop.Loading
        val frame = withTimeoutOrNull(LIVE_MAP_TIMEOUT_MS) { cache.liveFrame(framed, size.width, size.height, dark) }
        backdrop = if (frame != null) MapBackdrop.Real(frame) else MapBackdrop.Art
    }
    RouteMap(points, destination, destinationLabel, modifier.onSizeChanged { size = it }, unfinished, backdrop = backdrop)
}

/**
 * Real recorded coordinates over a real map or a decorative backdrop, whose streets are generated art with no
 * geographic meaning; only the line and pins are data then, and the map says so.
 * A gap in recording is drawn dashed, and an unfinished end is a dashed link to the destination labelled as
 * having no location. Neither is ever drawn as if it had been driven.
 */
@Composable
fun RouteMap(
    points: List<MapPoint>, destination: MapPoint?, destinationLabel: String?, modifier: Modifier = Modifier,
    unfinished: Boolean = false, seed: Int = 7, startLabel: String? = null, backdrop: MapBackdrop = MapBackdrop.Art,
) {
    val d = LocalCarDimens.current
    val colors = MaterialTheme.colorScheme
    val ink = colors.onBackground
    val surface = colors.background
    val palette = if (surface.luminance() < 0.5f) NightMap else DayMap
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = ink, fontSize = d.t(14), fontWeight = FontWeight.Bold)
    val art = remember(seed) { generateBackdrop(seed) }
    val pinDrop = rememberProgress(destination, spring(dampingRatio = 0.55f, stiffness = 320f))
    Box(modifier.clip(RoundedCornerShape(d.u(14))).semantics { contentDescription = "주행 경로 지도" }) {
        Canvas(Modifier.matchParentSize()) {
            val real = (backdrop as? MapBackdrop.Real)?.frame
            when {
                real != null -> drawImage(real.image)
                backdrop == MapBackdrop.Loading -> {
                    // The route waits too: it would jump when the map's own projection replaces this one.
                    drawRect(palette.land)
                    return@Canvas
                }
                else -> drawBackdrop(palette, art, d.scale)
            }
            val all = points + listOfNotNull(destination)
            if (all.isEmpty()) return@Canvas
            val pad = 44f * d.scale * density
            val project = real?.project ?: projector(all, size.width, size.height, pad)
            val routeWidth = (if (real != null) 6f else 4.5f) * d.scale * density
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
            // On a real night map the line glows, as on the drive cards. Stacked strokes, as a blur needs a software canvas.
            if (real != null && surface.luminance() < 0.5f) {
                drawPath(solid, ink.copy(alpha = 0.10f), style = Stroke(routeWidth * 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(solid, ink.copy(alpha = 0.18f), style = Stroke(routeWidth * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // A surface-coloured edge keeps the line legible where it crosses roads of the backdrop.
            else drawPath(solid, surface.copy(alpha = 0.75f), style = Stroke(routeWidth * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
                if (startLabel != null) {
                    val layout = measurer.measure(startLabel, labelStyle)
                    drawText(layout, topLeft = Offset((at.x - layout.size.width / 2).coerceIn(2f, (size.width - layout.size.width - 2f).coerceAtLeast(2f)), (at.y - layout.size.height - routeWidth * 3f).coerceAtLeast(2f)))
                }
            }
            if (destination != null) {
                val tip = project(destination)
                val dropped = pinDrop.value
                val opacity = dropped.coerceIn(0f, 1f)
                val pinHeight = 40f * d.scale * density
                drawPin(tip.copy(y = tip.y - (1f - dropped) * pinHeight), pinHeight, ink.copy(alpha = opacity), surface.copy(alpha = opacity))
                if (destinationLabel != null) {
                    val layout = measurer.measure(destinationLabel, labelStyle)
                    // Beside the pin, on whichever side has room, so the pin never covers the name.
                    val gap = 18f * d.scale * density
                    val right = tip.x + gap
                    val x = if (right + layout.size.width <= size.width - 6f) right else (tip.x - gap - layout.size.width).coerceAtLeast(6f)
                    drawText(layout, topLeft = Offset(x, (tip.y - pinHeight * 0.95f).coerceAtLeast(2f)), alpha = opacity)
                }
            } else if (last != null) {
                val at = project(last)
                drawCircle(ink, routeWidth * 2f, at)
            }
        }
        // Art looks like geography but is not, so it says so. A real map credits its data instead.
        if (backdrop != MapBackdrop.Loading) T(
            if (backdrop is MapBackdrop.Real) ATTRIBUTION else "지도 배경은 예시예요", d.t(14),
            Modifier.align(Alignment.BottomStart).padding(d.u(10)).clip(RoundedCornerShape(d.u(6))).background(surface)
                .padding(horizontal = d.u(8), vertical = d.u(3)).testTag("map-note"),
            color = colors.onSurfaceVariant,
        )
    }
}

private const val GAP_MS = 60_000L

private fun projector(points: List<MapPoint>, width: Float, height: Float, pad: Float): (MapPoint) -> Offset {
    val k = cos(Math.toRadians(points.map { it.latitude }.average()))
    val xs = points.map { it.longitude * k }
    val ys = points.map { -it.latitude }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    // One position has no extent to fit, and would otherwise land on the frame's top edge.
    if (maxX - minX < 1e-6 && maxY - minY < 1e-6) return { Offset(width / 2, height / 2) }
    val spanX = (maxX - minX).coerceAtLeast(1e-6)
    val spanY = (maxY - minY).coerceAtLeast(1e-6)
    val scale = minOf((width - pad * 2) / spanX, (height - pad * 2) / spanY)
    val ox = (width - spanX * scale) / 2
    val oy = (height - spanY * scale) / 2
    return { p -> Offset((ox + (p.longitude * k - minX) * scale).toFloat(), (oy + (-p.latitude - minY) * scale).toFloat()) }
}

private class MapPalette(val land: Color, val water: Color, val park: Color, val road: Color)

private val DayMap = MapPalette(Color(0xFFEFEFF0), Color(0xFFCFD0D3), Color(0xFFE1E2E4), Color.White)
private val NightMap = MapPalette(Color(0xFF1C1C1F), Color(0xFF0A0A0B), Color(0xFF242427), Color(0xFF3A3A3F))

private class Road(val points: List<Offset>, val width: Float)
private class Backdrop(val coast: List<Offset>, val parks: List<List<Offset>>, val roads: List<Road>)

/** All in 0..1 of the frame, so one drawing fits any window. Smoothing is what makes straight steps read as streets. */
private fun generateBackdrop(seed: Int): Backdrop {
    val random = Random(seed)
    fun jitter(amount: Float) = (random.nextFloat() - 0.5f) * amount
    // Runs from the top right to the bottom left, with water below it.
    val coast = smooth(List(12) { step ->
        val along = step / 11f
        Offset(1.04f - along * 0.72f + jitter(0.03f), 0.42f + along * 0.64f + jitter(0.06f))
    }, passes = 3)
    val parks = List(4) {
        val center = Offset(0.08f + random.nextFloat() * 0.5f, 0.06f + random.nextFloat() * 0.42f)
        val radius = 0.05f + random.nextFloat() * 0.05f
        smooth(List(8) { corner ->
            val angle = corner / 8.0 * Math.PI * 2
            val reach = radius * (0.7f + random.nextFloat() * 0.6f)
            center + Offset((cos(angle) * reach).toFloat(), (sin(angle) * reach * 1.5).toFloat())
        }.let { it + it.first() }, passes = 3)
    }
    fun wandering(from: Offset, to: Offset, width: Float, wander: Float) = Road(
        smooth(List(7) { step ->
            val along = step / 6f
            from + (to - from) * along + Offset(jitter(wander), jitter(wander))
        }, passes = 3),
        width,
    )
    val arterials = List(5) { index ->
        val y = 0.1f + index * 0.2f
        wandering(Offset(-0.02f, y), Offset(1.02f, y - 0.32f + jitter(0.2f)), 2.6f, 0.05f)
    } + List(4) { index ->
        val x = 0.12f + index * 0.22f
        wandering(Offset(x, -0.02f), Offset(x - 0.12f + jitter(0.16f), 1.02f), 2.2f, 0.05f)
    }
    val connectors = List(16) {
        val from = Offset(random.nextFloat(), random.nextFloat())
        wandering(from, from + Offset(0.28f * (random.nextFloat() - 0.5f) * 2, 0.28f * (random.nextFloat() - 0.5f) * 2), 1.2f, 0.025f)
    }
    // Neighbourhoods of short streets, each block turned its own way.
    val streets = List(7) {
        val center = Offset(0.04f + random.nextFloat() * 0.8f, 0.04f + random.nextFloat() * 0.75f)
        val angle = random.nextFloat() * 1.5f
        val along = Offset(cos(angle), sin(angle))
        val across = Offset(-along.y, along.x)
        List(8) { line ->
            val offset = (line / 2 - 1.5f) * 0.05f
            val (direction, normal) = if (line % 2 == 0) along to across else across to along
            val middle = center + normal * offset
            Road(listOf(middle - direction * 0.09f, middle + direction * 0.09f), 0.8f)
        }
    }.flatten()
    return Backdrop(coast, parks, streets + connectors + arterials)
}

private fun DrawScope.drawBackdrop(palette: MapPalette, backdrop: Backdrop, scale: Float) {
    val w = size.width
    val h = size.height
    fun pathOf(line: List<Offset>) = Path().apply {
        line.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * w, p.y * h) else lineTo(p.x * w, p.y * h) }
    }
    drawRect(palette.water)
    val land = pathOf(backdrop.coast).apply {
        lineTo(0f, h)
        lineTo(0f, 0f)
        lineTo(w, 0f)
        close()
    }
    drawPath(land, palette.land)
    clipPath(land) {
        backdrop.parks.forEach { drawPath(pathOf(it), palette.park) }
        backdrop.roads.forEach { road ->
            drawPath(pathOf(road.points), palette.road, style = Stroke(road.width * scale * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
