package com.drivelog.app.core.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.RecordStatus
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.platform.CarGear
import com.drivelog.app.core.platform.CarPlatform
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RoutePoint(val latitude: Double, val longitude: Double, val atMillis: Long)

/**
 * The first [size] points of a list that only ever grows. Publishing a drive costs nothing however long it gets,
 * where copying the whole route for every fix grew with the square of its length. Two views are equal when they
 * look at the same list up to the same point, so a new fix is still a change.
 */
internal class GrowingPoints(private val backing: ArrayList<RoutePoint>, override val size: Int) : AbstractList<RoutePoint>() {
    override fun get(index: Int): RoutePoint {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
        return backing[index]
    }
    override fun equals(other: Any?) = other is GrowingPoints && other.backing === backing && other.size == size
    override fun hashCode() = System.identityHashCode(backing) * 31 + size
}

data class Recording(
    val id: String,
    val goalId: String?,
    val place: PlaceV,
    val startedAtMillis: Long,
    /** Published when the shown minute changes, so read the clock rather than this for an exact duration. */
    val elapsedSeconds: Long = 0,
    val points: List<RoutePoint> = emptyList(),
    val distanceKm: Double = 0.0,
    val simulated: Boolean,
    val arrived: Boolean = false,
    val locationStale: Boolean = false,
    /** A point could not be written to disk, so a crash now would lose more than the last tick. Shown once parked. */
    val storageFailed: Boolean = false,
    /** Set while a break the driver marked is running; it ends when the car leaves P. */
    val restingSinceMillis: Long? = null,
)

fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 6371.0088 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Distance from where the drive ended to the target, or null when the end position is unknown. */
fun Recording.kmToPlace(): Double? = points.lastOrNull()?.let { haversineKm(it.latitude, it.longitude, place.latitude, place.longitude) }

const val VISIT_CANDIDATE_RADIUS_KM = 1.0

/**
 * The odometer's unit and session baseline are unverified, so a vehicle reading is only trusted when it
 * agrees with the GPS track. Everything else is reported, and labelled, as a location-based estimate.
 */
fun resolveDistance(gpsKm: Double, hasRoute: Boolean, odometerDelta: Double?): Pair<Double?, String> {
    if (!hasRoute) return null to DistanceSource.NONE
    if (odometerDelta != null && gpsKm >= 0.5 && abs(odometerDelta - gpsKm) / gpsKm <= 0.25)
        return odometerDelta to DistanceSource.VEHICLE
    return gpsKm to DistanceSource.GPS
}

/**
 * Owns the drive being recorded, outside any Activity. Every point is flushed to disk as it arrives, so an
 * app or vehicle shutdown loses at most the last tick and the remainder can be recovered as a partial record.
 */
class Recorder(context: Context, private val platform: CarPlatform, private val scope: CoroutineScope, private val simulatedGear: () -> CarGear) {
    private val directory = File(context.filesDir, "recording").apply { mkdirs() }
    private val metaFile = File(directory, "active.json")
    private val pointsFile = File(directory, "points.csv")
    private val tickFile = File(directory, "tick")
    /** Breaks as "start,end" lines; a break still running has no end yet. */
    private val restsFile = File(directory, "rests.csv")
    private val gson = Gson()
    private var writer: BufferedWriter? = null
    private var ticker: Job? = null
    private var startedElapsed = 0L
    private var odometerStart: Float? = null
    /** Every accepted point of the drive in progress. Only appended to, and replaced by a new list for each drive. */
    private var points = ArrayList<RoutePoint>()
    private var distanceKm = 0.0
    private val rests = ArrayList<List<Long>>()
    private val mutableState = MutableStateFlow<Recording?>(null)
    /**
     * The drive as shown. A fix is published when the count, the distance to one decimal or the write status
     * changes; while driving the screen shows nothing finer. Use [current] where the exact figures matter.
     */
    val state: StateFlow<Recording?> = mutableState

