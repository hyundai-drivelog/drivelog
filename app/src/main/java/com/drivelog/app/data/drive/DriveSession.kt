package com.drivelog.app.data.drive

import com.drivelog.app.data.sdk.GeoPoint
import kotlin.math.*

fun routeDistanceKm(points: List<GeoPoint>): Double? {
    if (points.size < 2) return null
    return points.zipWithNext().sumOf { (a, b) ->
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        6371.0088 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
