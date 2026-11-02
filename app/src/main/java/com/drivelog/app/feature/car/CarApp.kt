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
    val goal = store.journey.activeGoal
    val parked = model.parked
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { testTagsAsResourceId = true }) {
        val dimens = remember(maxWidth, maxHeight) { carDimensFor(maxWidth.value, maxHeight.value) }
        CompositionLocalProvider(LocalCarDimens provides dimens) {
            Box(Modifier.fillMaxSize()) {
                val active = recording
                val hold = { model.go(Screen.DIAGNOSTICS) }
                when {
                    active != null && model.screen == Screen.STOP && parked ->
                        StopConfirmScreen(active, model::endRecording, model::keepRecording)
                    active != null -> RecordingScreen(
                        active, parked, model.gear, BuildConfig.DEBUG && active.simulated, navi.available, navi.routeMessage,
                        model::askToStop, model::changeSimGear, model::openNavigation,
                    )
                    !parked -> DrivingLockScreen(goal)
                    else -> when (model.screen) {
                        Screen.DIAGNOSTICS -> DiagnosticsRoute(model.simulation, model::toggleSimulation, { model.home() })
                        Screen.RECOVERY -> model.recoveredRecord?.let { record ->
                            RecoveryScreen(record, model.recoveredPlace?.let { MapPoint(it.latitude, it.longitude) }, model::keepPartial, model::deletePartial)
                        }
                        Screen.GOAL_NEW -> GoalNewScreen(
                            navi, model.simulation, model.goalName, model.goalPlaces, { model.goalName = it },
                            model::toggleGoalPlace, model::createGoal, model::openNavigation, model::home,
                        )
                        Screen.GOAL_EDIT_PLACE -> PlaceEditScreen(
                            navi, model.simulation, goal?.places?.firstOrNull { it.id == model.editingPlaceId }?.name.orEmpty(),
                            model::replaceEditingPlace, model::openNavigation, { model.goBack() },
                        )
                        Screen.SYNCING -> SyncingScreen()
                        Screen.PICKER -> if (goal != null) PlacePickerScreen(
                            goal, model.selectedPlaceId, model.simulation, model.message ?: navi.routeMessage, model::home, model::select,
                            onStartPressed, model::requestRoute, showNavigate = navi.available, model::editPlace,
                        ) else model.home()
                        Screen.VISIT -> model.finishedRecord?.let { record ->
                            VisitConfirmScreen(goal, record, model::confirmVisit, model::continueAfterRecord)
                        }
                        Screen.LINK -> PhoneLinkScreen(
                            model.linkCode?.code, model.linkCode?.expiresAtMillis ?: 0, model.linkCode?.webUrl, model.linkError,
                            model::leaveLink, model::startLink,
                        )
                        Screen.DONE -> ContinueOnPhoneScreen(model.finishedRecord, model::home)
                        Screen.OFFLINE -> {
                            val record = model.finishedRecord
                            val place = goal?.places?.firstOrNull { it.id == record?.placeId }
                            OfflineSavedScreen(
                                record, place?.let { MapPoint(it.latitude, it.longitude) }, store.token != null, model.syncing,
                                model::retrySync, model::home,
                            )
                        }
                        Screen.COMPLETE -> if (goal != null) GoalCompleteScreen(
                            model.store, goal, store.journey.records, model.syncing, model::continueAfterRecord, model::home,
                        ) else model.home()
                        else -> if (goal == null) WelcomeScreen(store.token != null, model::startNewGoal, model::openLink, model::unlink, hold)
                        else GoalHomeScreen(goal, store.token != null, store.outbox.isNotEmpty(), { model.onHomeAction(goal) }, model::openLink, model::unlink, hold)
                    }
                }
            }
        }
    }
}
