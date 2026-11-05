package com.drivelog.app

import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.withRecent
import com.drivelog.app.core.journey.withSavedToggled
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.feature.car.MapPoint
import com.drivelog.app.feature.car.destinationFitsFrame
import com.drivelog.app.feature.car.elapsedText
import com.drivelog.app.feature.car.isNewDestination
import com.drivelog.app.feature.car.routeBounds
import com.drivelog.app.feature.car.splitAtGaps
import com.drivelog.app.feature.car.barelyMoved
import com.drivelog.app.feature.car.SampleCourses
import com.drivelog.app.feature.car.filterCourses
import com.drivelog.app.feature.car.mercatorProjector
import com.drivelog.app.feature.car.nearestArea
import com.drivelog.app.feature.car.routeKm
import com.drivelog.app.feature.car.trimRouteEnds
import com.drivelog.app.feature.car.previewLinkCode
import com.drivelog.app.feature.car.routeMapStyle
import com.drivelog.app.feature.car.thinnedForDrawing
import com.drivelog.app.feature.car.mergePlaces
import com.drivelog.app.core.recording.resolveDistance
import com.drivelog.app.feature.car.CarLayoutMode
import com.drivelog.app.feature.car.carDimensFor
import com.drivelog.app.feature.car.withDirectionParticle
import org.junit.Assert.*
import org.junit.Test

class JourneyRulesTest {
    private fun place(id: String) = PlaceV(id, 0, id, "", 35.0, 129.0)

    @Test fun elapsedTimeChangesOnlyEveryMinuteWhileDriving() {
        assertEquals("1분 미만", elapsedText(0))
        assertEquals("1분 미만", elapsedText(59))
        assertEquals("1분", elapsedText(60))
        assertEquals("1분", elapsedText(119))
        assertEquals("59분", elapsedText(3599))
        assertEquals("1시간 0분", elapsedText(3600))
        assertEquals("2시간 5분", elapsedText(7500))
    }

    @Test fun recentPlaceMovesToTheTopInsteadOfRepeating() {
        val a = place("a").copy(latitude = 1.0)
        val b = place("b").copy(latitude = 2.0)
        assertEquals(listOf("a", "b"), listOf(b, a).withRecent(a).map { it.id })
        assertEquals(20, (1..30).fold(emptyList<PlaceV>()) { list, i -> list.withRecent(place("p$i").copy(latitude = i.toDouble())) }.size)
    }

    @Test fun savingAPlaceTwiceRemovesIt() {
        val a = place("a")
        assertEquals(1, emptyList<PlaceV>().withSavedToggled(a).size)
        assertEquals(0, listOf(a).withSavedToggled(a).size)
    }

    @Test fun mergedPlacesDropEmptySlotsAndRepeats() {
        val own = NaviPlace("광안리", "", 35.15, 129.11)
        val navi = NaviPlace("광안리 해수욕장", "", 35.15, 129.11)
        val empty = NaviPlace("", "", 0.0, 0.0)
        assertEquals(listOf("광안리"), mergePlaces(listOf(own), listOf(navi, empty)).map { it.name })
    }

    @Test fun mapStyleKeepsTheProviderTilesUnderOurOwnLayers() {
        val style = routeMapStyle("""{"version":8,"sprite":"s","glyphs":"g","sources":{"openmaptiles":{"type":"vector"}},"layers":[{"id":"houses","type":"fill","source-layer":"building"},{"id":"name","type":"symbol"}]}""")
        assertTrue(style.contains("openmaptiles"))
        assertTrue(style.contains("transportation"))
        assertFalse(style.contains("building"))
        assertFalse(style.contains("symbol"))
        assertFalse(style.contains("sprite"))
        assertFalse(style.contains("glyphs"))
    }

    @Test fun savingBecomesAChoiceOnlyWhenTheCarBarelyMoved() {
        assertTrue(barelyMoved(0.0, hasRoute = true))
        assertTrue(barelyMoved(0.09, hasRoute = true))
        assertTrue(barelyMoved(5.0, hasRoute = false))
        assertTrue(barelyMoved(null, hasRoute = true))
        assertFalse(barelyMoved(0.1, hasRoute = true))
        assertFalse(barelyMoved(18.6, hasRoute = true))
    }

