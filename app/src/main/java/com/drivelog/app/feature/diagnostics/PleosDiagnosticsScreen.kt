package com.drivelog.app.feature.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.drivelog.app.BuildConfig
import com.drivelog.app.data.sdk.*
import com.drivelog.app.feature.drive.DriveUiState

@Composable
fun PleosDiagnosticsScreen(state: DriveUiState, environment: String, allowed: Boolean,
        onVehicle: () -> Unit, onLocation: () -> Unit, onNavi: (NavigationAction) -> Unit, onPermission: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SDK Diagnostics", style = MaterialTheme.typography.headlineMedium)
            val verified = state.results.any { it.target in listOf("vehicle.init", "navi.init") && it.status == ProbeStatus.SUCCESS }
            Text(if (verified) "Pleos Connect · 실제 SDK 서비스 응답 확인" else environment, modifier = Modifier.testTag("runtime-environment"))
            Text("SDK 포함: ${BuildConfig.PLEOS_SDK_INCLUDED} · Vehicle 2.3.2 / Navi 3.0.5 / Fused 1.1.3")
            Text("실제 호출 결과만 표시 · Mock 결과를 Diagnostics 성공으로 사용하지 않습니다.")
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!allowed) Text("차량 UX 상태 확인 불가 또는 제한 활성 · 조작 잠금", color = MaterialTheme.colorScheme.error)
        }
        item { Button(onClick = onVehicle, enabled = allowed && !state.busy, modifier = Modifier.fillMaxWidth().testTag("probe-vehicle")) { Text("Vehicle 상태 조회") } }
        item { Button(onClick = onLocation, enabled = allowed && !state.busy, modifier = Modifier.fillMaxWidth().testTag("probe-location")) { Text("현재 위치 조회") } }
        item { OutlinedButton(onClick = onPermission, enabled = allowed && !state.busy, modifier = Modifier.fillMaxWidth().testTag("request-location-permission")) { Text("위치 권한 요청") } }
        item { Button(onClick = { onNavi(NavigationAction.STATE) }, enabled = allowed && !state.busy, modifier = Modifier.fillMaxWidth().testTag("probe-navi")) { Text("NaviHelper 상태 확인") } }
        item { OutlinedButton(onClick = { onNavi(NavigationAction.ROUTE) }, enabled = allowed && !state.busy, modifier = Modifier.fillMaxWidth().testTag("probe-route")) { Text("${state.destination.name} · 경로 요청") } }
        state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { OutlinedButton(onClick = { onNavi(NavigationAction.FOREGROUND) }, enabled = allowed && !state.busy, modifier = Modifier.fillMaxWidth().testTag("probe-foreground")) { Text("Pleos 내비게이션 화면 요청") } }
        items(state.results.sortedBy { it.target }, key = { it.target }) { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.target, style = MaterialTheme.typography.titleMedium)
                    Text(result.status.name, color = if (result.status == ProbeStatus.SUCCESS) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                    Text(result.message)
                    result.code?.let { Text("code: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(result.at, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
