package com.drivelog.app.feature.car

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.drivelog.app.BuildConfig
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.core.recording.Recording
import com.drivelog.app.feature.diagnostics.DiagnosticsRoute

/**
 * Safety state first and navigation second: while moving, only the recording view or a lock notice can
 * render, so no editing control is reachable regardless of how the driver arrived here.
 */
fun routeFor(recording: Boolean, parked: Boolean, screen: Screen): Screen = when {
    recording && screen == Screen.STOP && parked -> Screen.STOP
    recording -> Screen.RECORDING
    !parked -> Screen.LOCKED
    else -> screen
}

/** A copy of what a screen shows, so one that is fading out keeps its content after the model has moved on. */
private data class ShownScreen(
    val route: Screen, val recording: Recording?, val place: NaviPlace?, val recovered: RecordV?, val recoveredPoint: MapPoint?,
    val finished: RecordV?, val opened: RecordV?, val query: String, val phonePreview: RecordV?,
)

/** The phone screens as a mockup, for builds where no real hand-off exists. A submitted build must not offer them. */
private val phonePreviewOffered = BuildConfig.DEBUG && !BuildConfig.PHONE_LINK_ENABLED && BuildConfig.CARD_WEB_URL.isBlank()

/** The real hand-off: a QR code opening the published card page. Offered only once that page has an address. */
private val cardQrOffered = BuildConfig.CARD_WEB_URL.isNotBlank()

private fun CarViewModel.sendToPhone(record: RecordV): (() -> Unit)? = when {
    cardQrOffered -> { { openCardQr(record) } }
    phonePreviewOffered -> { { openPhonePreview(record) } }
    else -> null
}

/**
 * Recommending a course: a real QR hand-off once the card page has an address, otherwise the community mockup in
 * debug builds. A drive too short to keep anything after trimming both ends offers neither.
 */
