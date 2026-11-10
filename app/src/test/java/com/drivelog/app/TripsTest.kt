package com.drivelog.app

import com.drivelog.app.core.journey.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class TripsTest {
    private fun record(id: String, start: Long, distance: Double? = 2.5, simulated: Boolean = false) = RecordV(
        id, null, null, "장소", start, start + 120000, 120, distance, DistanceSource.GPS, RecordStatus.COMPLETE,
        simulated, listOf(listOf(35.0, 129.0, start.toDouble()), listOf(35.01, 129.01, (start + 120000).toDouble())),
        rests = listOf(listOf(start + 1000, start + 31000)),
    )
    private val first = record("one", 1000)
    private val second = record("two", 86401000, null, true)
    private val trip = TripV("trip", "부산에서 보낸 하루", listOf("two", "one", "one"), "one", 5000)

    @Test fun summaryUsesOnlyMembersAndDoesNotCountOvernightGapAsRestOrDriving() {
        val summary = summarizeTrip(trip, listOf(second, first, record("other", 2000)))
        assertEquals(listOf("one", "two"), summary.records.map { it.id })
        assertEquals(2.5, summary.distanceKm, 0.00001)
        assertEquals(1, summary.unknownDistances)
        assertEquals(240L, summary.recordingSeconds)
        assertEquals(60L, summary.restSeconds)
        assertEquals(first.startedAtMillis, summary.firstAtMillis)
        assertEquals(second.endedAtMillis, summary.lastAtMillis)
        assertTrue(summary.simulated)
    }

    @Test fun editingPreservesIdentityAndRejectsConflictingOrMissingDrives() {
        val records = listOf(first, second)
        val updated = prepareTrip("trip", "  바다   여행  ", listOf("one", "two", "one"), "missing", 9000, listOf(trip), records)!!
        assertEquals("바다 여행", updated.title)
        assertEquals(5000, updated.createdAtMillis)
        assertEquals(listOf("one", "two"), updated.recordIds)
        assertEquals("one", updated.coverRecordId)
        assertNull(prepareTrip("other", "다른 여행", listOf("one"), null, 0, listOf(trip), records))
        assertNull(prepareTrip("trip", "여행", listOf("missing"), null, 0, listOf(trip), records))
        assertNull(prepareTrip("trip", " ", listOf("one"), null, 0, listOf(trip), records))
        assertNull(prepareTrip("trip", "a".repeat(41), listOf("one"), null, 0, listOf(trip), records))
        assertNull(prepareTrip("trip", "여행", emptyList(), null, 0, listOf(trip), records))
    }

    @Test fun deletedMemberUpdatesCoverAndKeepsEmptyTripWithoutDeletingOtherRecords() {
        val retained = retainTripRecords(listOf(trip), listOf(second)).single()
        assertEquals(listOf("two"), retained.recordIds)
        assertEquals("two", retained.coverRecordId)
        val empty = retainTripRecords(listOf(trip), emptyList()).single()
        assertEquals(trip.title, empty.title)
        assertTrue(empty.recordIds.isEmpty())
        assertNull(empty.coverRecordId)
        assertEquals(3, trip.recordIds.size)
    }

    @Test fun oldStoredDataWithoutTripsLoadsAndNewMetadataRoundTrips() {
        val json = Gson()
        val legacy = json.fromJson("{\"journey\":{\"records\":[]},\"outbox\":[]}", StoreState::class.java)
        assertTrue(legacy.trips.isEmpty())
        val stored = StoreState(journey = JourneyV(records = listOf(first, second)), trips = listOf(trip))
        assertEquals(trip, json.fromJson(json.toJson(stored), StoreState::class.java).trips.single())
    }

    @Test fun localTripImportRequiresTheSameExplicitHistoryChoice() {
        val local = StoreState(journey = JourneyV(records = listOf(first, second)), trips = listOf(trip))
        assertTrue(linkHistory(StoreState(), local, false).trips.isEmpty())
        assertEquals(listOf("two", "one"), linkHistory(StoreState(), local, true).trips.single().recordIds)
        assertTrue(linkHistory(StoreState(), local.copy(token = "other-account"), true).trips.isEmpty())
    }

    @Test fun importedGroupsCannotClaimAnAlreadyGroupedDrive() {
        val profileTrip = trip.copy(id = "existing", recordIds = listOf("one"))
        val profile = StoreState(journey = JourneyV(records = listOf(first)), trips = listOf(profileTrip))
        val guest = StoreState(journey = JourneyV(records = listOf(first, second)), trips = listOf(trip))
        val linked = linkHistory(profile, guest, true)
        assertEquals(listOf("two"), linked.trips.first { it.id == "trip" }.recordIds)
        assertEquals(listOf("one"), linked.trips.first { it.id == "existing" }.recordIds)
    }

    @Test fun aTripDoesNotGrantAStampFromASimulatedLeg() {
        val summary = summarizeTrip(trip, listOf(first, second))
        val place = com.drivelog.app.feature.car.StampPlace("test", "명소", "지역", 35.01, 129.01, "설명", 0f)
        val stamps = com.drivelog.app.feature.car.collectedStamps(summary.records, listOf(place))
        assertEquals(1, stamps.size)
        assertEquals("one", stamps.getValue("test").record.id)
    }

    @Test fun returningFromTripRestoresTheOriginalCardAndItsParent() {
        val target = com.drivelog.app.feature.car.TripReturnTarget(com.drivelog.app.feature.car.Screen.RECORD, "original", com.drivelog.app.feature.car.Screen.HISTORY)
        assertEquals(target, com.drivelog.app.feature.car.resolveTripReturn(target, setOf("original", "another-drive")))
        assertEquals(com.drivelog.app.feature.car.Screen.HISTORY,
            com.drivelog.app.feature.car.resolveTripReturn(target, setOf("another-drive")).screen)
        val nested = target.copy(recordParent = com.drivelog.app.feature.car.Screen.TRIP)
        assertEquals(com.drivelog.app.feature.car.Screen.HISTORY,
            com.drivelog.app.feature.car.resolveTripReturn(nested, emptySet()).screen)
    }

    @Test fun everyTripScreenIsUnavailableWhileDriving() {
        val screens = listOf(com.drivelog.app.feature.car.Screen.TRIP, com.drivelog.app.feature.car.Screen.TRIP_EDITOR, com.drivelog.app.feature.car.Screen.TRIP_PICKER)
        screens.forEach { screen ->
            assertEquals(com.drivelog.app.feature.car.Screen.LOCKED, com.drivelog.app.feature.car.routeFor(false, false, screen))
            assertEquals(com.drivelog.app.feature.car.Screen.RECORDING, com.drivelog.app.feature.car.routeFor(true, false, screen))
            assertEquals(screen, com.drivelog.app.feature.car.routeFor(false, true, screen))
        }
    }
}
