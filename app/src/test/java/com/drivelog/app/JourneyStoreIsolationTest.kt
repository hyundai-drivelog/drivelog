package com.drivelog.app

import com.drivelog.app.core.journey.*
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercise the actual store, its disk writer and suspended network calls without an emulator. */
class JourneyStoreIsolationTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var token: String? = null
    private val servers = ConcurrentHashMap<String, FakeApi>()
    private fun store() = JourneyStore(folder.root, scope, true, { token }, { token = it },
        { key -> servers.computeIfAbsent(key ?: "guest") { FakeApi() } })
    private fun record(id: String) = RecordV(id, null, null, "장소", 1000, 61000, 60,
        1.0, DistanceSource.GPS, RecordStatus.COMPLETE, false,
        listOf(listOf(35.0, 129.0, 1000.0), listOf(35.01, 129.01, 61000.0)))

    @After fun stop() { scope.cancel() }

    @Test fun switchingAccountsNeverUploadsOldRecordsOrQueue() = runBlocking {
        val store = store()
        store.setToken("secret-A")
        assertTrue(store.addRecordDurably(record("private-a")))
        assertTrue(store.sync())
        store.unlink()
        assertTrue(store.state.value.journey.records.isEmpty())
        assertTrue(store.addRecordDurably(record("unlinked")))
        store.setToken("secret-B")
        assertTrue(store.sync())
        assertTrue(servers.getValue("secret-B").records.isEmpty())
        assertTrue(store.state.value.outbox.isEmpty())
        assertTrue(store.addRecordDurably(record("private-b")))
        assertTrue(store.sync())
        // A new store restores B's namespace, not A or the unlinked history.
        assertEquals(listOf("private-b"), store().state.value.journey.records.map { it.id })
        store.unlink()
        assertEquals(listOf("unlinked"), store.state.value.journey.records.map { it.id })
        assertTrue(File(folder.root, "journey-profiles/${journeyProfileId("secret-A")}/store.json").exists())
        folder.root.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach {
            assertFalse(it.readText().contains("secret-A"))
            assertFalse(it.readText().contains("secret-B"))
        }
    }

    @Test fun responseFromDisconnectedAccountCannotReplaceNewAccount() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = FakeApi().also { api -> api.fetch = {
            entered.complete(Unit)
            release.await()
            JourneyV(records = listOf(record("private-a")))
        } }
        servers["secret-A"] = old
        val store = store()
        store.setToken("secret-A")
        withTimeout(5000) { entered.await() }
        store.unlink()
        store.setToken("secret-B")
        release.complete(Unit)
        assertTrue(withTimeout(5000) { store.sync() })
        assertEquals("secret-B", store.state.value.token)
        assertTrue(store.state.value.journey.records.isEmpty())
    }

    @Test fun localEditsWhileSnapshotIsInFlightSurvive() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = FakeApi().also { api -> api.fetch = {
            entered.complete(Unit)
            release.await()
            JourneyV(records = listOf(record("deleted")))
        } }
        servers["secret-A"] = server
        val store = store()
        store.setToken("secret-A")
        withTimeout(5000) { entered.await() }
        store.deleteRecord("deleted")
        assertTrue(store.addRecordDurably(record("added")))
        assertNotNull(store.saveTripDurably("trip", "내 여행", listOf("added"), "added"))
        release.complete(Unit)
        // Complete a following sync to drain both pending operations.
        server.fetch = null
        withTimeout(5000) { store.sync() }
        assertEquals(listOf("added"), store.state.value.journey.records.map { it.id })
        assertEquals(listOf("added"), store.state.value.trips.single().recordIds)
    }

    @Test fun tripsPersistAcrossRestartAndUngroupingNeverDeletesDrives() = runBlocking {
        val store = store()
        assertTrue(store.addRecordDurably(record("one")))
        assertTrue(store.addRecordDurably(record("two")))
        assertNotNull(store.saveTripDurably("trip", "부산 여행", listOf("one", "two"), "one"))
        assertEquals("부산 여행", store().state.value.trips.single().title)
        assertTrue(store.deleteTripDurably("trip"))
        assertTrue(store().state.value.trips.isEmpty())
        assertEquals(setOf("one", "two"), store().state.value.journey.records.map { it.id }.toSet())
    }

    @Test fun linkedTripMetadataStaysLocalAndDoesNotAppearInAnotherConnection() = runBlocking {
        val store = store()
        store.setToken("secret-A")
        assertTrue(store.addRecordDurably(record("one")))
        assertNotNull(store.saveTripDurably("trip", "A의 여행", listOf("one"), "one"))
        assertTrue(store.sync())
        assertEquals("A의 여행", store.state.value.trips.single().title)
        store.unlink()
        assertTrue(store.state.value.trips.isEmpty())
        store.setToken("secret-B")
        assertTrue(store.sync())
        assertTrue(store.state.value.trips.isEmpty())
    }

    @Test fun legacyLinkedFileDoesNotResumeSendingWithoutNewConsent() {
        File(folder.root, "journey-store.json").writeText(Gson().toJson(StoreState(
            journey = JourneyV(records = listOf(record("legacy"))),
            outbox = listOf(OutboxOp(OutboxOp.RECORD, "legacy", record("legacy"))),
        )))
        token = "legacy-secret"
        val store = store()
        assertNull(store.state.value.token)
        assertEquals(listOf("legacy"), store.state.value.journey.records.map { it.id })
        assertTrue(store.state.value.outbox.isEmpty())
    }

    private class FakeApi : JourneyApi {
        val records = ConcurrentHashMap<String, RecordV>()
        @Volatile var fetch: (suspend () -> JourneyV)? = null
        override suspend fun vehicleJourney() = fetch?.invoke() ?: JourneyV(records = records.values.toList())
        override suspend fun journey() = error("Limited feed must never be used for vehicle sync")
        override suspend fun putRecord(id: String, record: RecordV): RecordV { records[id] = record; return record }
        override suspend fun deleteRecord(id: String) { records.remove(id) }
        override suspend fun createLinkCode(): LinkCodeV = error("unused")
        override suspend fun pollLink(poll: String): LinkPollV = error("unused")
        override suspend fun putGoal(id: String, goal: GoalV): GoalV = error("unused")
        override suspend fun putPlace(goal: String, place: String, body: PlaceV): GoalV = error("unused")
        override suspend fun archiveGoal(id: String): GoalV = error("unused")
        override suspend fun confirmVisit(id: String): GoalV = error("unused")
        override suspend fun photo(id: String): okhttp3.ResponseBody = error("unused")
    }
}
