package com.drivelog.app

import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.withRecent
import com.drivelog.app.core.journey.withSavedToggled
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.feature.car.MapPoint
import com.drivelog.app.feature.car.destinationFitsFrame
import com.drivelog.app.feature.car.elapsedText
import com.drivelog.app.feature.car.roundedRoute
import com.drivelog.app.feature.car.routeSummaryText
import com.drivelog.app.feature.car.kmSplits
import com.drivelog.app.feature.car.splitTime
import com.drivelog.app.feature.car.clockRange
import com.drivelog.app.feature.car.encodePolyline
import com.drivelog.app.feature.car.cardShareUrl
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.journey.RecordStatus
import com.drivelog.app.core.platform.RouteSummary
import com.drivelog.app.feature.car.styledRoute
import androidx.compose.ui.geometry.Offset
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
import com.drivelog.app.feature.car.thisMonth
import com.drivelog.app.feature.car.collectedStamps
import com.drivelog.app.feature.car.stampPlaceFor
import com.drivelog.app.feature.car.stampResultFor
import com.drivelog.app.core.recording.resolveDistance
import com.drivelog.app.feature.car.CarDimens
import com.drivelog.app.feature.car.CarLayoutMode
import com.drivelog.app.feature.car.carDimensFor
import com.drivelog.app.feature.car.withDirectionParticle
import org.junit.Assert.*
import org.junit.Test

class JourneyRulesTest {
    private fun place(id: String) = PlaceV(id, 0, id, "", 35.0, 129.0)

    private fun east(km: Double, seconds: Long, north: Double = 0.0) =
        MapPoint(35.0 + north / 111.0, 129.0 + km / 91.0, seconds * 1000)

    @Test fun aCornerSurvivesDrawingAndAStraightNeedsOnlyItsEnds() {
        val straight = (0..500).map { east(it * 0.01, it.toLong()) }
        val corner = (1..500).map { east(5.0, 500L + it, it * 0.01) }
        val drawn = styledRoute(straight + corner).pieces.single()
        assertTrue("a straight then a turn needs few points, got ${drawn.size}", drawn.size < 20)
        assertTrue("the corner must be kept", drawn.any { kotlin.math.abs(it.longitude - straight.last().longitude) < 1e-4 && kotlin.math.abs(it.latitude - straight.last().latitude) < 1e-4 })
        assertEquals(straight.first(), drawn.first())
        assertEquals(corner.last(), drawn.last())
    }

    @Test fun kmMarksFallWhereThatDistanceWasDrivenAndSkipRecordingGaps() {
        val first = (0..250).map { east(it * 0.01, it.toLong()) }
        val second = (0..100).map { east(10.0 + it * 0.01, 10_000L + it) }
        val marks = styledRoute(first + second).marks
        assertEquals(listOf(1, 2, 3), marks.map { it.km })
        assertEquals(east(1.0, 0).longitude, marks[0].point.longitude, 0.001)
        assertEquals(listOf(5, 10, 15, 20, 25), styledRoute((0..2800).map { east(it * 0.01, it.toLong()) }).marks.map { it.km })
    }

    @Test fun roundingACornerStaysCloseToItHoweverLongTheStraightsAre() {
        val line = roundedRoute(listOf(Offset(0f, 0f), Offset(1000f, 0f), Offset(1000f, 1000f)), maxCut = 10f)
        assertEquals(Offset(0f, 0f), line.first())
        assertEquals(Offset(1000f, 1000f), line.last())
        assertTrue(line.minOf { (it - Offset(1000f, 0f)).getDistance() } < 15f)
    }

    @Test fun splitsTimeEachStretchAndLeaveGapsOut() {
        // 1 km in 60 s, then 1 km in 120 s, then a gap of about an hour, then 0.5 km in 60 s.
        // Real points carry epoch times, so a zero time means the time is missing.
        val points = (0..100).map { east(it * 0.01, 1000L + (it * 0.6).toLong()) } +
            (1..100).map { east(1.0 + it * 0.01, 1060L + (it * 1.2).toLong()) } +
            (0..50).map { east(5.0 + it * 0.01, 5000L + (it * 1.2).toLong()) }
        val splits = kmSplits(points)
        assertEquals(3, splits.size)
        assertEquals(60.0, splits[0].seconds.toDouble(), 2.0)
        assertEquals(120.0, splits[1].seconds.toDouble(), 2.0)
        assertEquals(2.5, splits[2].toKm, 0.05)
        assertEquals(60.0, splits[2].seconds.toDouble(), 2.0)
        assertTrue(kmSplits(points.map { it.copy(atMillis = 0) }).isEmpty())
    }

    @Test fun aTimeRangeSaysAmOrPmOnceWhenBothEndsShareIt() {
        assertEquals("오전 4:44–4:48", clockRange("오전 4:44", "오전 4:48"))
        assertEquals("오전 11:50–오후 12:10", clockRange("오전 11:50", "오후 12:10"))
    }

