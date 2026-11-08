package com.drivelog.app.feature.diagnostics

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drivelog.app.BuildConfig
import com.drivelog.app.data.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Real SDK call results for judges and developers. Reached by holding the "DRIVELOG" title on the home screen while parked. */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    var results by mutableStateOf(emptyList<DiagnosticResult>())
        private set
    var busy by mutableStateOf(false)
        private set
    private val sdk = PleosRuntimeDataSource(application) { publish(it) }

    init { refreshPermissions() }

    private fun publish(result: DiagnosticResult) {
        Log.i("DriveLogSDK", "${result.at} ${result.target} ${result.status} code=${result.code ?: "none"}")
        viewModelScope.launch { results = (results.filterNot { it.target == result.target } + result).takeLast(40) }
    }

    fun refreshPermissions() { permissionResults(getApplication()).forEach(::publish) }

    private fun action(target: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publish(DiagnosticResult(target, ProbeStatus.ERROR, "호출 예외 · ${error.javaClass.simpleName}", error.javaClass.name)) }
            finally { busy = false; refreshPermissions() }
        }
    }

    fun vehicle() = action("vehicle") { sdk.vehicle() }
    fun location() = action("location") { sdk.location() }
    fun navigation(action: NavigationAction, destination: DriveDestination = demoDestinations.first()) =
        action("navi.$action") { sdk.navigation(action, destination) }

    override fun onCleared() { sdk.close(); super.onCleared() }
}

@Composable
fun DiagnosticsRoute(simulation: Boolean, onToggleSimulation: () -> Unit, onBack: () -> Unit, model: DiagnosticsViewModel = viewModel()) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshPermissions() }
    val enabled = !model.busy
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp).testTag("diagnostics-back")) { Text("돌아가기") }
            Text("SDK Diagnostics", style = MaterialTheme.typography.headlineMedium)
            Text("SDK 포함: ${BuildConfig.PLEOS_SDK_INCLUDED} · Vehicle 2.3.2 / Navi 3.0.5 / Fused 1.1.3 / TTS 3.3.1")
            Text("실제 호출 결과만 표시합니다.")
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (BuildConfig.DEBUG) item {
            OutlinedButton(onClick = onToggleSimulation, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("toggle-simulation")) {
                Text(if (simulation) "시뮬레이션 켜짐 · 누르면 실제 SDK 사용" else "시뮬레이션 꺼짐 · 누르면 켜기")
            }
        }
        item { Button(onClick = model::vehicle, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("probe-vehicle")) { Text("Vehicle 상태 조회") } }
        item { Button(onClick = model::location, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("probe-location")) { Text("현재 위치 조회") } }
        item {
            OutlinedButton(onClick = {
                val name = "pleos.car.permission.FUSED_LOCATION"
                val defined = runCatching { context.packageManager.getPermissionInfo(name, 0) }.isSuccess
                if (defined && context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED) permission.launch(name)
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("request-location-permission")) { Text("위치 권한 요청") }
        }
        item { Button(onClick = { model.navigation(NavigationAction.STATE) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("probe-navi")) { Text("NaviHelper 상태 확인") } }
        item { OutlinedButton(onClick = { model.navigation(NavigationAction.FOREGROUND) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("probe-foreground")) { Text("Pleos 내비게이션 화면 요청") } }
        if (BuildConfig.DEBUG) items(demoDestinations, key = { it.name }) { destination ->
            OutlinedButton(onClick = { model.navigation(NavigationAction.ROUTE, destination) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("route-" + demoDestinations.indexOf(destination))) { Text("경로 요청 · " + destination.name) }
        }
        items(model.results.sortedBy { it.target }, key = { it.target }) { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.target, style = MaterialTheme.typography.titleMedium)
                    Text(result.status.name)
                    Text(result.message)
                    result.code?.let { Text("code: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
