package com.drivelog.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drivelog.app.core.drive.DriveSourceType
import com.drivelog.app.core.network.DriveRecord
import java.math.BigDecimal

@Composable
fun DevelopmentScreen(
    state: DemoState,
    controlsAllowed: Boolean,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
    onNewPreview: () -> Unit,
    onDetail: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding().semantics { testTagsAsResourceId = true },
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("DRIVELOG", style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("Pleos Connect · 주행 기록 데모", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onClose) { Text("닫기") }
                }
            }
            item {
                Panel {
                    Text("서버 연결", style = MaterialTheme.typography.labelLarge)
                    Text(state.serverStatus, style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("server-status"))
                    Text("로컬 개발 전용 · 계정 구분 없음", style = MaterialTheme.typography.bodyMedium)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("loading"))
                }
            }
            item {
                if (!controlsAllowed) {
                    Panel {
                        Text("정차 확인 전 조작 잠금", style = MaterialTheme.typography.titleLarge)
                        Text("차량 UX 제한이 활성화되었거나 상태를 확인할 수 없습니다.")
                    }
                } else {
                    Text("정차 중 확인용 · 주행 중 조작하지 마세요.", color = MaterialTheme.colorScheme.secondary)
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val draft = state.preview
                        Text(
                            if (draft?.sourceType == DriveSourceType.PLEOS) "PLEOS · 실제 차량 데이터" else "MOCK · 가상 데모 데이터",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("data-source"),
                        )
                        Text(state.providerStatus, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("provider-status"))
                        if (draft != null) {
                            Text("주행 시간  ${draft.durationSeconds / 60}분 ${draft.durationSeconds % 60}초",
                                style = MaterialTheme.typography.titleMedium)
                            Text("주행 거리  ${number(draft.distanceKm)} km",
                                style = MaterialTheme.typography.titleLarge)
                            Text("평균 효율  ${efficiency(draft.averageEfficiency, draft.efficiencyUnit)}",
                                style = MaterialTheme.typography.titleMedium)
                            if (draft.sourceType == DriveSourceType.MOCK) Text("거리·시간·효율은 모두 샘플 값입니다.")
                        }
                    }
                }
            }
            if (controlsAllowed) {
                item {
                    Button(
                        onClick = onSave,
                        enabled = !state.busy && state.preview != null && state.saved == null && !state.uncertainSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("save-drive"),
                    ) { Text("주행 기록 서버 저장", style = MaterialTheme.typography.titleMedium) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onRefresh, enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("refresh-drives"),
                        ) { Text("최근 기록 조회") }
                        OutlinedButton(
                            onClick = onNewPreview, enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("new-sample"),
                        ) { Text("새 데모 샘플") }
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("api-error"))
                }
            }
            state.message?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("operation-result"))
                }
            }
            state.detail?.let { record ->
                item {
                    Panel {
                        Text("서버 상세 재조회 #${record.id}", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("saved-detail"))
                        RecordText(record)
                    }
                }
            }
            item {
                Text("최근 주행 기록 · 서버 조회", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
            }
            if (state.recent.isEmpty()) {
                item { Text("조회된 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.recent, key = { it.id }) { record ->
                Panel {
                    RecordText(record)
                    if (controlsAllowed) TextButton(
                        onClick = { onDetail(record.id) }, enabled = !state.busy,
                        modifier = Modifier.testTag("drive-detail-${record.id}"),
                    ) { Text("상세 조회 #${record.id}") }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun RecordText(record: DriveRecord) {
    Text("#${record.id} · ${record.sourceType}", style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary)
    Text("${number(record.distanceKm)} km · ${record.durationSeconds}초",
        style = MaterialTheme.typography.titleLarge)
    Text("평균 효율 ${efficiency(record.averageEfficiency, record.efficiencyUnit)}")
    Text("시작 ${record.startedAt}", style = MaterialTheme.typography.bodyMedium)
    Text("저장 ${record.createdAt}", style = MaterialTheme.typography.bodySmall)
}

private fun number(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
private fun efficiency(value: BigDecimal?, unit: String?): String =
    if (value == null || unit == null) "미지원 / 데이터 없음" else "${number(value)} $unit"
