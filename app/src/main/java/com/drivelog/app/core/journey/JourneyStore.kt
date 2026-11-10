package com.drivelog.app.core.journey

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoreState(
    val journey: JourneyV = JourneyV(),
    val outbox: List<OutboxOp> = emptyList(),
    val lastSyncFailed: Boolean = false,
    val recentPlaces: List<PlaceV> = emptyList(),
    val savedPlaces: List<PlaceV> = emptyList(),
    /** Trip metadata stays in this connection's local file; server journey snapshots do not replace it. */
    val trips: List<TripV> = emptyList(),
    /** Old installations must explicitly consent before automatic server sync resumes. */
    val serverConsentVersion: Int = 0,
    // Held in memory only; persisted separately through TokenVault.
    @Transient val token: String? = null,
)

private const val MAX_RECENT_PLACES = 20

private fun PlaceV.sameLocation(other: PlaceV) = latitude == other.latitude && longitude == other.longitude

/** Most recent first, and a place returns to the top rather than appearing twice. */
fun List<PlaceV>.withRecent(place: PlaceV): List<PlaceV> =
    (listOf(place) + filterNot { it.sameLocation(place) }).take(MAX_RECENT_PLACES)

fun List<PlaceV>.withSavedToggled(place: PlaceV): List<PlaceV> =
    if (any { it.sameLocation(place) }) filterNot { it.sameLocation(place) } else listOf(place) + this

/**
 * Offline-first store: goals, records and queued writes survive process death in one atomically replaced
 * file per connection, written off the main thread. Unlinked history is kept separately and is never
 * uploaded without an explicit import choice. Raw tokens live only in TokenVault, not these files.
 */
