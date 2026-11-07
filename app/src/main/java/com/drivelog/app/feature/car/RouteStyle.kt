package com.drivelog.app.feature.car

import androidx.compose.ui.geometry.Offset
import com.drivelog.app.core.recording.haversineKm
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class KmMark(val km: Int, val point: MapPoint)

/** A drive made ready to draw. It holds no new data: every position comes from its own recorded points. */
class StyledRoute(val pieces: List<List<MapPoint>>, val marks: List<KmMark>)

private const val ROUTE_DRAWN_POINTS = 600
private const val SIMPLIFY_FRACTION = 0.0008
private const val MAX_CORNER_CUT = 14f

fun styledRoute(points: List<MapPoint>, limit: Int = ROUTE_DRAWN_POINTS): StyledRoute {
    val rawPieces = splitAtGaps(points)
    val tolerance = simplifyTolerance(points)
    val perPiece = (limit / rawPieces.size.coerceAtLeast(1)).coerceAtLeast(2)
    val pieces = rawPieces.map { piece -> simplifiedIndices(piece, tolerance, perPiece).map { piece[it] } }
    return StyledRoute(pieces, kmMarks(rawPieces))
}

private fun simplifyTolerance(points: List<MapPoint>): Double {
    if (points.isEmpty()) return 0.0
    val k = cos(Math.toRadians(points.first().latitude))
    val width = (points.maxOf { it.longitude } - points.minOf { it.longitude }) * k
    val height = points.maxOf { it.latitude } - points.minOf { it.latitude }
    return hypot(width, height) * SIMPLIFY_FRACTION
}

/**
 * Keeps the points that give the route its shape, so a corner survives however long the drive is. Evenly spaced
 * thinning cut corners off: on a long straight it dropped nothing it needed, while on a bend it lost the bend.
 */
private fun simplifiedIndices(points: List<MapPoint>, tolerance: Double, limit: Int): List<Int> {
    if (points.size <= 2) return points.indices.toList()
    val k = cos(Math.toRadians(points.first().latitude))
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.lastIndex)
    while (stack.isNotEmpty()) {
        val (from, to) = stack.removeLast()
        var farthest = -1
        var distance = tolerance
        val ax = points[from].longitude * k
        val ay = points[from].latitude
        val dx = points[to].longitude * k - ax
        val dy = points[to].latitude - ay
        val length = hypot(dx, dy)
        for (index in from + 1 until to) {
            val px = points[index].longitude * k - ax
            val py = points[index].latitude - ay
            val away = if (length == 0.0) hypot(px, py) else abs(dx * py - dy * px) / length
            if (away > distance) { distance = away; farthest = index }
        }
        if (farthest >= 0) {
            keep[farthest] = true
            stack.addLast(from to farthest)
            stack.addLast(farthest to to)
        }
    }
    val kept = points.indices.filter { keep[it] }
    return if (kept.size <= limit) kept else kept.thinnedForDrawing(limit)
}

private fun kmStep(totalKm: Double): Int = when {
    totalKm <= 10 -> 1
    totalKm <= 20 -> 2
    totalKm <= 50 -> 5
    totalKm <= 100 -> 10
    totalKm <= 200 -> 20
    else -> 50
}

/** Where each round distance was reached along the recorded line. The jump across a recording gap counts for nothing. */
private fun kmMarks(pieces: List<List<MapPoint>>): List<KmMark> {
    val total = pieces.sumOf { piece -> piece.zipWithNext().sumOf { (a, b) -> haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) } }
    val step = kmStep(total)
    val marks = ArrayList<KmMark>()
    var driven = 0.0
    var next = step
    pieces.forEach { piece ->
        piece.zipWithNext().forEach { (a, b) ->
            val length = haversineKm(a.latitude, a.longitude, b.latitude, b.longitude)
            while (length > 0 && driven + length >= next) {
                val along = (next - driven) / length
                // A number right on top of the finish marker would hide it.
                if (total - next >= step * 0.3) marks.add(KmMark(next, MapPoint(a.latitude + (b.latitude - a.latitude) * along, a.longitude + (b.longitude - a.longitude) * along)))
                next += step
            }
            driven += length
        }
    }
    return marks
}

/**
 * One stretch of a drive and the time spent covering it. The last one may be shorter than the step. A break the
 * driver marked inside it is kept apart in [restSeconds], so the driving time is not inflated by a stop for coffee.
 */
data class KmSplit(val fromKm: Double, val toKm: Double, val seconds: Long, val restSeconds: Long = 0)

private const val MIN_LAST_SPLIT_KM = 0.1

/**
 * Time for each stretch of the recorded line, measured only while recording: a gap adds neither distance nor time.
 * Empty when the points carry no times. Uses the same step as the distance marks on the map.
 */
fun kmSplits(points: List<MapPoint>, rests: List<List<Long>> = emptyList()): List<KmSplit> {
    val pieces = splitAtGaps(points)
    fun restIn(from: Long, to: Long) = rests.sumOf { rest ->
        val start = rest.getOrNull(0) ?: return@sumOf 0L
        val end = rest.getOrNull(1) ?: return@sumOf 0L
        (minOf(to, end) - maxOf(from, start)).coerceAtLeast(0)
    }.toDouble()
    if (pieces.flatten().any { it.atMillis <= 0 }) return emptyList()
    val total = pieces.sumOf { piece -> piece.zipWithNext().sumOf { (a, b) -> haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) } }
    val step = kmStep(total).toDouble()
    val splits = ArrayList<KmSplit>()
    // Distance and recording time at the start of each segment; boundaries inside it are interpolated.
    var driven = 0.0
    var millis = 0.0
    var splitStartKm = 0.0
    var splitStartMillis = 0.0
    // Break time is counted on its own clock and never moves the split boundaries, which follow distance.
    var rested = 0.0
    var splitStartRest = 0.0
    pieces.forEach { piece ->
        piece.zipWithNext().forEach { (a, b) ->
            val length = haversineKm(a.latitude, a.longitude, b.latitude, b.longitude)
            val rest = restIn(a.atMillis, b.atMillis)
            val duration = ((b.atMillis - a.atMillis).coerceAtLeast(0) - rest).coerceAtLeast(0.0)
            while (length > 0 && driven + length >= splitStartKm + step) {
                val fraction = (splitStartKm + step - driven) / length
                val at = millis + duration * fraction
                val restAt = rested + rest * fraction
                splits.add(KmSplit(splitStartKm, splitStartKm + step, ((at - splitStartMillis) / 1000).toLong(), ((restAt - splitStartRest) / 1000).toLong()))
                splitStartKm += step
                splitStartMillis = at
                splitStartRest = restAt
            }
            driven += length
            millis += duration
            rested += rest
        }
    }
    if (driven - splitStartKm >= MIN_LAST_SPLIT_KM) {
        splits.add(KmSplit(splitStartKm, driven, ((millis - splitStartMillis) / 1000).toLong(), ((rested - splitStartRest) / 1000).toLong()))
    }
    return splits
}

/** Rounds corners for display by a fixed small amount, so the line stays on the road however long the straights are. */
fun roundedRoute(line: List<Offset>, maxCut: Float = MAX_CORNER_CUT, passes: Int = 2): List<Offset> {
    var points = line
    repeat(passes) {
        if (points.size < 3) return points
        val next = ArrayList<Offset>(points.size * 2)
        next.add(points.first())
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            val length = (b - a).getDistance()
            val cut = if (length == 0f) 0f else minOf(0.25f, maxCut / length)
            next.add(a + (b - a) * cut)
            next.add(b - (b - a) * cut)
        }
        next.add(points.last())
        points = next
    }
    return points
}