    /** About 111 m a step: 0.001 degrees of latitude. */
    private fun northward(steps: Int) = List(steps + 1) { MapPoint(35.0 + it * 0.001, 129.0) }

    @Test fun aRecommendedRouteLosesBothEnds() {
        val route = northward(30)
        val trimmed = trimRouteEnds(route)!!
        assertEquals(routeKm(route) - 1.0, routeKm(trimmed), 0.001)
        assertFalse(route.first() in trimmed)
        assertFalse(route.last() in trimmed)
    }

    @Test fun sparsePointsAreCutAtTheDistanceNotAtTheNextPoint() {
        // Every 1.1 km, as a poor signal records. Only 500 m goes from each end, not a whole step.
        val route = List(5) { MapPoint(35.0 + it * 0.01, 129.0, it * 1000L) }
        val trimmed = trimRouteEnds(route)!!
        assertEquals(routeKm(route) - 1.0, routeKm(trimmed), 0.001)
        assertEquals(route[1], trimmed[1])
        assertEquals(route[3], trimmed[trimmed.lastIndex - 1])
        assertTrue(trimmed.first().atMillis in 1 until 1000L)
    }

    @Test fun aRouteTooShortToTrimCannotBeRecommended() {
        assertNull(trimRouteEnds(northward(8)))
        assertNull(trimRouteEnds(emptyList()))
        assertNull(trimRouteEnds(listOf(MapPoint(35.0, 129.0))))
    }

    @Test fun anOriginIsNamedOnlyAtNeighbourhoodLevel() {
        assertEquals("부산 수영구", nearestArea(MapPoint(35.146, 129.113)))
        assertEquals("출발지 주변", nearestArea(MapPoint(36.5, 128.0)))
    }

    @Test fun coursesFilterByOriginAndDestinationWithMineFirst() {
        val mine = SampleCourses[0].copy(id = "mine", recommendations = 0, mine = true)
        val all = SampleCourses + mine
        assertEquals("mine", filterCourses(all, null, null).first().id)
        assertTrue(filterCourses(all, "부산 사하구", null).all { it.originArea == "부산 사하구" })
        assertTrue(filterCourses(all, null, "양평 두물머리").all { it.destinationName == "양평 두물머리" })
        assertTrue(filterCourses(all, "서울 강남구", "송정 해수욕장").isEmpty())
    }

    @Test fun sampleCoursesCanAllBeDrawnAndHaveUniqueIds() {
        assertEquals(SampleCourses.size, SampleCourses.map { it.id }.distinct().size)
        SampleCourses.forEach { assertTrue(it.route.size >= 2 && it.distanceKm > 1.0) }
    }

    @Test fun previewLinkCodeIsAlwaysSixDigits() {
        repeat(200) { assertTrue(previewLinkCode().matches(Regex("[1-9][0-9]{5}"))) }
    }

    @Test fun mapStyleFollowsTheTheme() {
        val provider = """{"version":8,"sources":{"openmaptiles":{"type":"vector"}},"layers":[]}"""
        assertNotEquals(routeMapStyle(provider, dark = true), routeMapStyle(provider, dark = false))
    }

    @Test fun mercatorProjectorPutsTheCornersOnTheirPixelsAndStretchesTowardThePole() {
        val box = doubleArrayOf(35.0, 129.0, 36.0, 130.0)
        val project = mercatorProjector(box, 0f, 1000f, 1000f, 0f)
        assertEquals(0f, project(MapPoint(35.0, 129.0)).x, 0.01f)
        assertEquals(1000f, project(MapPoint(35.0, 129.0)).y, 0.01f)
        assertEquals(1000f, project(MapPoint(36.0, 130.0)).x, 0.01f)
        assertEquals(0f, project(MapPoint(36.0, 130.0)).y, 0.01f)
        assertEquals(500f, project(MapPoint(35.5, 129.5)).x, 0.01f)
        // Mercator: the middle latitude sits below the frame's middle, by a small but real amount.
        assertTrue(project(MapPoint(35.5, 129.5)).y > 500f)
    }

