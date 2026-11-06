package com.drivelog.app.feature.car

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** RECORDING and LOCKED follow from vehicle state (see routeFor); nothing navigates to them. */
enum class Screen { HOME, PLACE, HISTORY, RECORD, STOP, SYNCING, LINK, DONE, OFFLINE, RECOVERY, DIAGNOSTICS, RECORDING, LOCKED, PHONE_PREVIEW_LINK, PHONE_PREVIEW_DONE, COMMUNITY, COURSE_DETAIL, COURSE_SHARE, CARD_QR }

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
                    screen = Screen.STOP
                    platform.speak("정차했어요. 기록을 종료할 수 있어요.")
                }
                if (current != CarGear.PARK && screen == Screen.STOP) screen = Screen.HOME
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
                        val current = recorder.state.value
                        // Guidance switched off before setting out says nothing about a drive worth keeping.
                        if (current != null && barelyMoved(current.distanceKm, current.points.size >= 2)) {
                            screen = Screen.STOP
                            platform.speak("거의 움직이지 않았어요. 저장할지 골라 주세요.")
                        } else {
                            platform.speak("안내가 끝나 드라이브를 저장했어요.")
                            endRecording()
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
     * The navigation service can come up after this app, and without it a first-time driver cannot search at all.
     * Tries for a minute while on screen, since a vehicle without navigation never connects.
     */
    private fun retryNaviConnection() {
        naviRetry?.cancel()
        naviRetry = viewModelScope.launch {
            repeat(NAVI_RETRY_COUNT) {
                if (platform.navi.value.available) return@launch
                platform.connect()
                delay(NAVI_RETRY_MS)
            }
        }
    }

    /** Parked and not recording only. A failure simply leaves the map-free card until the next attempt. */
    private fun fillRouteMaps() {
        if (!parked || recorder.state.value != null || mapJob?.isActive == true) return
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

    fun home() {
        finishedRecord = null
        selectedPlace = null
        searchQuery = ""
        go(Screen.HOME)
    }

    /** False means this is the top screen, and the activity hides itself rather than finishing. */
    fun goBack(): Boolean {
        if (recorder.state.value != null) {
            if (screen == Screen.STOP) { keepRecording(); return true }
            return false
        }
        when (screen) {
            Screen.HOME, Screen.RECOVERY, Screen.SYNCING -> return false
            Screen.LINK -> leaveLink()
            Screen.PHONE_PREVIEW_LINK, Screen.PHONE_PREVIEW_DONE -> leavePhonePreview()
            Screen.CARD_QR -> leaveCardQr()
            Screen.COURSE_DETAIL -> go(Screen.COMMUNITY)
            Screen.COURSE_SHARE -> go(returnAfterCourseShare)
            Screen.RECORD -> go(Screen.HISTORY)
            else -> home()
        }
        return true
    }

    // --- Places: speech and typing only produce a keyword; the navigation app finds the place ------

    /** One position for the map, only when permission is already held. It never prompts on the home screen. */
    private fun refreshLocation() {
        if (simulation || !locationSupported || recorder.state.value != null) return
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
        platform.searchPlace(keyword)
    }

    fun selectPlace(place: NaviPlace) {
        if (!parked || recorder.state.value != null) return
        selectedPlace = place
        searchQuery = place.name
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

    /** The driver pressed "길 안내 및 기록 시작", so this is the one explicit route request. */
    fun startRecording() {
        if (!parked) return
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

    fun askToStop() { if (parked) go(Screen.STOP) }
    fun keepRecording() { go(Screen.HOME) }

    fun endRecording() {
        if (!parked || saving) return
        saving = true
        viewModelScope.launch {
            try {
                val record = recorder.finish() ?: return@launch
                RecordingService.stop(getApplication())
                store.addRecord(record)
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

    fun openHistory() {
        finishedRecord = null
        fillRouteMaps()
        go(Screen.HISTORY)
    }

    fun openRecord(record: RecordV) {
        openedRecordId = record.id
        go(Screen.RECORD)
    }

    fun deleteOpenedRecord() {
        openedRecordId?.let {
            store.deleteRecord(it)
            routeMaps.delete(it)
        }
        openedRecordId = null
        go(Screen.HISTORY)
        say("기록을 지웠어요")
    }

    // --- Phone link -------------------------------------------------------------------------------

    fun openLink() {
        returnAfterLink = Screen.HOME
        startLink()
    }

    fun unlink() = store.unlink()

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
    private var returnAfterCardQr = Screen.HOME

    fun openCardQr(record: RecordV) {
        if (!parked) return
        cardQrRecord = record
        returnAfterCardQr = screen
        go(Screen.CARD_QR)
    }

    fun leaveCardQr() = go(returnAfterCardQr)

    // --- Community as a preview: example courses, and recommendations kept only in memory -------------

    var postedCourses by mutableStateOf(emptyList<CommunityCourse>())
        private set
    val communityCourses get() = postedCourses + SampleCourses
    var courseOriginFilter by mutableStateOf<String?>(null)
    var courseDestinationFilter by mutableStateOf<String?>(null)
    var openedCourse by mutableStateOf<CommunityCourse?>(null)
        private set
    var courseDraft by mutableStateOf<CourseDraft?>(null)
        private set
    private var returnAfterCourseShare = Screen.HOME
    private var courseDraftRecordId: String? = null

    fun openCommunity() {
        courseOriginFilter = null
        courseDestinationFilter = null
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
}
