package com.drivelog.app

import com.drivelog.app.data.drive.routeDistanceKm
import com.drivelog.app.data.sdk.GeoPoint
import com.drivelog.app.data.sdk.RecordingMode
import com.drivelog.app.feature.drive.routeShape
import org.junit.Assert.*
import org.junit.Test

class DriveGeometryTest {
    private fun point(lat: Double, lon: Double) = GeoPoint(lat, lon, 0, RecordingMode.MOCK)

    @Test fun absentRouteHasNoInventedDistance() {
        assertNull(routeDistanceKm(emptyList()))
        assertNull(routeDistanceKm(listOf(point(0.0, 0.0))))
    }

    @Test fun equatorialDegreeAndStationaryRoute() {
        assertEquals(111.195, routeDistanceKm(listOf(point(0.0, 0.0), point(0.0, 1.0)))!!, 0.01)
        assertEquals(0.0, routeDistanceKm(listOf(point(37.0, 127.0), point(37.0, 127.0)))!!, 0.0)
    }

    @Test fun silhouetteFitsAndKeepsNorthAboveSouth() {
        val shape = routeShape(listOf(point(37.0, 127.0), point(37.1, 127.2)), 500f, 300f)
        assertTrue(shape.all { (x, y) -> x in 0f..500f && y in 0f..300f })
        assertTrue(shape[1].second < shape[0].second)
        assertTrue(shape[1].first > shape[0].first)
        assertTrue(routeShape(listOf(point(37.0, 127.0)), 500f, 300f).all { it.first.isFinite() && it.second.isFinite() })
    }
}