    @Test fun routeBoundsLeaveRoomAroundEvenAVeryShortDrive() {
        val box = routeBounds(listOf(MapPoint(37.55, 126.97), MapPoint(37.5501, 126.9701)))
        assertEquals(0.035, box[2] - box[0], 1e-9)
        assertEquals(0.035, box[3] - box[1], 1e-9)
        assertTrue(box[0] < 37.55 && box[2] > 37.5501)
    }

    @Test fun recordingGapsAreNotDrawnAsDriving() {
        val points = listOf(MapPoint(35.0, 129.0, 0), MapPoint(35.1, 129.0, 1_000), MapPoint(35.2, 129.0, 120_000))
        assertEquals(listOf(2, 1), splitAtGaps(points).map { it.size })
    }

    @Test fun thinningKeepsBothEndsOfTheDrive() {
        val thinned = (0..999).toList().thinnedForDrawing(240)
        assertEquals(240, thinned.size)
        assertEquals(0, thinned.first())
        assertEquals(999, thinned.last())
    }

    @Test fun aFarDestinationStaysOutOfTheFrame() {
        val drive = listOf(MapPoint(37.55, 126.97), MapPoint(37.56, 126.98))
        assertTrue(destinationFitsFrame(drive, MapPoint(37.561, 126.981)))
        assertFalse(destinationFitsFrame(drive, MapPoint(35.15, 129.11)))
    }

    @Test fun onlyADestinationSetAfterTheSearchCounts() {
        val before = NaviPlace("집", "", 37.0, 127.0)
        val picked = NaviPlace("광안리해수욕장", "", 35.15, 129.11)
        assertTrue(isNewDestination(picked, null))
        assertTrue(isNewDestination(picked, before))
        assertFalse(isNewDestination(before, before))
        assertFalse(isNewDestination(null, before))
        // The navigation app answers with an empty destination when none is set.
        assertFalse(isNewDestination(NaviPlace("", "", 0.0, 0.0), before))
    }

    @Test fun distanceIsEstimatedUnlessVehicleAgreesWithTrack() {
        assertEquals(10.0 to DistanceSource.GPS, resolveDistance(10.0, true, null))
        assertEquals(10.4 to DistanceSource.VEHICLE, resolveDistance(10.0, true, 10.4))
        // A raw reading of 0.0, as the emulator reports, or in another unit, must not replace the track.
        assertEquals(10.0 to DistanceSource.GPS, resolveDistance(10.0, true, 0.0))
        assertEquals(10.0 to DistanceSource.GPS, resolveDistance(10.0, true, 10000.0))
    }

    @Test fun noRouteMeansNoDistanceRatherThanZero() {
        assertEquals(null to DistanceSource.NONE, resolveDistance(0.0, false, 5.0))
    }

    @Test fun directionParticleFollowsFinalConsonant() {
        assertEquals("송정으로", withDirectionParticle("송정"))
        assertEquals("광안리로", withDirectionParticle("광안리"))
        assertEquals("다대포로", withDirectionParticle("다대포"))
        assertEquals("서울로", withDirectionParticle("서울"))
        assertEquals("수서역 SRT(으)로", withDirectionParticle("수서역 SRT"))
    }

    @Test fun windowShapeSelectsLayout() {
        // Full app area of a QHD panel, as measured on the Pleos emulator.
        assertEquals(CarLayoutMode.WIDE, carDimensFor(1706f, 1210f).mode)
        // Portrait half of a split screen.
        assertEquals(CarLayoutMode.STACKED, carDimensFor(853f, 1210f).mode)
        // Very short panels shrink but stay within bounds.
        assertTrue(carDimensFor(800f, 300f).scale >= 0.6f)
    }
}
