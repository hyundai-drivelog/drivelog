package com.drivelog.app.core.journey

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

data class StoreState(
    val journey: JourneyV = JourneyV(),
    val outbox: List<OutboxOp> = emptyList(),
    val lastSyncFailed: Boolean = false,
    val recentPlaces: List<PlaceV> = emptyList(),
    val savedPlaces: List<PlaceV> = emptyList(),
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
 * file, and the car works fully before a phone is ever linked. The Pleos Sync SDK is not available and the
 * mobile web cannot use an Android SDK, so sync goes through the service server; this class is the only
 * place that would change to adopt it.
 */
class JourneyStore(
    context: Context, baseUrl: String, private val scope: CoroutineScope,
    /** False in a build with no phone link: nothing could ever send the queue, so each record would copy into it forever. */
    private val queueForServer: Boolean = true,
) {
    private val file = File(context.filesDir, "journey-store.json")
    private val vault = TokenVault(context)
    private val gson = Gson()
    private val syncLock = Mutex()
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<StoreState> = mutableState
    val api: JourneyApi = createJourneyApi(baseUrl) { mutableState.value.token }

    /** Polling for remote changes stops in the background; queued writes still go out. */
    @Volatile var foreground = false

    private fun load(): StoreState {
        val saved = runCatching {
            if (file.exists()) gson.fromJson(file.readText(), StoreState::class.java) else StoreState()
        }.getOrElse {
            Log.w(TAG, "store unreadable: ${it.javaClass.simpleName}")
            StoreState()
        }
        // Earlier builds queued every record even without a link. Dropping that queue leaves the records themselves.
        return saved.copy(token = vault.read(), outbox = if (queueForServer) saved.outbox else emptyList())
    }

    private fun List<OutboxOp>.plusQueued(op: OutboxOp) = if (queueForServer) this + op else this

    @Synchronized
    private fun update(change: (StoreState) -> StoreState) {
        val next = change(mutableState.value)
        mutableState.value = next
        try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(gson.toJson(next))
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        } catch (error: IOException) {
            Log.e(TAG, "store write failed: ${error.javaClass.simpleName}")
        }
    }

    fun startSyncLoop() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = mutableState.value
                if (current.token != null && (foreground || current.outbox.isNotEmpty())) sync()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    fun setToken(token: String) {
        vault.write(token)
        update { it.copy(token = token) }
        scope.launch(Dispatchers.IO) { sync() }
    }

    /** Disconnects this car. Local data stays, so a different phone or driver can link afterwards. */
    fun unlink() {
        vault.write(null)
        update { it.copy(token = null, lastSyncFailed = false) }
    }

    fun addRecentPlace(place: PlaceV) = update { it.copy(recentPlaces = it.recentPlaces.withRecent(place)) }

    fun toggleSavedPlace(place: PlaceV) = update { it.copy(savedPlaces = it.savedPlaces.withSavedToggled(place)) }

    fun addRecord(record: RecordV) = update { state ->
        state.copy(
            journey = state.journey.copy(records = listOf(record) + state.journey.records.filterNot { it.id == record.id }),
            outbox = state.outbox.plusQueued(OutboxOp(OutboxOp.RECORD, record.id, record)),
        )
    }

    fun deleteRecord(id: String) = update { state ->
        state.copy(
            journey = state.journey.copy(records = state.journey.records.filterNot { it.id == id }),
            outbox = state.outbox.filterNot { it.recordId == id }.plusQueued(OutboxOp(OutboxOp.DELETE_RECORD, id)),
        )
    }

    /** True only when the server holds everything and the local snapshot is current. */
    suspend fun sync(): Boolean = syncLock.withLock {
        val token = mutableState.value.token ?: return false
        try {
            for (op in mutableState.value.outbox) {
                try {
                    when (op.type) {
                        OutboxOp.GOAL -> checkNotNull(op.goal).let { api.putGoal(it.id, it) }
                        OutboxOp.PLACE -> api.putPlace(checkNotNull(op.goal).id, checkNotNull(op.place).id, op.place)
                        OutboxOp.RECORD -> api.putRecord(op.recordId, checkNotNull(op.record))
                        OutboxOp.VISIT -> api.confirmVisit(op.recordId)
                        OutboxOp.ARCHIVE -> api.archiveGoal(checkNotNull(op.goal).id)
                        OutboxOp.DELETE_RECORD -> api.deleteRecord(op.recordId)
                    }
                } catch (error: HttpException) {
                    // A definite rejection (e.g. the goal was deleted on the phone) will never succeed on
                    // retry and must not block later writes. Auth and server failures do stay queued.
                    val code = error.code()
                    if (code == 401 || code == 408 || code == 429 || code >= 500) throw error
                    Log.w(TAG, "dropping rejected ${op.type}: HTTP $code")
                }
                update { state -> state.copy(outbox = state.outbox.drop(1)) }
            }
            val remote = api.journey()
            update { it.copy(journey = remote, lastSyncFailed = false) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is HttpException && error.code() == 401 && mutableState.value.token == token) unlink()
            update { it.copy(lastSyncFailed = true) }
            false
        }
    }

    private companion object {
        const val TAG = "DriveLogStore"
        const val SYNC_INTERVAL_MS = 15_000L
    }
}
