package com.drivelog.app.core.journey

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.UUID
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
    // Held in memory only; persisted separately through TokenVault.
    @Transient val token: String? = null,
)

/** Pure rule shared by online and offline paths: one place counts once, however often it is confirmed. */
fun JourneyV.withVisit(record: RecordV, now: Long): JourneyV {
    val goal = activeGoal ?: return this
    if (goal.id != record.goalId) return this
    val places = goal.places.map { place ->
        if (place.id == record.placeId && place.visitRecordId == null)
            place.copy(visitRecordId = record.id, visitedAtMillis = now)
        else place
    }
    val updated = goal.copy(places = places)
    return copy(activeGoal = updated.copy(status = if (updated.achieved) "ACHIEVED" else goal.status))
}

/** Only an unvisited place of the active goal can change; a confirmed visit stays attached to its place. */
fun JourneyV.withReplacedPlace(placeId: String, replacement: PlaceV): JourneyV {
    val goal = activeGoal ?: return this
    return copy(activeGoal = goal.copy(places = goal.places.map { place ->
        if (place.id == placeId && place.visitRecordId == null) replacement.copy(id = place.id, position = place.position) else place
    }))
}

/**
 * Offline-first store: goals, records and queued writes survive process death in one atomically replaced
 * file, and the car works fully before a phone is ever linked. The Pleos Sync SDK is not available and the
 * mobile web cannot use an Android SDK, so sync goes through the service server; this class is the only
 * place that would change to adopt it.
 */
class JourneyStore(context: Context, baseUrl: String, private val scope: CoroutineScope) {
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
        return saved.copy(token = vault.read())
    }

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

    fun createGoal(name: String, places: List<PlaceV>): GoalV {
        val goal = GoalV(UUID.randomUUID().toString(), name, "ACTIVE", places.mapIndexed { index, place ->
            place.copy(id = UUID.randomUUID().toString(), position = index)
        })
        update { state ->
            state.copy(journey = state.journey.copy(activeGoal = goal), outbox = state.outbox + OutboxOp(OutboxOp.GOAL, goal = goal))
        }
        return goal
    }

    fun replacePlace(placeId: String, replacement: PlaceV) = update { state ->
        val goalId = state.journey.activeGoal?.id
        state.copy(
            journey = state.journey.withReplacedPlace(placeId, replacement),
            outbox = state.outbox + OutboxOp(OutboxOp.PLACE, goal = state.journey.activeGoal?.takeIf { it.id == goalId },
                place = replacement.copy(id = placeId)),
        )
    }

    fun addRecord(record: RecordV) = update { state ->
        state.copy(
            journey = state.journey.copy(records = listOf(record) + state.journey.records.filterNot { it.id == record.id }),
            outbox = state.outbox + OutboxOp(OutboxOp.RECORD, record.id, record),
        )
    }

    fun confirmVisit(record: RecordV) = update { state ->
        state.copy(
            journey = state.journey.withVisit(record, System.currentTimeMillis()),
            outbox = state.outbox + OutboxOp(OutboxOp.VISIT, record.id),
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
