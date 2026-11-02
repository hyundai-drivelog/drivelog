package com.drivelog.app.feature.drive

import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelog.app.data.drive.*
import com.drivelog.app.data.sdk.*
import com.google.gson.Gson
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*

data class DriveUiState(
    val screen: String = "diagnostics",
    val busy: Boolean = false,
    val mode: RecordingMode = RecordingMode.MOCK,
    val destination: DriveDestination = demoDestinations.first(),
    val recording: Boolean = false,
    val elapsedSeconds: Long = 0,
    val points: List<GeoPoint> = emptyList(),
    val summary: DriveSession? = null,
    val results: List<DiagnosticResult> = emptyList(),
    val message: String? = null,
    val exportPath: String? = null,
    val template: Int = 0,
    val photoUri: String? = null,
)

class DriveViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(DriveUiState())
        private set
    val environment: String = if (application.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        "AAOS · Pleos 여부는 실제 서비스 응답으로 확인" else "일반 Android · SDK 서비스 미설치 환경"
    private val sdk = PleosRuntimeDataSource(application) { publish(it) }
    private var ticker: Job? = null
    private var startedAt = ""
    private var startedElapsed = 0L
    private val mock = MockVehicleDataSource()
    private val gson = Gson()

    init {
        refreshPermissions()
        listOf("vehicle.init", "navi.init", "location.support").forEach {
            publish(DiagnosticResult(it, ProbeStatus.UNAVAILABLE, "아직 요청하지 않음"))
        }
    }

    private fun publish(result: DiagnosticResult) {
        Log.i("DriveLogSDK", "${result.at} ${result.target} ${result.status} code=${result.code ?: "none"}")
        viewModelScope.launch {
            state = state.copy(results = (state.results.filterNot { it.target == result.target } + result).takeLast(40))
            withContext(Dispatchers.IO) {
                val log = File(getApplication<Application>().filesDir, "sdk-diagnostics.jsonl")
                if (log.exists() && log.length() > 256_000) log.writeText("")
                log.appendText(gson.toJson(result) + "\n")
            }
        }
    }

    fun refreshPermissions() { permissionResults(getApplication()).forEach(::publish) }
    fun go(screen: String) { if (!state.recording) state = state.copy(screen = screen, message = null) }
    fun mode(mode: RecordingMode) {
        if (!state.recording && state.mode != mode) state = state.copy(mode = mode, points = emptyList(),
            elapsedSeconds = 0, summary = null, exportPath = null, message = null)
    }
    fun destination(destination: DriveDestination) { if (!state.recording) state = state.copy(destination = destination) }
    fun template(index: Int) { state = state.copy(template = index, exportPath = null) }
    fun photo(uri: String?) { state = state.copy(photoUri = uri, exportPath = null) }
    fun exported(path: String) { state = state.copy(exportPath = path, message = "PNG 이미지 생성 완료") }
    fun report(message: String) { state = state.copy(message = message) }

    private fun action(target: String, block: suspend () -> Unit) {
        if (state.busy || state.recording) return
        state = state.copy(busy = true, message = null)
        publish(DiagnosticResult("$target.request", ProbeStatus.UNAVAILABLE, "실제 SDK 호출 시작"))
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publish(DiagnosticResult(target, ProbeStatus.ERROR, "호출 예외 · ${error.javaClass.simpleName}", error.javaClass.name)) }
            finally { state = state.copy(busy = false) }
        }
    }

    fun vehicle() = action("vehicle") { sdk.vehicle(); refreshPermissions() }
    fun location() = action("location") { sdk.location(); refreshPermissions() }
    fun navigation(action: NavigationAction) = action("navi.$action") { sdk.navigation(action, state.destination); refreshPermissions() }

    fun start() {
        if (state.recording || state.busy) return
        if (state.mode == RecordingMode.PLEOS) {
            val accepted = sdk.startLocations({ point ->
                viewModelScope.launch {
                    if (state.recording && state.mode == RecordingMode.PLEOS && point.source == RecordingMode.PLEOS &&
                        point.latitude.isFinite() && point.longitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) {
                        val previous = state.points.lastOrNull()
                        val seconds = previous?.let { (point.receivedAtMillis - it.receivedAtMillis) / 1000.0 }
                        val distance = previous?.let { routeDistanceKm(listOf(it, point)) }
                        if (seconds != null && distance != null && (seconds <= 0 || distance / seconds * 3600 > 250)) {
                            publish(DiagnosticResult("location.outlier", ProbeStatus.UNAVAILABLE,
                                "비현실적인 좌표 이동 제외 · 위치 점프 후 새 기록 시작 권장"))
                        } else state = state.copy(points = (state.points + point).takeLast(10_000))
                    }
                }
            }, ::publish)
            if (!accepted) { state = state.copy(message = "실제 위치 수집을 시작할 수 없습니다. Diagnostics를 확인하거나 MOCK 모드를 선택하세요."); return }
        }
        startedAt = Instant.now().toString()
        startedElapsed = SystemClock.elapsedRealtime()
        state = state.copy(recording = true, elapsedSeconds = 0, points = emptyList(), summary = null, exportPath = null, message = null)
        ticker = viewModelScope.launch {
            var step = 0
            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - startedElapsed) / 1000
                val points = if (state.mode == RecordingMode.MOCK) state.points + mock.point(step++, System.currentTimeMillis()) else state.points
                state = state.copy(elapsedSeconds = elapsed, points = points.takeLast(10_000))
                delay(1000)
            }
        }
    }

    fun stop() {
        if (!state.recording) return
        ticker?.cancel(); ticker = null
        sdk.stopLocations()
        val duration = (SystemClock.elapsedRealtime() - startedElapsed) / 1000
        val summary = DriveSession(state.mode, state.destination, startedAt, Instant.now().toString(), duration,
            state.points.toList(), routeDistanceKm(state.points),
            if (state.mode == RecordingMode.MOCK) mock.efficiency else null,
            if (state.mode == RecordingMode.MOCK) "km/L" else null)
        state = state.copy(recording = false, elapsedSeconds = duration, summary = summary,
            message = "기록 종료 · ${summary.mode} · ${summary.points.size}개 좌표")
    }

    override fun onCleared() { ticker?.cancel(); sdk.close(); super.onCleared() }
}