    /** The drive with every point and the exact distance so far, without publishing it. */
    fun current(): Recording? = mutableState.value?.copy(points = GrowingPoints(points, points.size), distanceKm = distanceKm)

    /** Publishes the exact drive, for screens shown once parked that draw the route or the distance left. */
    fun publish() { current()?.let { mutableState.value = it } }

    private data class Meta(val id: String, val goalId: String?, val place: PlaceV, val startedAtMillis: Long, val simulated: Boolean)

    fun start(goalId: String?, place: PlaceV, simulated: Boolean): Boolean {
        if (mutableState.value != null) return false
        val now = System.currentTimeMillis()
        val recording = Recording(UUID.randomUUID().toString(), goalId, place, now, simulated = simulated)
        if (!simulated) {
            if (!startLocationUpdates()) return false
            platform.watchArrival(place.latitude, place.longitude, 500f) { markArrived() }
        }
        metaFile.writeText(gson.toJson(Meta(recording.id, goalId, place, now, simulated)))
        pointsFile.writeText("")
        tickFile.writeText(now.toString())
        writer = pointsFile.bufferedWriter()
        points = ArrayList()
        distanceKm = 0.0
        rests.clear()
        restsFile.delete()
        startedElapsed = SystemClock.elapsedRealtime()
        mutableState.value = recording
        scope.launch { odometerStart = platform.odometerRaw() }
        ticker = scope.launch { tick(simulated) }
        return true
    }

    private fun startLocationUpdates() = platform.startLocation(
        onPoint = { latitude, longitude -> scope.launch { addPoint(latitude, longitude) } },
        onError = { Log.w(TAG, "location error: $it") },
    )

    private fun elapsedSeconds() = (SystemClock.elapsedRealtime() - startedElapsed) / 1000

    private suspend fun tick(simulated: Boolean) {
        var step = 0
        var lastSaved = 0L
        var lastRestart = 0L
        while (scope.isActive) {
            val elapsed = elapsedSeconds()
            val current = mutableState.value ?: return
            if (simulated && simulatedGear() != CarGear.PARK && step <= SIM_STEPS) {
                val (latitude, longitude) = simulatedPoint(current.place, step++)
                addPoint(latitude, longitude)
            }
            val latest = mutableState.value ?: return
            val lastFix = points.lastOrNull()?.atMillis ?: latest.startedAtMillis
            val stale = !simulated && System.currentTimeMillis() - lastFix > STALE_AFTER_MS
            // Time is shown in whole minutes, so a second passing is no reason to redraw while driving.
            if (latest.elapsedSeconds / 60 != elapsed / 60 || latest.locationStale != stale) {
                mutableState.value = latest.copy(elapsedSeconds = elapsed, locationStale = stale)
            }
            // A location error is never followed by another fix on its own, so ask again while none arrive.
            if (stale && System.currentTimeMillis() - maxOf(lastFix, lastRestart) > STALE_AFTER_MS) {
                lastRestart = System.currentTimeMillis()
                if (!startLocationUpdates()) Log.w(TAG, "location restart refused")
            }
            if (elapsed - lastSaved >= 5) {
                lastSaved = elapsed
                runCatching { tickFile.writeText(System.currentTimeMillis().toString()) }
            }
            delay(1000)
        }
    }

    private fun addPoint(latitude: Double, longitude: Double) {
        val current = mutableState.value ?: return
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        val now = System.currentTimeMillis()
        val previous = points.lastOrNull()
        var added = 0.0
        if (previous != null) {
            added = haversineKm(previous.latitude, previous.longitude, latitude, longitude)
            val seconds = (now - previous.atMillis) / 1000.0
            // A position jump is a sensor or emulator artefact, not driving. Never count it as distance.
            if (!current.simulated && (seconds <= 0 || added / seconds * 3600 > MAX_SPEED_KMH)) return
        }
        val written = runCatching {
            writer?.run { append("$latitude,$longitude,$now\n"); flush() }
        }.onFailure { Log.e(TAG, "point write failed: ${it.javaClass.simpleName}") }.isSuccess
        points.add(RoutePoint(latitude, longitude, now))
        distanceKm += added
        val storageFailed = current.storageFailed || !written
        if (shouldPublish(current, storageFailed)) {
            mutableState.value = current.copy(points = GrowingPoints(points, points.size), distanceKm = distanceKm, storageFailed = storageFailed)
        }
    }

