package com.drivelog.app

import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.withRecent
import com.drivelog.app.core.journey.withSavedToggled
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.feature.car.MapPoint
import com.drivelog.app.feature.car.destinationFitsFrame
import com.drivelog.app.feature.car.isNewDestination
import com.drivelog.app.feature.car.routeBounds
import com.drivelog.app.feature.car.splitAtGaps
import com.drivelog.app.feature.car.withoutSymbolLayers
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

    @Test fun mapStyleLosesItsTextLayersOnly() {
        val cleaned = withoutSymbolLayers("""{"version":8,"layers":[{"id":"road","type":"line"},{"id":"name","type":"symbol"}]}""")
        assertTrue(cleaned.contains("road"))
        assertFalse(cleaned.contains("symbol"))
    }

    @Test fun routeBoundsLeaveRoomAroundEvenAVeryShortDrive() {
        val box = routeBounds(listOf(MapPoint(37.55, 126.97), MapPoint(37.5501, 126.9701)))
        assertTrue(box[2] - box[0] >= 0.012)
        assertTrue(box[3] - box[1] >= 0.012)
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