class JourneyStore internal constructor(
    filesDir: File,
    private val scope: CoroutineScope,
    private val queueForServer: Boolean,
    private val readToken: () -> String?,
    private val writeToken: (String?) -> Unit,
    private val clientFor: (String?) -> JourneyApi,
) {
    constructor(context: Context, baseUrl: String, scope: CoroutineScope, queueForServer: Boolean = true) : this(
        context.filesDir, scope, queueForServer,
        { TokenVault(context).read() }, { TokenVault(context).write(it) },
        { token -> createJourneyApi(baseUrl) { token } },
    )

    private val guestFile = File(filesDir, "journey-store.json")
    private val profilesDir = File(filesDir, "journey-profiles")
    private var file = guestFile
    private val gson = Gson()
    private val syncLock = Mutex()
    private val snapshots = mutableMapOf<String, StoreState>()
    @Volatile private var session = 0L
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<StoreState> = mutableState
    @Volatile var api: JourneyApi = clientFor(mutableState.value.token)
        private set

    private fun profileFile(token: String) = File(File(profilesDir, journeyProfileId(token)), "store.json")

    /** Raised by every change, and the newest change reached disk at [written]. */
    private var version = 0L
    private val written = MutableStateFlow(WriteResult(0L, ok = true))
    /** Keep writes for every profile: conflation across a connection switch could lose the old profile. */
    private data class PendingWrite(val version: Long, val file: File, val state: StoreState)
    private val writes = Channel<PendingWrite>(Channel.UNLIMITED)
    private val mutableWriteFailed = MutableStateFlow(false)
    /** The last write did not reach disk, usually for want of space. Records stay in memory until one succeeds. */
    val writeFailed: StateFlow<Boolean> = mutableWriteFailed

    private data class WriteResult(val version: Long, val ok: Boolean)

    init {
        // One writer off the main thread. Serialising every record and point used to block the screen on each change.
        scope.launch(Dispatchers.IO) {
            for ((at, destination, next) in writes) {
                val ok = try {
                    destination.parentFile?.mkdirs()
                    val temp = File(destination.parentFile, destination.name + ".tmp")
                    temp.writeText(gson.toJson(next))
                    // Atomic replacement keeps the previous snapshot if the write fails.
                    java.nio.file.Files.move(temp.toPath(), destination.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    true
                } catch (error: IOException) {
                    Log.e(TAG, "store write failed: ${error.javaClass.simpleName}")
                    false
                }
                mutableWriteFailed.value = !ok
                written.value = WriteResult(at, ok)
            }
        }
    }

    /** Polling for remote changes stops in the background; queued writes still go out. */
    @Volatile var foreground = false

    private fun read(source: File): StoreState = snapshots[source.absolutePath] ?: runCatching {
            if (source.exists()) gson.fromJson(source.readText(), StoreState::class.java) else StoreState()
        }.getOrElse {
            Log.w(TAG, "store unreadable: ${it.javaClass.simpleName}")
            StoreState()
        }

    private fun load(): StoreState {
        val token = readToken()?.takeIf { queueForServer }
        val linked = token?.let { profileFile(it) }
        if (linked != null && linked.exists()) {
            val saved = read(linked)
            if (saved.serverConsentVersion == SERVER_CONSENT_VERSION) {
                file = linked
                return saved.copy(token = token)
            }
        }
        // Legacy linked data remains in the original local file; it cannot silently resume uploading.
        return read(guestFile).copy(token = null, outbox = emptyList(), serverConsentVersion = 0)
    }

    private fun StoreState.plusQueued(op: OutboxOp) =
        if (queueForServer && token != null && serverConsentVersion == SERVER_CONSENT_VERSION) outbox + op else outbox

    /** Changes memory at once and queues the file write. Returns the version to wait on with [awaitWritten]. */
    @Synchronized
    private fun update(change: (StoreState) -> StoreState): Long {
        val next = change(mutableState.value)
        mutableState.value = next
        snapshots[file.absolutePath] = next
        version += 1
        writes.trySend(PendingWrite(version, file, next))
        return version
    }

    /** True once [at] or a later change is on disk; false when that write failed. */
    private suspend fun awaitWritten(at: Long): Boolean = written.first { it.version >= at }.ok

    /**
     * Adds a finished drive and waits until it is on disk. Its recording files must stay until this returns true,
     * since they are the only other copy of the drive.
     */
    suspend fun addRecordDurably(record: RecordV): Boolean = awaitWritten(addRecord(record))

    fun startSyncLoop() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = mutableState.value
                if (current.token != null && (foreground || current.outbox.isNotEmpty())) sync()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun setToken(token: String, includeLocalRecords: Boolean = false) {
        check(queueForServer) { "Phone linking is disabled in this build" }
        val local = mutableState.value
        session += 1
        file = profileFile(token)
        val profile = linkHistory(read(file), local, includeLocalRecords)
            .copy(token = token, serverConsentVersion = SERVER_CONSENT_VERSION, lastSyncFailed = false)
        api = clientFor(token)
        writeToken(token)
        update { profile }
        scope.launch(Dispatchers.IO) { sync() }
    }

    /** Keep the old profile on disk, but never expose its records or queue to another connection. */
    @Synchronized
    fun unlink() {
        session += 1
        writeToken(null)
        file = guestFile
        api = clientFor(null)
        update { read(guestFile).copy(token = null, outbox = emptyList(), lastSyncFailed = false, serverConsentVersion = 0) }
    }

    fun addRecentPlace(place: PlaceV) = update { it.copy(recentPlaces = it.recentPlaces.withRecent(place)) }

    fun toggleSavedPlace(place: PlaceV) = update { it.copy(savedPlaces = it.savedPlaces.withSavedToggled(place)) }

    fun addRecord(record: RecordV) = update { state ->
        state.copy(
            journey = state.journey.copy(records = listOf(record) + state.journey.records.filterNot { it.id == record.id }),
            outbox = state.plusQueued(OutboxOp(OutboxOp.RECORD, record.id, record)),
        )
    }

    fun deleteRecord(id: String) = update { state ->
        val remaining = state.journey.records.filterNot { it.id == id }
        state.copy(
            journey = state.journey.copy(records = remaining),
            trips = retainTripRecords(state.trips, remaining),
            outbox = state.copy(outbox = state.outbox.filterNot { it.recordId == id })
                .plusQueued(OutboxOp(OutboxOp.DELETE_RECORD, id)),
        )
    }

    suspend fun saveTripDurably(id: String, title: String, recordIds: List<String>, coverRecordId: String?): TripV? {
        var trip: TripV? = null
        val at = update { state ->
            trip = prepareTrip(id, title, recordIds, coverRecordId, System.currentTimeMillis(), state.trips, state.journey.records)
            trip?.let { saved -> state.copy(trips = listOf(saved) + state.trips.filterNot { it.id == id }) } ?: state
        }
        return trip?.takeIf { awaitWritten(at) }
    }

    suspend fun deleteTripDurably(id: String): Boolean = awaitWritten(update { state ->
        state.copy(trips = state.trips.filterNot { it.id == id })
    })

    /** True only when the server holds everything and the local snapshot is current. */
    suspend fun sync(): Boolean = syncLock.withLock {
        val connection = synchronized(this) { Triple(mutableState.value.token, session, api) }
        val token = connection.first ?: return false
        val activeSession = connection.second
        val client = connection.third // This client's interceptor captures a fixed token.
        fun active() = session == activeSession && mutableState.value.token == token
        try {
            for (op in mutableState.value.outbox) {
                if (!active()) return false
                // Deleting/replacing an item during a request must not remove the next queued operation.
                if (mutableState.value.outbox.none { it === op }) continue
                when (op.type) {
                    OutboxOp.GOAL -> checkNotNull(op.goal).let { client.putGoal(it.id, it) }
                    OutboxOp.PLACE -> client.putPlace(checkNotNull(op.goal).id, checkNotNull(op.place).id, op.place)
                    OutboxOp.RECORD -> client.putRecord(op.recordId, checkNotNull(op.record))
                    OutboxOp.VISIT -> client.confirmVisit(op.recordId)
                    OutboxOp.ARCHIVE -> client.archiveGoal(checkNotNull(op.goal).id)
                    OutboxOp.DELETE_RECORD -> client.deleteRecord(op.recordId)
                }
                update { state -> if (active()) state.copy(outbox = state.outbox.filterNot { it === op }) else state }
            }
            if (!active()) return false
            // Unlike the mobile feed, this endpoint is a complete authoritative snapshot.
            // An older server returns 404: keep local data and report failure, never fall back to 200 items.
            val remote = client.vehicleJourney()
            update { state ->
                if (active()) {
                    val journey = reconcileJourney(remote, state.outbox)
                    state.copy(journey = journey, trips = retainTripRecords(state.trips, journey.records), lastSyncFailed = false)
                } else state
            }
            active() && mutableState.value.outbox.isEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            update { if (active()) it.copy(lastSyncFailed = true) else it }
            false
        }
    }

    private companion object {
        const val TAG = "DriveLogStore"
        const val SYNC_INTERVAL_MS = 15_000L
        const val SERVER_CONSENT_VERSION = 1
    }
}
