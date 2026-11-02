package com.drivelog.app.data.drive

import com.drivelog.app.data.sdk.DriveDestination
import com.drivelog.app.data.sdk.GeoPoint
import com.drivelog.app.data.sdk.RecordingMode
import kotlin.math.*

data class DriveSession(
    val mode: RecordingMode,
    val destination: DriveDestination,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Long,
    val points: List<GeoPoint>,
    val distanceKm: Double?,
    val averageEfficiency: Double?,
    val efficiencyUnit: String?,
)

fun routeDistanceKm(points: List<GeoPoint>): Double? {
    if (points.size < 2) return null
    return points.zipWithNext().sumOf { (a, b) ->
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        6371.0088 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Synthetic route; never used by the PLEOS recording branch. */
class MockVehicleDataSource {
    fun point(step: Int, now: Long): GeoPoint {
        val progress = (step.coerceAtMost(60) / 60.0)
        return GeoPoint(37.520 + progress * 0.0245, 127.010 + progress * 0.0274 + sin(progress * PI * 3) * 0.003,
            now, RecordingMode.MOCK)
    }
    val efficiency = 14.2
}
