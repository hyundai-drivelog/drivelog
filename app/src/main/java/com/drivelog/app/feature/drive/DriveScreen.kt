package com.drivelog.app.feature.drive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drivelog.app.data.drive.routeDistanceKm
import com.drivelog.app.data.sdk.*
import java.util.Locale

fun distanceLabel(value: Double?): String = value?.let { String.format(Locale.US, "%.2f km", it) } ?: "미지원 / 좌표 대기"

@Composable
fun DriveScreen(state: DriveUiState, allowed: Boolean, onMode: (RecordingMode) -> Unit,
        onDestination: (DriveDestination) -> Unit, onStart: () -> Unit, onStop: () -> Unit, onCard: () -> Unit,
        onNavigate: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("오늘의 드라이브", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("${state.mode} · ${if (state.mode == RecordingMode.MOCK) "가상 차량 + 가상 GPS" else "실제 FusedLocation · 앱 세션 시간"}",
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("recording-source"))
        }
        if (!state.recording && allowed) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecordingMode.entries.forEach { mode -> FilterChip(selected = state.mode == mode,
                        onClick = { onMode(mode) }, label = { Text(if (mode == RecordingMode.MOCK) "MOCK 데모" else "PLEOS 실제") },
                        modifier = Modifier.testTag("mode-$mode")) }
                }
            }
            item {
                Text("목적지 선택", style = MaterialTheme.typography.titleLarge)
                demoDestinations.forEach { destination ->
                    OutlinedButton(onClick = { onDestination(destination) }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (state.destination == destination) "✓ " else "") + destination.name)
                    }
                }
                if (state.mode == RecordingMode.PLEOS) OutlinedButton(onClick = onNavigate,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Pleos 내비게이션 경로 요청 · 별도 화면")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.destination.name, style = MaterialTheme.typography.titleLarge)
                    Text("${state.elapsedSeconds / 60}분 ${state.elapsedSeconds % 60}초", style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.testTag("elapsed-time"))
                    Text(distanceLabel(routeDistanceKm(state.points)), style = MaterialTheme.typography.headlineMedium)
                    Text("좌표 기반 거리 · 수신 ${state.points.size}개")
                    val last = state.points.lastOrNull()
                    Text(last?.let { String.format(Locale.US, "%.6f, %.6f", it.latitude, it.longitude) } ?: "현재 위치: 수신 대기")
                    Text(if (state.mode == RecordingMode.MOCK) "평균 효율 14.2 km/L · 가상 샘플" else "평균 연비/전비: 미지원 · 세션 일치 및 단위 검증 전")
                    if (state.mode == RecordingMode.MOCK) Text("시뮬레이션 좌표이며 실제 차량 위치가 아닙니다.")
                }
            }
        }
        item { RouteSilhouette(state.points, Modifier.testTag("drive-route")) }
        if (state.points.isEmpty()) item { Text("경로는 좌표 수신 후 표시됩니다.") }
        if (allowed) {
            item {
                if (state.recording) Button(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("stop-recording")) { Text("주행 기록 종료") }
                else Button(onClick = onStart, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("start-recording")) { Text("주행 시작") }
            }
        } else item { Text("차량 UX 제한 활성 또는 상태 미확인 · 입력 및 편집 잠금", color = MaterialTheme.colorScheme.error) }
        state.summary?.let { summary ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("주행 완료 · ${summary.mode}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("drive-summary"))
                        Text("${distanceLabel(summary.distanceKm)} · ${summary.durationSeconds}초 · ${summary.points.size}개 좌표")
                        Text("${summary.startedAt.take(10)} · ${summary.destination.name}")
                        if (allowed) Button(onClick = onCard, modifier = Modifier.fillMaxWidth().testTag("open-card-editor")) { Text("사진 카드 만들기") }
                    }
                }
            }
        }
        state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
        item { Text("차량 운행 중에는 사진 편집과 복잡한 입력을 제공하지 않습니다.", style = MaterialTheme.typography.bodySmall) }
    }
}
