package com.drivelog.app.feature.car

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelog.app.BuildConfig
import com.drivelog.app.DrivelogApp
import com.drivelog.app.core.journey.LinkCodeV
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.journey.TripV
import com.drivelog.app.core.journey.TRIP_TITLE_LIMIT
import java.util.UUID
import com.drivelog.app.core.platform.CarGear
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.core.platform.RouteEnd
import com.drivelog.app.core.recording.RecordingService
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** RECORDING and LOCKED follow from vehicle state (see routeFor); nothing navigates to them. */
enum class Screen { WELCOME, HOME, DESTINATION, PLACE, HISTORY, RECORD, TRIP, TRIP_EDITOR, TRIP_PICKER, STOP, SYNCING, LINK, LINK_CONSENT, PRIVACY, DATA_CONSENT, DONE, OFFLINE, RECOVERY, DIAGNOSTICS, RECORDING, LOCKED, PHONE_PREVIEW_LINK, PHONE_PREVIEW_DONE, COMMUNITY, COURSE_DETAIL, COURSE_SHARE, CARD_QR }

private const val PREFS = "drivelog"
private const val PREF_WELCOMED = "welcomed"
private const val PHONE_PREVIEW_CODE_MS = 5 * 60 * 1000L
private const val NAVI_RETRY_COUNT = 6
private const val NAVI_RETRY_MS = 10_000L

/** A code that connects to nothing, for the phone screens' preview. */
fun previewLinkCode(random: Random = Random.Default) = random.nextInt(100_000, 1_000_000).toString()

fun NaviPlace.toPlace() = PlaceV("", 0, name, address, latitude, longitude, poiId, poiSubId, poiProvider)
fun PlaceV.toNaviPlace() = NaviPlace(name, address, latitude, longitude, poiId, poiSubId, poiProvider)

/** Labelled sample destinations, used only in debug builds when no navigation app supplies places. */
val simulatedNaviPlaces = listOf(
    NaviPlace("광안리 해수욕장", "부산 수영구", 35.1532, 129.1186),
    NaviPlace("송정 해수욕장", "부산 해운대구", 35.1786, 129.1996),
    NaviPlace("다대포 해수욕장", "부산 사하구", 35.0466, 128.9662),
)

/** Unset destinations and bookmark slots arrive without a name or coordinates; they are not places. */
fun NaviPlace.isUsablePlace() = name.isNotBlank() && (latitude != 0.0 || longitude != 0.0)
fun NaviPlace.sameLocation(other: NaviPlace) = latitude == other.latitude && longitude == other.longitude

/** Earlier lists win, so this app's own history keeps its order ahead of what the navigation app reports. */
fun mergePlaces(vararg lists: List<NaviPlace>): List<NaviPlace> =
    // The navigation app reports slightly different coordinates for one place across its lists, so names count too.
    lists.flatMap { it }.filter { it.isUsablePlace() }.distinctBy { it.latitude to it.longitude }.distinctBy { it.name }

/**
 * What the end of guidance does to a recording. Saving waits for P: the driver may keep going after ending
 * guidance, and the rest of the drive still belongs to the record. Nothing changes on screen while moving.
 */
enum class RouteEndAction { NONE, ANNOUNCE, SAVE }

fun routeEndAction(end: RouteEnd?, recording: Boolean, parked: Boolean) = when {
    end == null || !recording -> RouteEndAction.NONE
    parked -> RouteEndAction.SAVE
    else -> RouteEndAction.ANNOUNCE
}

/**
 * What is said when guidance has ended the drive and it is stored: one sentence with the place, distance and time.
 * A simulated or barely moved drive has no figures worth saying.
 */
fun savedSentence(record: RecordV): String {
    val km = record.distanceKm
    if (record.simulated || km == null || barelyMoved(km, record.route.size >= 2)) return "드라이브를 저장했어요."
    return "${shortName(record.placeName)}까지 ${distanceText(km)}킬로미터, ${elapsedText(record.elapsedSeconds)} 달렸어요. 드라이브를 저장했어요."
}

/** True once the navigation app holds a destination the driver did not already have when the search began. */
fun isNewDestination(current: NaviPlace?, before: NaviPlace?) =
    current != null && current.isUsablePlace() && (before == null || !current.sameLocation(before))

class CarViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DrivelogApp
    val store = app.store
    val recorder = app.recorder
    val platform = app.platform
    val routeMaps = app.routeMaps
    private var mapJob: Job? = null

    var screen by mutableStateOf(Screen.HOME)
        private set
    var selectedPlace by mutableStateOf<NaviPlace?>(null)
        private set
    var openedRecordId by mutableStateOf<String?>(null)
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
    var includeLinkHistory by mutableStateOf(false)
        private set
    private var linkConsented = false
    private var linkRequest = 0L
    fun toggleLinkHistory() { includeLinkHistory = !includeLinkHistory }
    fun agreeToLink() {
        if (!parked || screen != Screen.LINK_CONSENT) return
        linkConsented = true
        startLink()
    }
    var syncing by mutableStateOf(false)
        private set
    var locationRefused by mutableStateOf(false)
        private set

    /** What just happened, said once at the foot of the screen (Pleos Toast). Shown only while parked. */
    var toast by mutableStateOf<CarToast?>(null)
        private set
    fun toastShown(shown: CarToast) { if (toast?.id == shown.id) toast = null }
    private fun say(text: String) { toast = CarToast(text) }

    /** The drive is being written out: the save button shows the guide's Loading state until it is done. */
    var saving by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
    var currentLocation by mutableStateOf<MapPoint?>(null)
        private set
    private var awaitingNavi by mutableStateOf(false)
    private var destinationBeforeSearch: NaviPlace? = null

    /** Debug switch; forced on where FusedLocation is absent, since nothing real can be recorded there. */
    var simulationRequested by mutableStateOf(false)
        private set
    var locationSupported by mutableStateOf(platform.locationSupported())
        private set
    val simulation get() = BuildConfig.DEBUG && (simulationRequested || !locationSupported)
    val recordingAvailable get() = locationSupported || simulation

    private var uxAllowed by mutableStateOf(false)
    private var realGear by mutableStateOf(CarGear.UNKNOWN)
    private var simGear by mutableStateOf(CarGear.PARK)
    private var returnAfterLink = Screen.HOME
    private var linkJob: Job? = null
    private var naviRetry: Job? = null
    /** The way back from the navigation app is said once per session, the first time its search is opened. */
    private var returnHintSpoken = false

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
        // A first start opens on the introduction; a drive left to recover comes first.
        if (screen == Screen.HOME && !prefs.getBoolean(PREF_WELCOMED, false)) screen = Screen.WELCOME
        // The process is frozen behind the navigation app, so this usually fires when the driver comes back.
        viewModelScope.launch {
            platform.navi.collect { state ->
                val destination = state.currentDestination
                if (awaitingNavi && destination != null && isNewDestination(destination, destinationBeforeSearch)) selectPlace(destination)
            }
        }
        viewModelScope.launch {
            platform.speech.collect { state ->
                val heard = state.heard ?: return@collect
                platform.clearSpeech()
                // Only fills the field: a misheard phrase must not take over the screen with a navigation search.
                searchQuery = heard
            }
        }
        viewModelScope.launch {
            snapshotFlow { parked }.collect { isParked ->
                if (isParked) fillRouteMaps() else {
                    platform.stopListening()
                    mapJob?.cancel() // Nothing is requested from the map service while moving.
                }
            }
        }
        viewModelScope.launch {
            // Moving into P mid-drive offers the end-of-drive screen, as the driver has just stopped.
            var previous = gear
            combine(app.vehicleState.gear, app.simulatedGear) { real, sim -> if (simulation) sim else real }.collectLatest { current ->
                // When guidance has already ended, reaching P saves the drive instead of asking.
                if (recorder.state.value != null && platform.navi.value.routeEnd == null && previous != CarGear.PARK && previous != CarGear.UNKNOWN && current == CarGear.PARK) {
                    recorder.publish()
                    screen = Screen.STOP
                    platform.speak("정차했어요. 기록을 종료할 수 있어요.")
                }
                if (current != CarGear.PARK && screen == Screen.STOP) screen = Screen.HOME
                // Setting off again ends a break the driver marked.
                if (current != CarGear.PARK && current != CarGear.UNKNOWN) recorder.endRest()
                previous = current
            }
        }
        viewModelScope.launch {
            combine(platform.navi, recorder.state, snapshotFlow { parked }) { navi, recording, isParked ->
                routeEndAction(navi.routeEnd, recording != null, isParked)
            }.distinctUntilChanged().collect { action ->
                when (action) {
                    RouteEndAction.SAVE -> {
                        platform.clearRouteEnd()
                        val current = recorder.current()
                        // Guidance switched off before setting out says nothing about a drive worth keeping.
                        if (current != null && barelyMoved(current.distanceKm, current.points.size >= 2)) {
                            recorder.publish()
                            screen = Screen.STOP
                            platform.speak("거의 움직이지 않았어요. 저장할지 골라 주세요.")
                        } else {
                            // One sentence for the event, with the figures, said once the drive is safely stored.
                            endRecording(announce = true)
                        }
                    }
                    RouteEndAction.ANNOUNCE -> platform.speak("안내가 끝났어요. 정차하면 드라이브를 저장해요.")
                    RouteEndAction.NONE -> {}
                }
            }
        }
    }

    /**
     * The location service can come up after this app after a boot. Asked again rather than trusting the first
     * answer, but never mid-drive, where a change would flip what the recording is.
     */
    private fun recheckLocationSupport() {
        if (!locationSupported && recorder.state.value == null) locationSupported = platform.locationSupported()
    }

    fun onForeground() {
        recheckLocationSupport()
        platform.refreshNaviPlaces()
        refreshLocation()
        fillRouteMaps()
        retryNaviConnection()
    }

    /**
     * The navigation and speech services can come up after this app, fail to start, or die later. Without navigation
     * a first-time driver cannot search at all, and without speech there is no spoken cue. Tries for a minute each
     * time the app is on screen, since a vehicle without a service never connects it.
     */
    private fun retryNaviConnection() {
        naviRetry?.cancel()
        naviRetry = viewModelScope.launch {
            repeat(NAVI_RETRY_COUNT) {
                if (platform.connectionsComplete()) return@launch
                platform.connect()
                delay(NAVI_RETRY_MS)
            }
        }
    }

    /** Parked and not recording only. A failure simply leaves the map-free card until the next attempt. */
    private fun fillRouteMaps() {
        if (!dataConsented || !parked || recorder.state.value != null || mapJob?.isActive == true) return
        mapJob = viewModelScope.launch { routeMaps.fill(store.state.value.journey.records) }
    }

    /** The map's position request must not outlive the screen when no fix has arrived yet. */
    fun onBackground() {
        naviRetry?.cancel()
        if (recorder.state.value == null) platform.stopLocation()
    }

    fun changeSimGear(value: CarGear) { app.simulatedGear.value = value }
    fun toggleSimulation() { if (recorder.state.value == null) simulationRequested = !simulationRequested }

    fun go(target: Screen) {
        message = null
        locationRefused = false
        awaitingNavi = false
        platform.stopListening()
        platform.clearSpeech()
        platform.clearRouteMessage()
        screen = target
    }

    private val prefs get() = getApplication<Application>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dataConsented get() = prefs.getInt("data-consent-version", 0) == 1
    private var afterDataConsent = Screen.DESTINATION

    private fun dataConsentFor(target: Screen): Boolean {
        if (dataConsented) return true
        afterDataConsent = target
        go(Screen.DATA_CONSENT)
        return false
    }

    fun ensureDataConsent(): Boolean = parked && dataConsentFor(Screen.PLACE)

    fun agreeToDataUse() {
        if (!parked || screen != Screen.DATA_CONSENT) return
        prefs.edit().putInt("data-consent-version", 1).apply()
        refreshLocation()
        fillRouteMaps()
        go(afterDataConsent)
    }

    /** The introduction, from the home screen's `앱 소개`. */
    fun openWelcome() = go(Screen.WELCOME)

    /** Done or skipped: either way it is not shown on its own again. */
    fun finishWelcome() {
        prefs.edit().putBoolean(PREF_WELCOMED, true).apply()
        home()
    }

    fun home() {
        finishedRecord = null
        selectedPlace = null
        searchQuery = ""
        go(Screen.HOME)
    }

    /** "드라이브 시작" on the home screen: where the driver finds a place to go. */
    fun openDestination() {
        finishedRecord = null
        selectedPlace = null
        if (!dataConsentFor(Screen.DESTINATION)) return
        go(Screen.DESTINATION)
    }

    /** False means this is the top screen, and the activity hides itself rather than finishing. */
    fun goBack(): Boolean {
        if (tripBusy) return true
        if (recorder.state.value != null) {
            if (screen == Screen.STOP) { keepRecording(); return true }
            return false
        }
        when (screen) {
            Screen.HOME, Screen.RECOVERY, Screen.SYNCING -> return false
            Screen.WELCOME -> finishWelcome()
            Screen.LINK, Screen.LINK_CONSENT -> leaveLink()
            Screen.PRIVACY -> home()
            Screen.DATA_CONSENT -> home()
            Screen.PHONE_PREVIEW_LINK, Screen.PHONE_PREVIEW_DONE -> leavePhonePreview()
            Screen.CARD_QR -> leaveCardQr()
            Screen.COURSE_DETAIL -> go(Screen.COMMUNITY)
            Screen.COURSE_SHARE -> go(returnAfterCourseShare)
            Screen.RECORD -> if (returnAfterRecord == Screen.HOME) home() else go(returnAfterRecord)
            Screen.TRIP -> backFromTrip()
            Screen.TRIP_EDITOR -> cancelTripEditor()
            Screen.TRIP_PICKER -> cancelTripPicker()
            Screen.PLACE -> openDestination()
            else -> home()
        }
        return true
    }

    // --- Places: speech and typing only produce a keyword; the navigation app finds the place ------

    /** One position for the map, only when permission is already held. It never prompts on the home screen. */
    private fun refreshLocation() {
        if (!dataConsented || simulation || !locationSupported || recorder.state.value != null) return
        val context = getApplication<Application>()
        if (platform.locationPermissions().any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return
        platform.startLocation(
            onPoint = { latitude, longitude ->
                viewModelScope.launch {
                    currentLocation = MapPoint(latitude, longitude)
                    if (recorder.state.value == null) platform.stopLocation()
                }
            },
            onError = {},
        )
    }

    fun toggleListening() {
        if (!parked) return
        if (platform.speech.value.listening) platform.stopListening() else {
            searchQuery = ""
            platform.startListening()
        }
    }

    /** Explicit action only: the driver pressed 찾기 on typed or spoken text. */
    fun searchPlace() {
        val keyword = searchQuery.trim()
        if (!parked || keyword.isEmpty()) return
        val navi = platform.navi.value
        if (!navi.available) {
            // Debug stand-in for emulators without navigation; the screen carries the simulation badge.
            if (simulation) selectPlace(simulatedNaviPlaces.firstOrNull { it.name.contains(keyword) } ?: simulatedNaviPlaces.first())
            return
        }
        destinationBeforeSearch = navi.currentDestination
        awaitingNavi = true
        if (!returnHintSpoken) {
            returnHintSpoken = true
            platform.speak("내비에서 목적지를 고른 뒤 드라이브로그로 돌아오면 기록을 시작할 수 있어요.")
        }
        platform.searchPlace(keyword)
    }

    fun selectPlace(place: NaviPlace) {
        if (!parked || recorder.state.value != null) return
        selectedPlace = place
        searchQuery = place.name
        if (!dataConsentFor(Screen.PLACE)) return
        store.addRecentPlace(place.toPlace())
        go(Screen.PLACE)
    }

    fun recentPlaces(): List<NaviPlace> {
        val navi = platform.navi.value
        val own = store.state.value.recentPlaces.map { it.toNaviPlace() }
        val merged = mergePlaces(listOfNotNull(navi.currentDestination), own, navi.recents)
        return if (merged.isEmpty() && simulation && !navi.available) simulatedNaviPlaces else merged
    }

    fun savedPlaces(): List<NaviPlace> = mergePlaces(store.state.value.savedPlaces.map { it.toNaviPlace() }, platform.navi.value.bookmarks)

    fun isSaved(place: NaviPlace) = store.state.value.savedPlaces.any { it.toNaviPlace().sameLocation(place) }

    fun toggleSaved() {
        val place = selectedPlace ?: return
        val wasSaved = isSaved(place)
        store.toggleSavedPlace(place.toPlace())
        say(if (wasSaved) "저장한 장소에서 뺐어요" else "저장한 장소에 넣었어요")
    }

    // --- Recording --------------------------------------------------------------------------------

    fun missingLocationPermissions(): List<String> {
        if (simulation) return emptyList()
        val context = getApplication<Application>()
        return platform.locationPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private var energyAsked = false

    /**
     * What to ask for when the driver starts a drive: location, which recording needs, and the vehicle's energy
     * level, which only the drive's efficiency needs. Energy is asked once a session so a refusal is not nagged.
     */
    fun startPermissions(): List<String> {
        if (simulation) return emptyList()
        val energy = app.vehicleState.missingEnergyPermission()?.takeIf { !energyAsked }
        if (energy != null) energyAsked = true
        return missingLocationPermissions() + listOfNotNull(energy)
    }

    /** The driver pressed "길 안내 및 기록 시작", so this is the one explicit route request. */
    fun startRecording() {
        if (!parked) return
        if (!dataConsentFor(Screen.PLACE)) return
        val place = selectedPlace ?: return
        recheckLocationSupport()
        if (!recordingAvailable) { message = "이 차량에서는 위치를 수집할 수 없어 기록할 수 없어요."; return }
        // Before the recording exists: guidance that ended earlier must not read as the end of this drive.
        platform.clearRouteEnd()
        if (!recorder.start(null, place.toPlace(), simulation)) {
            message = "위치 권한을 허용하면 기록할 수 있어요. 경로는 드라이브 카드를 만드는 데 쓰여요."
            // After a permanent refusal the system stops asking, and this is the only way left to allow it.
            locationRefused = missingLocationPermissions().isNotEmpty()
            return
        }
        locationRefused = false
        val navi = platform.navi.value
        val guiding = navi.currentDestination?.sameLocation(place) == true
        go(Screen.HOME)
        if (!simulation) RecordingService.startIfAllowed(getApplication())
        platform.speak("${place.name} 기록을 시작했어요.")
        if (navi.available) {
            if (guiding) platform.openNavigation() else platform.requestRoute(place)
        }
    }

    fun openAppSettings() {
        val context = getApplication<Application>()
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message = "설정 화면을 열 수 없어요. 차량 설정에서 위치 권한을 허용해 주세요." }
    }

    fun openNavigation() = platform.openNavigation()
    fun showRouteOverview() { if (parked) platform.showRouteOverview() }
    fun cancelRoute() = platform.cancelRoute()

    fun askToStop() {
        if (!parked) return
        recorder.publish()
        go(Screen.STOP)
    }
    fun keepRecording() { go(Screen.HOME) }

    /** Parked only: recording goes on, and the time until the car leaves P is marked as a break. */
    fun restRecording() {
        if (!parked || recorder.state.value == null) return
        recorder.startRest()
        go(Screen.HOME)
        say("쉬는 시간을 따로 표시해요")
    }

    fun endRecording() = endRecording(announce = false)

    /** [announce] when guidance ended the drive: the driver may not be looking, so the result is said. */
    private fun endRecording(announce: Boolean) {
        if (!parked || saving) return
        saving = true
        viewModelScope.launch {
            try {
                val finished = recorder.finish() ?: return@launch
                // The first route the navigation app started during this drive: the one it planned from the start.
                val planned = platform.navi.value.routeStarts.firstOrNull { it.atMillis >= finished.startedAtMillis }?.summary
                val record = if (planned == null) finished else finished.copy(naviDistanceMeters = planned.distanceMeters, naviDurationSeconds = planned.durationSeconds)
                RecordingService.stop(getApplication())
                val stored = store.addRecordDurably(record)
                keepRecoveryUntilStored(stored)
                if (announce) platform.speak(if (stored) savedSentence(record) else "저장 공간이 부족해 드라이브를 아직 저장하지 못했어요.")
                finishedRecord = record
                fillRouteMaps()
                continueAfterRecord()
            } finally {
                saving = false
            }
        }
    }

    /** Offered only when the car barely moved. Guidance is the navigation app's, so it stays as the driver left it. */
    fun discardRecording() {
        if (!parked) return
        recorder.discard()
        RecordingService.stop(getApplication())
        routeMaps.clearTiles() // The stop screen fetched the map of this spot.
        home()
        say("저장하지 않고 끝냈어요")
    }

    /** Outcome of the upload decides what the driver is told; "arrived" is never claimed in advance. */
    fun continueAfterRecord() {
        if (!BuildConfig.PHONE_LINK_ENABLED) { go(Screen.DONE); return }
        if (store.state.value.token == null) {
            returnAfterLink = Screen.DONE
            requestLinkConsent()
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

    /**
     * The recording files are the drive's only other copy, so they go only once the record is on disk. When the disk
     * is full they wait for the next write that succeeds, and the driver is told now, while parked.
     */
    private fun keepRecoveryUntilStored(stored: Boolean) {
        if (stored) { recorder.clearRecovery(); return }
        message = "저장 공간이 부족해 기록을 아직 저장하지 못했어요. 공간이 생기면 다시 저장해요"
        viewModelScope.launch {
            store.writeFailed.first { !it }
            recorder.clearRecovery()
        }
    }

    fun keepPartial() {
        val record = recoveredRecord ?: return
        recoveredRecord = null
        viewModelScope.launch { keepRecoveryUntilStored(store.addRecordDurably(record)) }
        recoveredPlace = null
        finishedRecord = record
        fillRouteMaps()
        continueAfterRecord()
    }

    fun deletePartial() {
        recorder.clearRecovery()
        routeMaps.clearTiles() // The recovery screen fetched the map of where this drive went.
        recoveredRecord = null
        recoveredPlace = null
        home()
        say("남은 기록을 지웠어요")
    }

    // --- 내 기록 ----------------------------------------------------------------------------------

    /** Trips first, with independent drives/cards/stamps still available. */
    var historyTab by mutableIntStateOf(0)
        private set

    fun openHistory() = openHistoryTab(0)

    fun openStamps() = openHistoryTab(3)

    private fun openHistoryTab(tab: Int) {
        historyTab = tab
        finishedRecord = null
        fillRouteMaps()
        go(Screen.HISTORY)
    }

    /** Where a record was opened from, so Back and 돌아가기 return there rather than always to 내 기록. */
    var returnAfterRecord by mutableStateOf(Screen.HISTORY)
        private set

    fun openRecord(record: RecordV) {
        openedRecordId = record.id
        returnAfterRecord = when (screen) { Screen.TRIP -> Screen.TRIP; Screen.HISTORY -> Screen.HISTORY; else -> Screen.HOME }
        go(Screen.RECORD)
    }

    fun deleteOpenedRecord() {
        openedRecordId?.let {
            store.deleteRecord(it)
            routeMaps.delete(it)
        }
        openedRecordId = null
        if (returnAfterRecord == Screen.HOME) home() else go(returnAfterRecord)
        say("기록을 지웠어요")
    }

    // --- User-curated trips: local metadata, never a new synthetic drive ----------------------------

    var openedTripId by mutableStateOf<String?>(null)
        private set
    var editingTripId by mutableStateOf<String?>(null)
        private set
    var tripDraftTitle by mutableStateOf("")
        private set
    var tripDraftRecordIds by mutableStateOf(emptyList<String>())
        private set
    var tripDraftCoverId by mutableStateOf<String?>(null)
        private set
    var tripBusy by mutableStateOf(false)
        private set
    var tripError by mutableStateOf<String?>(null)
        private set
    private var tripDraftId = ""
    private var returnAfterTrip = TripReturnTarget(Screen.HISTORY)
    private var returnAfterTripEditor = Screen.HISTORY
    private var returnAfterTripPicker = Screen.RECORD
    private var tripPickerRecordId: String? = null
    private fun canEditTrip() = parked && recorder.state.value == null && !tripBusy

    fun openTrip(trip: TripV) {
        if (!canEditTrip()) return
        rememberTripReturn(if (screen == Screen.HOME) Screen.HOME else Screen.HISTORY)
        openedTripId = trip.id
        tripError = null
        go(Screen.TRIP)
    }

    private fun rememberTripReturn(target: Screen) {
        returnAfterTrip = TripReturnTarget(target, openedRecordId, returnAfterRecord)
    }

    private fun resumeAfterTrip() {
        val target = resolveTripReturn(returnAfterTrip, store.state.value.journey.records.map { it.id }.toSet())
        if (target.screen == Screen.RECORD) {
            openedRecordId = target.recordId
            returnAfterRecord = target.recordParent
        }
        if (target.screen == Screen.HOME) home() else go(target.screen)
    }

    fun backFromTrip() { if (!tripBusy) resumeAfterTrip() }

    fun createTrip(record: RecordV? = null) {
        if (!canEditTrip()) return
        returnAfterTripEditor = screen
        if (screen != Screen.TRIP_EDITOR && screen != Screen.TRIP) rememberTripReturn(
            if (screen == Screen.TRIP_PICKER) returnAfterTripPicker else screen)
        editingTripId = null
        tripDraftId = UUID.randomUUID().toString()
        tripDraftTitle = if (record != null) "${shortName(record.placeName)} 여행".take(TRIP_TITLE_LIMIT)
            else java.text.SimpleDateFormat("M월 d일의 여행", java.util.Locale.KOREA).format(java.util.Date())
        tripDraftRecordIds = listOfNotNull(record?.id)
        tripDraftCoverId = record?.id
        tripError = null
        go(Screen.TRIP_EDITOR)
    }

    fun editTrip() {
        if (!canEditTrip()) return
        val trip = store.state.value.trips.firstOrNull { it.id == openedTripId } ?: return
        returnAfterTripEditor = Screen.TRIP
        editingTripId = trip.id
        tripDraftId = trip.id
        tripDraftTitle = trip.title
        tripDraftRecordIds = trip.recordIds
        tripDraftCoverId = trip.coverRecordId
        tripError = null
        go(Screen.TRIP_EDITOR)
    }

    fun setTripTitle(title: String) { if (!tripBusy) { tripDraftTitle = title.take(TRIP_TITLE_LIMIT); tripError = null } }
    fun toggleTripRecord(id: String) {
        if (!canEditTrip()) return
        if (store.state.value.trips.any { it.id != editingTripId && id in it.recordIds }) return
        tripDraftRecordIds = if (id in tripDraftRecordIds) tripDraftRecordIds - id else tripDraftRecordIds + id
        if (tripDraftCoverId !in tripDraftRecordIds) tripDraftCoverId = tripDraftRecordIds.firstOrNull()
        tripError = null
    }
    fun chooseTripCover(id: String) { if (canEditTrip() && id in tripDraftRecordIds) tripDraftCoverId = id }

    fun saveTrip() {
        if (!canEditTrip()) return
        tripBusy = true
        tripError = null
        val connection = store.state.value.token
        viewModelScope.launch {
            try {
                val saved = store.saveTripDurably(tripDraftId, tripDraftTitle, tripDraftRecordIds, tripDraftCoverId)
                if (store.state.value.token != connection) return@launch
                if (saved == null) { tripError = "주행 선택과 저장 공간을 확인한 뒤 다시 저장해 주세요"; return@launch }
                openedTripId = saved.id
                go(Screen.TRIP)
                say("여행을 저장했어요")
            } finally { tripBusy = false }
        }
    }

    fun cancelTripEditor() { if (!tripBusy) { tripError = null; go(returnAfterTripEditor) } }

    fun addRecordToTrip(record: RecordV) {
        if (!canEditTrip()) return
        store.state.value.trips.firstOrNull { record.id in it.recordIds }?.let { trip ->
            if (screen != Screen.RECORD || returnAfterRecord != Screen.TRIP) rememberTripReturn(screen)
            openedTripId = trip.id
            tripError = null
            go(Screen.TRIP)
            return
        }
        returnAfterTripPicker = screen
        tripPickerRecordId = record.id
        tripError = null
        go(Screen.TRIP_PICKER)
    }

    fun createTripFromPicker() = createTrip(store.state.value.journey.records.firstOrNull { it.id == tripPickerRecordId })
    fun cancelTripPicker() { if (!tripBusy) go(returnAfterTripPicker) }
    fun addToExistingTrip(trip: TripV) {
        if (!canEditTrip()) return
        val recordId = tripPickerRecordId ?: return
        tripBusy = true
        tripError = null
        viewModelScope.launch {
            try {
                val saved = store.saveTripDurably(trip.id, trip.title, trip.recordIds + recordId, trip.coverRecordId)
                if (saved == null) { tripError = "이 주행을 담지 못했어요. 저장 공간과 다른 여행을 확인해 주세요"; return@launch }
                openedTripId = saved.id
                rememberTripReturn(returnAfterTripPicker)
                go(Screen.TRIP)
                say("여행에 주행을 담았어요")
            } finally { tripBusy = false }
        }
    }

    fun deleteTrip() {
        if (!canEditTrip()) return
        val id = openedTripId ?: return
        tripBusy = true
        viewModelScope.launch {
            try {
                if (!store.deleteTripDurably(id)) { tripError = "변경을 저장하지 못했어요. 저장 공간을 확인해 주세요"; return@launch }
                openedTripId = null
                resumeAfterTrip()
                say("여행 묶음을 해제했어요. 주행 기록은 그대로예요")
            } finally { tripBusy = false }
        }
    }

    // --- Phone link -------------------------------------------------------------------------------

    fun openLink() {
        if (!parked || recorder.state.value != null) return
        returnAfterLink = Screen.HOME
        requestLinkConsent()
    }

    private fun requestLinkConsent() {
        linkJob?.cancel()
        linkRequest += 1
        linkCode = null
        includeLinkHistory = false
        linkConsented = false
        go(Screen.LINK_CONSENT)
    }

    fun unlink() {
        if (!parked || recorder.state.value != null) return
        linkJob?.cancel()
        linkRequest += 1
        linkConsented = false
        store.unlink()
        routeMaps.clearTiles()
        home()
        say("연결을 해제했어요. 서버 기록 삭제는 별도예요")
    }

    fun openPrivacy() { if (parked) go(Screen.PRIVACY) }

    // --- Phone screens as a preview: no server, no token, and the record stays as it is --------------

    var phonePreviewRecord by mutableStateOf<RecordV?>(null)
        private set
    var phonePreviewCode by mutableStateOf("")
        private set
    var phonePreviewExpiresAt by mutableStateOf(0L)
        private set
    private var returnAfterPhonePreview = Screen.HOME

    fun openPhonePreview(record: RecordV) {
        phonePreviewRecord = record
        returnAfterPhonePreview = screen
        newPhonePreviewCode()
        go(Screen.PHONE_PREVIEW_LINK)
    }

    fun newPhonePreviewCode() {
        phonePreviewCode = previewLinkCode()
        phonePreviewExpiresAt = System.currentTimeMillis() + PHONE_PREVIEW_CODE_MS
    }

    fun showPhonePreviewDone() = go(Screen.PHONE_PREVIEW_DONE)

    fun leavePhonePreview() = go(returnAfterPhonePreview)

    // --- A drive card handed to the phone by QR code: nothing leaves the car any other way ------------

    var cardQrRecord by mutableStateOf<RecordV?>(null)
        private set
    /** The same QR screen recommending the drive as a course: trimmed ends, no date. */
    var cardQrCourse by mutableStateOf(false)
        private set
    private var returnAfterCardQr = Screen.HOME

    fun openCardQr(record: RecordV, course: Boolean = false) {
        if (!parked) return
        cardQrRecord = record
        cardQrCourse = course
        returnAfterCardQr = screen
        go(Screen.CARD_QR)
    }

    fun leaveCardQr() = go(returnAfterCardQr)

    // --- Community as a preview: example courses, and recommendations kept only in memory -------------

    var postedCourses by mutableStateOf(emptyList<CommunityCourse>())
        private set
    val communityCourses get() = postedCourses + SampleCourses
    var courseRegionFilter by mutableStateOf<String?>(null)
    var courseThemeFilter by mutableStateOf<String?>(null)
    var openedCourse by mutableStateOf<CommunityCourse?>(null)
        private set
    var courseDraft by mutableStateOf<CourseDraft?>(null)
        private set
    private var returnAfterCourseShare = Screen.HOME
    private var courseDraftRecordId: String? = null

    fun openCommunity() {
        courseRegionFilter = null
        courseThemeFilter = null
        go(Screen.COMMUNITY)
    }

    fun openCourse(course: CommunityCourse) {
        openedCourse = course
        go(Screen.COURSE_DETAIL)
    }

    fun openCourseShare(record: RecordV) {
        courseDraft = courseDraftFrom(record)
        courseDraftRecordId = record.id
        returnAfterCourseShare = screen
        go(Screen.COURSE_SHARE)
    }

    /** One recommendation per drive: posting again replaces it, as tapping twice must not list a course twice. */
    fun publishCourse(tags: List<String>) {
        val draft = courseDraft ?: return
        val id = "mine-$courseDraftRecordId"
        postedCourses = listOf(draft.toCourse(id, tags)) + postedCourses.filter { it.id != id }
        openCommunity()
    }

    fun removeCourse(course: CommunityCourse) {
        postedCourses = postedCourses.filter { it.id != course.id }
        go(Screen.COMMUNITY)
    }

    fun startLink() {
        if (!parked || !BuildConfig.PHONE_LINK_ENABLED || !linkConsented) return
        linkJob?.cancel()
        val request = ++linkRequest
        linkCode = null
        linkError = false
        go(Screen.LINK)
        linkJob = viewModelScope.launch {
            val code = runCatching { store.api.createLinkCode() }.getOrNull()
            if (!linkConsented || request != linkRequest) return@launch
            if (code == null) { linkError = true; return@launch }
            linkCode = code
            while (isActive) {
                delay(2_000)
                val poll = runCatching { store.api.pollLink(code.pollToken) }.getOrNull() ?: continue
                if (!linkConsented || request != linkRequest) return@launch
                if (poll.linked && poll.accessToken != null) {
                    val importedHistory = includeLinkHistory
                    store.setToken(poll.accessToken, includeLocalRecords = includeLinkHistory)
                    linkConsented = false
                    linkCode = null
                    if (returnAfterLink == Screen.DONE && importedHistory) continueAfterRecord()
                    else {
                        home()
                        say(if (importedHistory) "연결했어요. 선택한 기존 기록을 전송해요" else "연결했어요. 다음 기록부터 전송해요")
                    }
                    return@launch
                }
                if (poll.expired) return@launch // The screen shows 00:00 and offers a new code.
            }
        }
    }

    fun leaveLink() {
        linkJob?.cancel()
        linkRequest += 1
        linkConsented = false
        linkCode = null
        if (returnAfterLink == Screen.DONE) go(Screen.OFFLINE) else home()
    }
}