private fun CarViewModel.recommend(record: RecordV): (() -> Unit)? = when {
    courseDraftFrom(record) == null -> null
    cardQrOffered -> { { openCardQr(record, course = true) } }
    BuildConfig.DEBUG -> { { openCourseShare(record) } }
    else -> null
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun CarApp(model: CarViewModel, onStartPressed: () -> Unit) {
    val store by model.store.state.collectAsState()
    val recording by model.recorder.state.collectAsState()
    val navi by model.platform.navi.collectAsState()
    val speech by model.platform.speech.collectAsState()
    val parked = model.parked
    val canSearch = navi.available || model.simulation
    val searchActions = remember(model) {
        SearchActions({ model.searchQuery = it }, model::searchPlace, model::toggleListening, { model.searchQuery = "" })
    }
    val screenFor: @Composable (ShownScreen) -> Unit = { shown ->
        val active = shown.recording
        when (shown.route) {
            Screen.STOP -> if (active != null) StopConfirmScreen(model.routeMaps, active, model::endRecording, model::discardRecording, model::keepRecording, model.saving, onRest = model::restRecording)
            Screen.RECORDING -> if (active != null) RecordingScreen(
                active, parked, model.gear, BuildConfig.DEBUG && active.simulated, navi.available, navi.routeMessage, navi.routeEnd != null,
                model::askToStop, model::changeSimGear, model::openNavigation,
            )
            Screen.LOCKED -> DrivingLockScreen()
            Screen.DIAGNOSTICS -> if (BuildConfig.DEBUG) DiagnosticsRoute(model.simulation, model::toggleSimulation, { model.home() })
            Screen.RECOVERY -> shown.recovered?.let { record ->
                RecoveryScreen(model.routeMaps, record, shown.recoveredPoint, model::keepPartial, model::deletePartial)
            }
            Screen.PLACE -> shown.place?.let { place ->
                val guiding = navi.currentDestination?.sameLocation(place) == true
                PlaceConfirmScreen(
                    place, shown.query, speech, canSearch, model.simulation, navi.available,
                    guiding, store.savedPlaces.any { it.toNaviPlace().sameLocation(place) },
                    model.message ?: navi.routeMessage, model.currentLocation, searchActions,
                    model::toggleSaved, onStartPressed, model::cancelRoute, model::openDestination,
                    if (model.locationRefused) model::openAppSettings else null,
                    model.routeMaps, navi.routeSummary.takeIf { guiding }, model::showRouteOverview,
                )
            }
            Screen.HISTORY -> HistoryScreen(model.store, model.routeMaps, store.journey.records, model::openRecord, model::home, model.historyTab,
                store.trips, model::openTrip, { model.createTrip() })
            Screen.TRIP -> TripDetailScreen(store.trips.firstOrNull { it.id == model.openedTripId }, store.journey.records,
                model::backFromTrip, model::editTrip, model::openRecord, model::deleteTrip, model.tripBusy, model.tripError)
            Screen.TRIP_EDITOR -> TripEditorScreen(model.tripDraftTitle, model.tripDraftRecordIds, model.tripDraftCoverId, model.editingTripId,
                store.trips, store.journey.records, model.tripBusy, model.tripError, model::setTripTitle, model::toggleTripRecord,
                model::chooseTripCover, model::saveTrip, model::cancelTripEditor)
            Screen.TRIP_PICKER -> TripPickerScreen(store.trips, store.journey.records, model.tripBusy, model.tripError,
                model::addToExistingTrip, model::createTripFromPicker, model::cancelTripPicker)
            Screen.RECORD -> RecordSavedScreen(
                model.routeMaps, shown.opened, "드라이브 기록", { model.goBack() }, null, model::deleteOpenedRecord,
                shown.opened?.let { model.sendToPhone(it) },
                shown.opened?.let { model.recommend(it) },
                shown.opened?.let { stampResultFor(it, store.journey.records) },
                returnsHome = model.returnAfterRecord == Screen.HOME,
                onTrip = shown.opened?.let { record -> { model.addRecordToTrip(record) } },
                tripLabel = if (store.trips.any { shown.opened?.id in it.recordIds }) "담긴 여행 보기" else "여행에 담기",
            )
            Screen.COMMUNITY -> CommunityScreen(
                model.routeMaps, model.communityCourses, model.courseRegionFilter, model.courseThemeFilter,
                { model.courseRegionFilter = it }, { model.courseThemeFilter = it }, model::openCourse, model::home,
            )
            Screen.COURSE_DETAIL -> model.openedCourse?.let { course ->
                CourseDetailScreen(model.routeMaps, course, { model.selectPlace(course.toPlace()) }, { model.removeCourse(course) }, { model.goBack() })
            }
            Screen.COURSE_SHARE -> CourseShareScreen(model.routeMaps, model.courseDraft, model::publishCourse) { model.goBack() }
            Screen.PHONE_PREVIEW_LINK -> PhoneLinkScreen(
                model.phonePreviewCode, model.phonePreviewExpiresAt, null, false,
                model::leavePhonePreview, model::newPhonePreviewCode, onPreviewNext = model::showPhonePreviewDone,
            )
            Screen.PHONE_PREVIEW_DONE -> ContinueOnPhoneScreen(shown.phonePreview, model::leavePhonePreview, preview = true)
            Screen.SYNCING -> SyncingScreen()
            Screen.CARD_QR -> model.cardQrRecord?.let { record -> CardQrScreen(record, BuildConfig.CARD_WEB_URL, model::leaveCardQr, model.cardQrCourse) }
            Screen.LINK -> PhoneLinkScreen(
                model.linkCode?.code, model.linkCode?.expiresAtMillis ?: 0, model.linkCode?.webUrl, model.linkError,
                model::leaveLink, model::startLink,
            )
            Screen.DONE -> if (BuildConfig.PHONE_LINK_ENABLED) ContinueOnPhoneScreen(shown.finished, model::home)
            else RecordSavedScreen(
                model.routeMaps, shown.finished, "드라이브를 기록했어요", model::home, model::openHistory, null,
                shown.finished?.let { model.sendToPhone(it) },
                shown.finished?.let { model.recommend(it) },
                shown.finished?.let { stampResultFor(it, store.journey.records) },
                onTrip = shown.finished?.let { record -> { model.addRecordToTrip(record) } },
                tripLabel = if (store.trips.any { shown.finished?.id in it.recordIds }) "담긴 여행 보기" else "여행에 담기",
            )
            Screen.OFFLINE -> OfflineSavedScreen(shown.finished, shown.finished?.destinationPoint(), store.token != null, model.syncing, model::retrySync, model::home)
            Screen.SETTINGS -> SettingsScreen(model.soundEffectsEnabled, model::toggleSoundEffects, model::openPrivacy, model::home)
            Screen.PRIVACY -> PrivacyScreen(model::closePrivacy)
            Screen.DATA_CONSENT -> PrivacyScreen(model::home, model::agreeToDataUse)
            Screen.LINK_CONSENT -> LinkConsentScreen(
                if (store.token == null) store.journey.records.size else 0,
                model.includeLinkHistory, model::toggleLinkHistory, model::agreeToLink, model::leaveLink,
            )
            Screen.WELCOME -> WelcomeScreen(model::finishWelcome)
            Screen.HOME -> HomeScreen(
                store.journey.records,
                model::openDestination, model::openHistory, model::openStamps,
                // A mockup of a community: a submitted build must not offer it.
                if (BuildConfig.DEBUG) model::openCommunity else null,
                model::openSettings,
                // A developer tool: a submitted build must not open it from a hidden gesture.
                if (BuildConfig.DEBUG) ({ model.go(Screen.DIAGNOSTICS) }) else null,
                if (BuildConfig.PHONE_LINK_ENABLED) ({ LinkControl(store.token != null, model::openLink, model::unlink) }) else null,
            )
            Screen.DESTINATION -> DestinationScreen(
                shown.query, speech, canSearch, model.simulation, navi.routeMessage,
                remember(store.recentPlaces, navi) { model.recentPlaces() }, remember(store.savedPlaces, navi) { model.savedPlaces() },
                searchActions, model::selectPlace, model::home,
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true }) {
        val dimens = remember(maxWidth, maxHeight) { carDimensFor(maxWidth.value, maxHeight.value) }
        val pressOverlay = PressOverlay(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
        CompositionLocalProvider(LocalCarDimens provides dimens, LocalMotionEnabled provides parked, LocalIndication provides pressOverlay) {
            val shown = ShownScreen(
                routeFor(recording != null, parked, model.screen), recording, model.selectedPlace, model.recoveredRecord,
                model.recoveredPlace?.let { MapPoint(it.latitude, it.longitude) }, model.finishedRecord,
                store.journey.records.firstOrNull { it.id == model.openedRecordId }, model.searchQuery, model.phonePreviewRecord,
            )
            // No transition exists while moving: leaving P drops this whole branch, including any screen mid-exit.
            if (parked) {
                AnimatedContent(
                    shown, Modifier.fillMaxSize(), label = "screen", contentKey = { it.route },
                    transitionSpec = { screenTransition(initialState.route, targetState.route) },
                ) { current ->
                    Box(Modifier.fillMaxSize()) {
                        screenFor(current)
                        if (transition.targetState != EnterExitState.Visible) Box(Modifier.fillMaxSize().blockTouches())
                    }
                }
                // Over every screen, and parked only: nothing new appears on screen while moving.
                Box(Modifier.fillMaxSize()) { ToastHost(model.toast, model::toastShown) }
            } else Box(Modifier.fillMaxSize()) { screenFor(shown) }
        }
    }
}
