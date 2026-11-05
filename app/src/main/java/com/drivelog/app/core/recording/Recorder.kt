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
    private val gson = Gson()
    private var writer: BufferedWriter? = null
    private var ticker: Job? = null
    private var startedElapsed = 0L
    private var odometerStart: Float? = null
    private val mutableState = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = mutableState

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
            val lastFix = latest.points.lastOrNull()?.atMillis ?: latest.startedAtMillis
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
        val previous = current.points.lastOrNull()
        var added = 0.0
        if (previous != null) {
            added = haversineKm(previous.latitude, previous.longitude, latitude, longitude)
            val seconds = (now - previous.atMillis) / 1000.0
            // A position jump is a sensor or emulator artefact, not driving. Never count it as distance.
            if (!current.simulated && (seconds <= 0 || added / seconds * 3600 > MAX_SPEED_KMH)) return
        }
        val point = RoutePoint(latitude, longitude, now)
        runCatching {
            writer?.run { append("$latitude,$longitude,$now\n"); flush() }
        }.onFailure { Log.e(TAG, "point write failed: ${it.javaClass.simpleName}") }
        mutableState.value = current.copy(points = current.points + point, distanceKm = current.distanceKm + added)
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

    suspend fun finish(): RecordV? {
        val current = mutableState.value ?: return null
        val end = platform.odometerRaw()
        val start = odometerStart
        val delta = if (start != null && end != null && end >= start) (end - start).toDouble() else null
        val elapsed = elapsedSeconds()
        stop()
        clearFiles()
        val (distance, source) = resolveDistance(current.distanceKm, current.points.size >= 2, delta)
        return toRecord(current.id, current.goalId, current.place, current.startedAtMillis, System.currentTimeMillis(),
            elapsed, current.points, distance, source, RecordStatus.COMPLETE, current.simulated)
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
    }

    private fun clearFiles() {
        metaFile.delete(); pointsFile.delete(); tickFile.delete()
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
        return toRecord(meta.id, meta.goalId, meta.place, meta.startedAtMillis, lastSaved,
            (lastSaved - meta.startedAtMillis).coerceAtLeast(0) / 1000, points, distance, source, RecordStatus.PARTIAL, meta.simulated)
    }

    fun recoveredPlace(): PlaceV? = runCatching { gson.fromJson(metaFile.readText(), Meta::class.java).place }.getOrNull()

    fun clearRecovery() = clearFiles()

    private fun toRecord(id: String, goalId: String?, place: PlaceV, started: Long, ended: Long, elapsed: Long, points: List<RoutePoint>,
                         distance: Double?, source: String, status: String, simulated: Boolean) = RecordV(
        id, goalId, place.id.ifBlank { null }, place.name, started, ended, elapsed, distance, source, status, simulated,
        thin(points).map { listOf(it.latitude, it.longitude, it.atMillis.toDouble()) },
        placeAddress = place.address, destinationLatitude = place.latitude, destinationLongitude = place.longitude,
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
    }
}
