package com.drivelog.app

import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.GoalV
import com.drivelog.app.core.journey.JourneyV
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.RecordStatus
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.journey.withReplacedPlace
import com.drivelog.app.core.journey.withVisit
import com.drivelog.app.core.recording.resolveDistance
import com.drivelog.app.feature.car.CarLayoutMode
import com.drivelog.app.feature.car.carDimensFor
import com.drivelog.app.feature.car.withDirectionParticle
import org.junit.Assert.*
import org.junit.Test

class JourneyRulesTest {
    private fun place(id: String) = PlaceV(id, 0, id, "", 35.0, 129.0)
    private val goal = GoalV("g", "목표", "ACTIVE", listOf(place("a"), place("b"), place("c")))
    private fun record(id: String, placeId: String) = RecordV(id, "g", placeId, placeId, 0, 0, 0, null, DistanceSource.NONE, RecordStatus.COMPLETE, false, emptyList())

    @Test fun confirmingAPlaceTwiceCountsOnce() {
        val once = JourneyV(goal).withVisit(record("r1", "a"), 1)
        val twice = once.withVisit(record("r2", "a"), 2)
        assertEquals(1, twice.activeGoal!!.visitedCount)
        assertEquals("r1", twice.activeGoal!!.places[0].visitRecordId)
    }

    @Test fun thirdPlaceAchievesTheGoal() {
        val journey = listOf("a", "b", "c").fold(JourneyV(goal)) { state, id -> state.withVisit(record("r$id", id), 1) }
        assertTrue(journey.activeGoal!!.achieved)
        assertEquals("ACHIEVED", journey.activeGoal!!.status)
    }

    @Test fun onlyUnvisitedPlacesCanBeReplaced() {
        val visited = JourneyV(goal).withVisit(record("r1", "a"), 1)
        val replacement = place("new").copy(name = "새 장소")
        val locked = visited.withReplacedPlace("a", replacement).activeGoal!!.places
        assertEquals("a", locked[0].name)
        val changed = visited.withReplacedPlace("b", replacement).activeGoal!!.places
        assertEquals("새 장소", changed[1].name)
        assertEquals("b", changed[1].id)
    }

    @Test fun recordFromAnotherGoalDoesNotChangeProgress() {
        val other = record("r", "a").copy(goalId = "other")
        assertEquals(0, JourneyV(goal).withVisit(other, 1).activeGoal!!.visitedCount)
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
