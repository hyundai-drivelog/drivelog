package com.drivelog.app.feature.car

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
import com.drivelog.app.feature.diagnostics.DiagnosticsRoute

/**
 * Routes by safety state first and navigation second: while moving, only the recording view or a lock
 * notice can render, so no editing control is reachable regardless of how the driver arrived here.
 */
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
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true }) {
        val dimens = remember(maxWidth, maxHeight) { carDimensFor(maxWidth.value, maxHeight.value) }
        CompositionLocalProvider(LocalCarDimens provides dimens) {
            Box(Modifier.fillMaxSize()) {
                val active = recording
                when {
                    active != null && model.screen == Screen.STOP && parked ->
                        StopConfirmScreen(active, model::endRecording, model::keepRecording)
                    active != null -> RecordingScreen(
                        active, parked, model.gear, BuildConfig.DEBUG && active.simulated, navi.available, navi.routeMessage,
                        model::askToStop, model::changeSimGear, model::openNavigation,
                    )
                    !parked -> DrivingLockScreen()
                    else -> when (model.screen) {
                        Screen.DIAGNOSTICS -> DiagnosticsRoute(model.simulation, model::toggleSimulation, { model.home() })
                        Screen.RECOVERY -> model.recoveredRecord?.let { record ->
                            RecoveryScreen(record, model.recoveredPlace?.let { MapPoint(it.latitude, it.longitude) }, model::keepPartial, model::deletePartial)
                        }
                        Screen.PLACE -> model.selectedPlace?.let { place ->
                            PlaceConfirmScreen(
                                place, model.searchQuery, speech, canSearch, model.simulation, navi.available,
                                navi.currentDestination?.sameLocation(place) == true, store.savedPlaces.any { it.toNaviPlace().sameLocation(place) },
                                model.message ?: navi.routeMessage, model.currentLocation, searchActions,
                                model::toggleSaved, onStartPressed, model::cancelRoute, model::home,
                            )
                        }
                        Screen.HISTORY -> HistoryScreen(model.store, model.routeMaps, store.journey.records, model::openRecord, model::home)
                        Screen.RECORD -> RecordSavedScreen(
                            model.routeMaps, store.journey.records.firstOrNull { it.id == model.openedRecordId }, "드라이브 기록",
                            { model.goBack() }, null, model::deleteOpenedRecord,
                        )
                        Screen.SYNCING -> SyncingScreen()
                        Screen.LINK -> PhoneLinkScreen(
                            model.linkCode?.code, model.linkCode?.expiresAtMillis ?: 0, model.linkCode?.webUrl, model.linkError,
                            model::leaveLink, model::startLink,
                        )
                        Screen.DONE -> if (BuildConfig.PHONE_LINK_ENABLED) ContinueOnPhoneScreen(model.finishedRecord, model::home)
                        else RecordSavedScreen(model.routeMaps, model.finishedRecord, "드라이브를 기록했어요", model::home, model::openHistory, null)
                        Screen.OFFLINE -> {
                            val record = model.finishedRecord
                            OfflineSavedScreen(record, record?.destinationPoint(), store.token != null, model.syncing, model::retrySync, model::home)
                        }
                        else -> HomeScreen(
                            model.searchQuery, speech, canSearch, model.simulation, navi.routeMessage,
                            remember(store.recentPlaces, navi) { model.recentPlaces() }, remember(store.savedPlaces, navi) { model.savedPlaces() },
                            model.currentLocation, searchActions, model::selectPlace, model::openHistory, { model.go(Screen.DIAGNOSTICS) },
                            if (BuildConfig.PHONE_LINK_ENABLED) ({ LinkControl(store.token != null, model::openLink, model::unlink) }) else null,
                        )
                    }
                }
            }
        }
    }
}