    /** The first two points make a route; after that only the shown tenth of a kilometre or a failed write. */
    private fun shouldPublish(shown: Recording, storageFailed: Boolean) =
        shown.points.size < 2 || storageFailed != shown.storageFailed || tenths(shown.distanceKm) != tenths(distanceKm)

    /** Rounded as the screen shows it ("%.1f"), so the figure on screen is never a tenth behind. */
    private fun tenths(km: Double) = Math.round(km * 10)

    /** The driver chose a break on the stop screen. Recording goes on; the time is marked so it reads as a break. */
    fun startRest() {
        val current = mutableState.value ?: return
        if (current.restingSinceMillis != null) return
        val now = System.currentTimeMillis()
        runCatching { restsFile.appendText("$now,") }
        mutableState.value = current.copy(restingSinceMillis = now)
    }

    /** Called when the car leaves P, and on finishing. Breaks under a minute were only a pause and are dropped. */
    fun endRest() {
        val current = mutableState.value ?: return
        val since = current.restingSinceMillis ?: return
        val now = System.currentTimeMillis()
        if (now - since >= MIN_REST_MS) rests.add(listOf(since, now))
        runCatching { restsFile.writeText(rests.joinToString("") { "${it[0]},${it[1]}\n" }) }
        mutableState.value = current.copy(restingSinceMillis = null)
    }

    private fun markArrived() {
        val current = mutableState.value ?: return
        if (current.arrived) return
        mutableState.value = current.copy(arrived = true)
        platform.speak("목적지 근처에 도착했어요. 정차 후 기록을 종료할 수 있어요.")
    }

    private fun simulatedPoint(place: PlaceV, step: Int): Pair<Double, Double> {
        val t = step.toDouble() / SIM_STEPS
        val remaining = 1 - t
        return (place.latitude - 0.075 * remaining + 0.012 * sin(t * PI * 3)) to
            (place.longitude - 0.095 * remaining - 0.010 * sin(t * PI * 2))
    }

    /**
     * Ends the drive and returns its record. The files on disk stay until [clearRecovery] is called once the record
     * itself is safely stored, so a crash or a full disk in between leaves the drive recoverable instead of lost.
     */
    suspend fun finish(): RecordV? {
        endRest()
        val current = current() ?: return null
        val end = platform.odometerRaw()
        val start = odometerStart
        val delta = if (start != null && end != null && end >= start) (end - start).toDouble() else null
        val elapsed = elapsedSeconds()
        stop()
        val (distance, source) = resolveDistance(current.distanceKm, current.points.size >= 2, delta)
        return toRecord(current.id, current.goalId, current.place, current.startedAtMillis, System.currentTimeMillis(),
            elapsed, current.points, distance, source, RecordStatus.COMPLETE, current.simulated, finishedRests)
    }

    /** Ends the recording and leaves nothing behind, neither a record nor anything to recover. */
    fun discard() {
        stop()
        clearFiles()
    }

    private fun stop() {
        ticker?.cancel(); ticker = null
        platform.stopLocation()
        platform.clearArrival()
        runCatching { writer?.close() }
        writer = null
        mutableState.value = null
        points = ArrayList()
        distanceKm = 0.0
        finishedRests = rests.toList()
        rests.clear()
    }

    /** The breaks of the drive [stop] just ended, kept for the record it returns. */
    private var finishedRests: List<List<Long>> = emptyList()

