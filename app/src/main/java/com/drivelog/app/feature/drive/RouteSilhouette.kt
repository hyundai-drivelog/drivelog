package com.drivelog.app.feature.drive

import com.drivelog.app.data.sdk.GeoPoint
import kotlin.math.cos

/** Aspect-preserving projection of real coordinates. No map tiles or fabricated coordinates. */
fun routeShape(points: List<GeoPoint>, width: Float, height: Float): List<Pair<Float, Float>> {
    if (points.isEmpty()) return emptyList()
    val latitude = points.map { it.latitude }.average()
    val xs = points.map { it.longitude * cos(Math.toRadians(latitude)) }
    val ys = points.map { -it.latitude }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val dx = (maxX - minX).coerceAtLeast(0.0000001)
    val dy = (maxY - minY).coerceAtLeast(0.0000001)
    val scale = minOf((width - 40).coerceAtLeast(1f) / dx, (height - 40).coerceAtLeast(1f) / dy)
    return xs.zip(ys).map { (x, y) ->
        ((width - dx * scale) / 2 + (x - minX) * scale).toFloat() to
                ((height - dy * scale) / 2 + (y - minY) * scale).toFloat()
    }
}
