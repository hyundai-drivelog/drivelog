package com.drivelog.app.feature.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.drivelog.app.data.sdk.GeoPoint
import kotlin.math.cos

/** Aspect-preserving silhouette. No map tiles or fabricated coordinates. */
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

@Composable
fun RouteSilhouette(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surface)) {
        val shape = routeShape(points, size.width, size.height)
        if (shape.isNotEmpty()) {
            val path = Path().apply {
                moveTo(shape.first().first, shape.first().second)
                shape.drop(1).forEach { lineTo(it.first, it.second) }
            }
            drawPath(path, color, style = Stroke(width = 6f))
            drawCircle(color, 9f, Offset(shape.first().first, shape.first().second))
            drawCircle(color, 9f, Offset(shape.last().first, shape.last().second))
        }
    }
}