    @Test fun splitTimesReadNaturally() {
        assertEquals("45초", splitTime(45))
        assertEquals("2분 05초", splitTime(125))
        assertEquals("1시간 2분", splitTime(3720))
    }

    @Test fun polylineMatchesGooglesReferenceEncoding() {
        val points = listOf(MapPoint(38.5, -120.2), MapPoint(40.7, -120.95), MapPoint(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encodePolyline(points))
    }

    @Test fun theShareLinkCarriesTheCardAfterTheHashAndStaysScannable() {
        val route = (0..3000).map { listOf(35.15 + it * 0.00001, 129.11 + kotlin.math.sin(it / 50.0) * 0.002, (1_000_000L + it * 1000L).toDouble()) }
        val record = RecordV("id", null, null, "광안리 해수욕장", 1_793_900_000_000, 1_793_903_000_000, 3000, 12.34, DistanceSource.GPS, RecordStatus.COMPLETE, false, route)
        val url = cardShareUrl("https://example.org/card/", record)
        assertTrue(url.startsWith("https://example.org/card/#v=1&"))
        assertTrue(url.contains("&k=12.3&"))
        assertTrue(url.contains("&d=g&"))
        assertTrue("too long for a car-screen QR: ${url.length}", url.length <= 1500)
        assertFalse(url.substringAfter('#').contains(' '))
    }

    @Test fun theNavigationAppsRouteFiguresReadAsDistanceAndRoundedUpTime() {
        assertEquals("내비 추천 경로 · 5.7 km · 약 12분", routeSummaryText(RouteSummary(5650, 700)))
        assertEquals("내비 추천 경로 · 321 km · 약 3시간 52분", routeSummaryText(RouteSummary(321_400, 13_920)))
        assertEquals("내비 추천 경로 · 0.3 km · 약 1분", routeSummaryText(RouteSummary(300, 20)))
    }

    @Test fun smallWindowsNeverShrinkTextBelowTheGuideMinimum() {
        val small = carDimensFor(400f, 300f)
        assertEquals(12f, small.t(14).value, 0.001f)
        assertEquals(84f, CarDimens(CarLayoutMode.WIDE, 1f).t(84).value, 0.001f)
    }

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

    @Test fun theMonthLineCountsOnlyDrivesStartedThisCalendarMonth() {
        fun at(text: String) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).parse(text)!!.time
        fun drive(id: String, start: String) = RecordV(id, null, null, "광안리", at(start), at(start) + 60_000, 60, 1.0, DistanceSource.GPS, RecordStatus.COMPLETE, false, emptyList())
        val records = listOf(drive("a", "2026-11-01 00:05"), drive("b", "2026-10-31 23:55"), drive("c", "2025-11-10 12:00"))
        assertEquals(listOf("a"), thisMonth(records, at("2026-11-06 09:00")).map { it.id })
    }

    private fun stampDrive(id: String, start: Long, endLat: Double, endLon: Double, km: Double = 5.0, simulated: Boolean = false) =
        RecordV(id, null, null, "목적지", start, start + 600_000, 600, km, DistanceSource.GPS, RecordStatus.COMPLETE, simulated,
            listOf(listOf(35.1534, 129.1183, 0.0), listOf(endLat, endLon, 1.0)))

    @Test fun aStampNeedsOnlyARealDriveThatEndedAtThePlace() {
        // The demo route's last point, about 70 m from the beach's point.
        assertEquals("haeundae", stampPlaceFor(stampDrive("a", 1, 35.159297, 129.160109))?.id)
        // Two kilometres short is not a visit.
        assertNull(stampPlaceFor(stampDrive("b", 1, 35.170, 129.180)))
        // A simulated drive and a start pressed by mistake never count, wherever they ended.
        assertNull(stampPlaceFor(stampDrive("c", 1, 35.159297, 129.160109, simulated = true)))
        assertNull(stampPlaceFor(stampDrive("d", 1, 35.159297, 129.160109, km = 0.05)))
    }

    @Test fun theFirstVisitHoldsTheStampAndAReturnIsNamedAsOne() {
        val first = stampDrive("first", 1_000, 35.1587, 129.1604)
        val again = stampDrive("again", 2_000, 35.1590, 129.1600)
        val records = listOf(again, first)
        assertEquals("first", collectedStamps(records)["haeundae"]?.record?.id)
        assertEquals(1, collectedStamps(records).size)
        assertTrue(stampResultFor(first, records)!!.isNew)
        assertFalse(stampResultFor(again, records)!!.isNew)
        // Deleting the first drive hands the stamp to the next one.
        assertTrue(stampResultFor(again, listOf(again))!!.isNew)
    }
}
