package com.drivelog.app

import com.drivelog.app.core.journey.*
import org.junit.Assert.*
import org.junit.Test

class JourneySyncRulesTest {
    private fun record(id: String) = RecordV(id, "old-goal", "old-place", "장소", 1000, 61000, 60,
        1.0, DistanceSource.GPS, RecordStatus.COMPLETE, false,
        listOf(listOf(35.0, 129.0, 1000.0), listOf(35.01, 129.01, 61000.0)))

    @Test fun anotherConnectionDoesNotInheritHistoryByDefault() {
        val old = StoreState(journey = JourneyV(records = listOf(record("private"))),
            outbox = listOf(OutboxOp(OutboxOp.RECORD, "private", record("private"))), token = "account-a")
        val next = linkHistory(StoreState(), old, false)
        assertTrue(next.journey.records.isEmpty())
        assertTrue(next.outbox.isEmpty())
        // Even a stale import toggle cannot copy a currently linked profile into another account.
        assertTrue(linkHistory(StoreState(), old, true).journey.records.isEmpty())
    }

    @Test fun localImportIsExplicitAndDoesNotReuseAnotherAccountsGoal() {
        val guest = StoreState(journey = JourneyV(records = listOf(record("before-link"))))
        assertTrue(linkHistory(StoreState(), guest, false).outbox.isEmpty())
        val imported = linkHistory(StoreState(), guest, true)
        assertEquals("before-link", imported.journey.records.single().id)
        assertNull(imported.journey.records.single().goalId)
        assertNull(imported.journey.records.single().placeId)
        assertEquals(1, imported.outbox.size)
        assertEquals(1, linkHistory(imported, guest, true).outbox.size)
        assertEquals("old-goal", guest.journey.records.single().goalId)
    }

    @Test fun completeSnapshotKeepsMoreThanTwoHundredRecords() {
        val remote = JourneyV(records = (0..205).map { record("record-$it") })
        assertEquals(206, reconcileJourney(remote, emptyList()).records.size)
    }

    @Test fun editsDuringFetchSurviveAndRemoteDeletionIsApplied() {
        val remote = JourneyV(records = listOf(record("deleted-locally"), record("edited")))
        val replacement = record("edited").copy(placeName = "새 이름")
        val pending = listOf(OutboxOp(OutboxOp.DELETE_RECORD, "deleted-locally"),
            OutboxOp(OutboxOp.RECORD, "new-local", record("new-local")),
            OutboxOp(OutboxOp.RECORD, "edited", replacement))
        val result = reconcileJourney(remote, pending).records
        assertEquals(setOf("new-local", "edited"), result.map { it.id }.toSet())
        assertEquals("새 이름", result.first { it.id == "edited" }.placeName)
        assertTrue(reconcileJourney(JourneyV(), emptyList()).records.isEmpty())
    }

    @Test fun diskNamespacesDoNotContainTokensAndStaySeparate() {
        assertNotEquals(journeyProfileId("account-a"), journeyProfileId("account-b"))
        assertEquals(64, journeyProfileId("account-a").length)
        assertTrue(journeyProfileId("account-a").all { it in "0123456789abcdef" })
    }
}
