package com.drivelog.app.feature.car

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelog.app.BuildConfig
import com.drivelog.app.DrivelogApp
import com.drivelog.app.core.journey.GoalV
import com.drivelog.app.core.journey.LinkCodeV
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.platform.CarGear
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.core.recording.RecordingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { HOME, GOAL_NEW, GOAL_EDIT_PLACE, PICKER, STOP, VISIT, SYNCING, LINK, DONE, OFFLINE, RECOVERY, COMPLETE, DIAGNOSTICS }

fun NaviPlace.toPlace() = PlaceV("", 0, name, address, latitude, longitude, poiId, poiSubId, poiProvider)
fun PlaceV.toNaviPlace() = NaviPlace(name, address, latitude, longitude, poiId, poiSubId, poiProvider)

/** Labelled sample destinations, used only in debug builds when no navigation app supplies places. */
val simulatedNaviPlaces = listOf(
    NaviPlace("광안리 해수욕장", "부산 수영구", 35.1532, 129.1186),
    NaviPlace("송정 해수욕장", "부산 해운대구", 35.1786, 129.1996),
    NaviPlace("다대포 해수욕장", "부산 사하구", 35.0466, 128.9662),
    NaviPlace("해운대 해수욕장", "부산 해운대구", 35.1587, 129.1604),
)

val goalNamePresets = listOf("올해 가보고 싶은 부산 바다", "올해 가보고 싶은 곳", "다음 드라이브 코스")

class CarViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DrivelogApp
    val store = app.store
    val recorder = app.recorder
    val platform = app.platform

    var screen by mutableStateOf(Screen.HOME)
        private set
    var selectedPlaceId by mutableStateOf<String?>(null)
        private set
    var editingPlaceId by mutableStateOf<String?>(null)
        private set
    var finishedRecord by mutableStateOf<RecordV?>(null)
        private set
    var recoveredRecord by mutableStateOf<RecordV?>(null)
        private set
    var recoveredPlace by mutableStateOf<PlaceV?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var linkCode by mutableStateOf<LinkCodeV?>(null)
        private set
    var linkError by mutableStateOf(false)
        private set
    var syncing by mutableStateOf(false)
        private set

    var goalName by mutableStateOf(goalNamePresets.first())
    val goalPlaces = mutableStateListOf<NaviPlace>()

    /** Debug switch; forced on where FusedLocation is absent, since nothing real can be recorded there. */
    var simulationRequested by mutableStateOf(false)
        private set
    val locationSupported = platform.locationSupported()
    val simulation get() = BuildConfig.DEBUG && (simulationRequested || !locationSupported)
    val recordingAvailable get() = locationSupported || simulation

    private var uxAllowed by mutableStateOf(false)
    private var realGear by mutableStateOf(CarGear.UNKNOWN)
    private var simGear by mutableStateOf(CarGear.PARK)
    private var returnAfterLink = Screen.HOME
    private var linkJob: Job? = null

    val gear: CarGear get() = if (simulation) simGear else realGear

    /**
     * Editing is unlocked only by positive evidence. The platform's UX restrictions always win; a reported
     * gear must then be P. A vehicle that reports no gear falls back to the restrictions alone.
     */
    val parked: Boolean
        get() = when {
            simulation -> simGear == CarGear.PARK
            !uxAllowed -> false
            else -> realGear == CarGear.PARK || realGear == CarGear.UNKNOWN
        }

    init {
        viewModelScope.launch { app.vehicleState.uxAllowed.collectLatest { uxAllowed = it } }
        viewModelScope.launch { app.vehicleState.gear.collectLatest { realGear = it } }
        viewModelScope.launch { app.simulatedGear.collectLatest { simGear = it } }
        if (recorder.hasUnfinishedRecording()) {
            recoveredRecord = recorder.recoverPartial()
            recoveredPlace = recorder.recoveredPlace()
            if (recoveredRecord != null) screen = Screen.RECOVERY
        }
        viewModelScope.launch {
            // Moving into P mid-drive offers the end-of-drive screen, as the driver has just stopped.
            var previous = gear
            combine(app.vehicleState.gear, app.simulatedGear) { real, sim -> if (simulation) sim else real }.collectLatest { current ->
                if (recorder.state.value != null && previous != CarGear.PARK && previous != CarGear.UNKNOWN && current == CarGear.PARK) {
                    screen = Screen.STOP
                    platform.speak("정차했어요. 기록을 종료할 수 있어요.")
                }
                if (current != CarGear.PARK && screen == Screen.STOP) screen = Screen.HOME
                previous = current
            }
        }
    }

    fun onForeground() { platform.refreshNaviPlaces() }

    fun changeSimGear(value: CarGear) { app.simulatedGear.value = value }
    fun toggleSimulation() { if (recorder.state.value == null) simulationRequested = !simulationRequested }

    fun go(target: Screen) { message = null; platform.clearRouteMessage(); screen = target }
    fun home() { finishedRecord = null; editingPlaceId = null; go(Screen.HOME) }

    /** False means this is the top screen, and the activity hides itself rather than finishing. */
    fun goBack(): Boolean {
        if (recorder.state.value != null) {
            if (screen == Screen.STOP) { keepRecording(); return true }
            return false
        }
        when (screen) {
            Screen.HOME, Screen.RECOVERY, Screen.SYNCING -> return false
            Screen.VISIT -> continueAfterRecord()
            Screen.LINK -> leaveLink()
            Screen.GOAL_EDIT_PLACE -> { editingPlaceId = null; go(Screen.PICKER) }
            else -> home()
        }
        return true
    }

    // --- Goal setup: places come from the navigation app, not typed search ------------------------

    /** Picks survive a trip to the navigation app, so places can be added one search at a time. */
    fun startNewGoal() {
        platform.refreshNaviPlaces()
        go(Screen.GOAL_NEW)
    }

    fun toggleGoalPlace(place: NaviPlace) {
        val index = goalPlaces.indexOfFirst { it.latitude == place.latitude && it.longitude == place.longitude }
        if (index >= 0) goalPlaces.removeAt(index) else if (goalPlaces.size < GOAL_PLACE_COUNT) goalPlaces.add(place)
    }

    fun createGoal() {
        if (!parked || goalPlaces.size != GOAL_PLACE_COUNT) return
        store.createGoal(goalName, goalPlaces.map { it.toPlace() })
        goalPlaces.clear()
        home()
        viewModelScope.launch { store.sync() }
    }

    fun editPlace(placeId: String) {
        editingPlaceId = placeId
        platform.refreshNaviPlaces()
        go(Screen.GOAL_EDIT_PLACE)
    }

    fun replaceEditingPlace(place: NaviPlace) {
        val id = editingPlaceId ?: return
        if (!parked) return
        store.replacePlace(id, place.toPlace())
        editingPlaceId = null
        go(Screen.PICKER)
        viewModelScope.launch { store.sync() }
    }

    fun onHomeAction(goal: GoalV) {
        if (goal.achieved) { go(Screen.COMPLETE); return }
        selectedPlaceId = goal.nextPlace?.id
        go(Screen.PICKER)
    }

    fun select(place: PlaceV) { selectedPlaceId = place.id }

    // --- Recording --------------------------------------------------------------------------------

    fun missingLocationPermissions(): List<String> {
        if (simulation) return emptyList()
        val context = getApplication<Application>()
        return platform.locationPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    fun startRecording() {
        if (!parked) return
        val goal = store.state.value.journey.activeGoal ?: return
        val place = goal.places.firstOrNull { it.id == selectedPlaceId } ?: return
        if (!recordingAvailable) { message = "이 차량에서는 위치를 수집할 수 없어 기록할 수 없어요."; return }
        if (!recorder.start(goal.id, place, simulation)) {
            message = "위치 권한을 허용하면 기록할 수 있어요. 경로는 사진 카드를 만드는 데 쓰여요."
            return
        }
        go(Screen.HOME)
        if (!simulation) RecordingService.startIfAllowed(getApplication())
        platform.speak("${place.name} 기록을 시작했어요.")
    }

    /** Explicit tap only: recording never starts guidance, and only one navigation app may hold the route. */
    fun requestRoute() {
        val goal = store.state.value.journey.activeGoal ?: return
        val place = goal.places.firstOrNull { it.id == selectedPlaceId } ?: return
        platform.requestRoute(place.toNaviPlace())
    }

    fun openNavigation() = platform.openNavigation()

    fun askToStop() { if (parked) go(Screen.STOP) }
    fun keepRecording() { go(Screen.HOME) }

    fun endRecording() {
        if (!parked) return
        viewModelScope.launch {
            val record = recorder.finish() ?: return@launch
            RecordingService.stop(getApplication())
            store.addRecord(record)
            finishedRecord = record
            go(Screen.VISIT)
        }
    }

    fun confirmVisit() {
        val record = finishedRecord ?: return
        store.confirmVisit(record)
        val goal = store.state.value.journey.activeGoal
        if (goal?.achieved == true) {
            go(Screen.COMPLETE)
            viewModelScope.launch { store.sync() }
        } else continueAfterRecord()
    }

    /** Outcome of the upload decides what the driver is told; "arrived" is never claimed in advance. */
    fun continueAfterRecord() {
        if (store.state.value.token == null) {
            returnAfterLink = Screen.DONE
            startLink()
            return
        }
        syncing = true
        go(Screen.SYNCING)
        viewModelScope.launch {
            val ok = store.sync()
            syncing = false
            go(if (ok) Screen.DONE else Screen.OFFLINE)
        }
    }

    fun retrySync() = continueAfterRecord()

    fun keepPartial() {
        val record = recoveredRecord ?: return
        store.addRecord(record)
        recorder.clearRecovery()
        recoveredRecord = null
        recoveredPlace = null
        finishedRecord = record
        continueAfterRecord()
    }

    fun deletePartial() {
        recorder.clearRecovery()
        recoveredRecord = null
        recoveredPlace = null
        home()
    }

    // --- Phone link -------------------------------------------------------------------------------

    fun openLink() {
        returnAfterLink = Screen.HOME
        startLink()
    }

    fun unlink() = store.unlink()

    fun startLink() {
        linkJob?.cancel()
        linkCode = null
        linkError = false
        go(Screen.LINK)
        linkJob = viewModelScope.launch {
            val code = runCatching { store.api.createLinkCode() }.getOrNull()
            if (code == null) { linkError = true; return@launch }
            linkCode = code
            while (isActive) {
                delay(2_000)
                val poll = runCatching { store.api.pollLink(code.pollToken) }.getOrNull() ?: continue
                if (poll.linked && poll.accessToken != null) {
                    store.setToken(poll.accessToken)
                    linkCode = null
                    if (returnAfterLink == Screen.DONE) continueAfterRecord() else home()
                    return@launch
                }
                if (poll.expired) return@launch // The screen shows 00:00 and offers a new code.
            }
        }
    }

    fun leaveLink() {
        linkJob?.cancel()
        linkCode = null
        if (returnAfterLink == Screen.DONE) go(Screen.OFFLINE) else home()
    }

    private companion object { const val GOAL_PLACE_COUNT = 3 }
}