    private fun clearFiles() {
        metaFile.delete(); pointsFile.delete(); tickFile.delete(); restsFile.delete()
    }

    fun hasUnfinishedRecording() = mutableState.value == null && metaFile.exists()

    /** Rebuilds what reached the disk before an abnormal exit. A torn final line is skipped, not guessed. */
    fun recoverPartial(): RecordV? {
        if (!hasUnfinishedRecording()) return null
        val meta = runCatching { gson.fromJson(metaFile.readText(), Meta::class.java) }.getOrNull() ?: run { clearFiles(); return null }
        val points = runCatching {
            pointsFile.readLines().mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size != 3) return@mapNotNull null
                val latitude = parts[0].toDoubleOrNull()
                val longitude = parts[1].toDoubleOrNull()
                val at = parts[2].toLongOrNull()
                if (latitude == null || longitude == null || at == null) null else RoutePoint(latitude, longitude, at)
            }
        }.getOrDefault(emptyList())
        val lastSaved = maxOf(
            runCatching { tickFile.readText().trim().toLong() }.getOrDefault(meta.startedAtMillis),
            points.lastOrNull()?.atMillis ?: meta.startedAtMillis,
        )
        val km = points.zipWithNext().sumOf { (a, b) -> haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) }
        val (distance, source) = resolveDistance(km, points.size >= 2, null)
        // A break still running when the app stopped ends where the saved drive ends.
        val rests = runCatching {
            restsFile.readLines().mapNotNull { line ->
                val start = line.substringBefore(',').toLongOrNull() ?: return@mapNotNull null
                val end = line.substringAfter(',', "").toLongOrNull() ?: lastSaved
                if (end - start >= MIN_REST_MS) listOf(start, end) else null
            }
        }.getOrDefault(emptyList())
        return toRecord(meta.id, meta.goalId, meta.place, meta.startedAtMillis, lastSaved,
            (lastSaved - meta.startedAtMillis).coerceAtLeast(0) / 1000, points, distance, source, RecordStatus.PARTIAL, meta.simulated, rests)
    }

    fun recoveredPlace(): PlaceV? = runCatching { gson.fromJson(metaFile.readText(), Meta::class.java).place }.getOrNull()

    fun clearRecovery() = clearFiles()

    private fun toRecord(id: String, goalId: String?, place: PlaceV, started: Long, ended: Long, elapsed: Long, points: List<RoutePoint>,
                         distance: Double?, source: String, status: String, simulated: Boolean, rests: List<List<Long>>) = RecordV(
        id, goalId, place.id.ifBlank { null }, place.name, started, ended, elapsed, distance, source, status, simulated,
        thin(points).map { listOf(it.latitude, it.longitude, it.atMillis.toDouble()) },
        placeAddress = place.address, destinationLatitude = place.latitude, destinationLongitude = place.longitude,
        rests = rests.ifEmpty { null },
    )

    /** Keeps ends and recording gaps; the server caps a route at 20,000 points. */
    private fun thin(points: List<RoutePoint>): List<RoutePoint> {
        if (points.size <= MAX_ROUTE_POINTS) return points
        val stride = points.size / MAX_ROUTE_POINTS + 1
        return points.filterIndexed { index, point ->
            index % stride == 0 || index == points.lastIndex ||
                (index > 0 && point.atMillis - points[index - 1].atMillis > STALE_AFTER_MS) ||
                (index < points.lastIndex && points[index + 1].atMillis - point.atMillis > STALE_AFTER_MS)
        }
    }

    private companion object {
        const val TAG = "DriveLogRecorder"
        const val SIM_STEPS = 90
        const val MAX_SPEED_KMH = 250
        const val STALE_AFTER_MS = 60_000L
        const val MAX_ROUTE_POINTS = 18_000
        const val MIN_REST_MS = 60_000L
    }
}
