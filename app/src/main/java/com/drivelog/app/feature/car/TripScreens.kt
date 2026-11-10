package com.drivelog.app.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.drivelog.app.core.journey.*
import com.drivelog.app.ui.theme.NightColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

@Composable
fun RecentTripRow(trip: TripV, records: List<RecordV>, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val summary = remember(trip, records) { summarizeTrip(trip, records) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(CarRadius.TILE))).background(soft())
        .pressClick(Role.Button, onClick = onClick).padding(d.u(16))
        .semantics { contentDescription = "최근 여행 ${trip.title}, 주행 ${summary.records.size}건, 여행 보기" }.testTag("home-latest-trip"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(d.u(112), d.u(88)).clip(RoundedCornerShape(d.u(8))).background(DriveCardBackground)) {
            TripRouteDrawing(summary.records, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(d.u(16)))
        Column(Modifier.weight(1f)) {
            T("최근 여행", d.t(16), color = muted())
            T(trip.title, d.t(22), bold = true, maxLines = 2)
            T("${summary.records.size}번의 주행 · ${tripDate(summary)}", d.t(16), color = muted(), maxLines = 2)
        }
        Spacer(Modifier.width(d.u(8)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(24), muted())
    }
}

internal fun tripDate(summary: TripSummary): String {
    val start = summary.firstAtMillis ?: return "주행을 추가해 주세요"
    val format = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    val first = format.format(Date(start))
    val last = format.format(Date(summary.lastAtMillis ?: start))
    return if (first == last) first else "$first — $last"
}

/** Each recorded leg is projected into the same space, without a line across separate drives. No network. */
@Composable
private fun TripRouteDrawing(records: List<RecordV>, modifier: Modifier = Modifier) {
    val pieces = remember(records) {
        records.flatMap { record -> styledRoute(record.route.toMapPoints().filter {
            it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }, 240).pieces }
            .filter { it.isNotEmpty() }
    }
    val points = remember(pieces) { pieces.flatten() }
    if (points.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { T("경로 정보가 없어요", LocalCarDimens.current.t(16), color = DriveCardMuted) }
        return
    }
    Canvas(modifier) {
        val pad = 18.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 4)
        val k = cos(Math.toRadians(points.map { it.latitude }.average()))
        val west = points.minOf { it.longitude * k }; val east = points.maxOf { it.longitude * k }
        val north = points.maxOf { it.latitude }; val south = points.minOf { it.latitude }
        val spanX = (east - west).coerceAtLeast(0.000001)
        val spanY = (north - south).coerceAtLeast(0.000001)
        val scale = minOf((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY)
        val left = (size.width - spanX * scale) / 2
        val top = (size.height - spanY * scale) / 2
        fun project(p: MapPoint) = Offset((left + (p.longitude * k - west) * scale).toFloat(), (top + (north - p.latitude) * scale).toFloat())
        val width = 2.5.dp.toPx()
        pieces.forEach { leg ->
            val path = Path()
            leg.forEachIndexed { index, point ->
                val at = project(point)
                if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
            }
            drawPath(path, DriveCardText, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(DriveCardText, width * 1.7f, project(leg.first()), style = Stroke(width))
            drawCircle(DriveCardText, width * 1.7f, project(leg.last()))
        }
    }
}

/** One trip, one postcard. Data is aggregated from its actual drives, never from a synthetic drive. */
@Composable
fun TripPostcard(trip: TripV, records: List<RecordV>, modifier: Modifier = Modifier) {
    val d = LocalCarDimens.current
    val summary = remember(trip, records) { summarizeTrip(trip, records) }
    val cover = summary.records.firstOrNull { it.id == trip.coverRecordId } ?: summary.records.lastOrNull()
    NightColors {
        Column(modifier.clip(RoundedCornerShape(d.u(CarRadius.CARD))).background(DriveCardBackground).padding(d.u(24)).testTag("trip-postcard")) {
            T("여행 기록 · ${tripDate(summary)}", d.t(16), color = DriveCardMuted, maxLines = 2)
            Spacer(Modifier.height(d.u(8)))
            T(trip.title, d.t(32), bold = true, color = DriveCardText, maxLines = 2)
            Box(Modifier.fillMaxWidth().height(d.u(if (d.stacked) 180 else 240))) {
                TripRouteDrawing(summary.records, Modifier.fillMaxSize())
                cover?.let { stampPlaceFor(it) }?.let { place ->
                    Box(Modifier.align(Alignment.TopEnd).padding(d.u(8))) { StampArt(place, true, d.u(56), ink = DriveCardText) }
                }
            }
            CardTearLine(d.u(24), Modifier.padding(vertical = d.u(12)))
            Row(verticalAlignment = Alignment.Bottom) {
                T(if (summary.unknownDistances == summary.records.size) "—" else distanceText(summary.distanceKm), d.t(56), display = true, tabular = true, color = DriveCardText)
                T(" km", d.t(22), Modifier.padding(bottom = d.u(6)), color = DriveCardMuted)
            }
            T("${summary.records.size}번의 주행 · ${elapsedText(summary.recordingSeconds)} 기록", d.t(18), color = DriveCardMuted)
            if (summary.unknownDistances > 0) T("거리 미상 ${summary.unknownDistances}건 제외", d.t(16), color = DriveCardMuted)
            if (summary.simulated) T("시뮬레이션 기록 포함", d.t(16), color = DriveCardMuted)
            Spacer(Modifier.height(d.u(20)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T(cover?.placeName ?: "아직 주행이 없어요", d.t(16), Modifier.weight(1f), color = DriveCardMuted, maxLines = 2)
                Spacer(Modifier.width(d.u(12)))
                BrandWordmark(d.t(16), DriveCardText)
            }
        }
    }
}

@Composable
fun TripArchive(trips: List<TripV>, records: List<RecordV>, onTrip: (TripV) -> Unit, onCreate: () -> Unit, onDrives: () -> Unit) {
    val d = LocalCarDimens.current
    val sorted = remember(trips, records) { trips.sortedByDescending { summarizeTrip(it, records).lastAtMillis ?: it.createdAtMillis } }
    val ungrouped = remember(trips, records) {
        val grouped = trips.flatMap { it.recordIds }.toSet()
        records.count { it.id !in grouped }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
        item(key = "intro") {
            Column {
                T("달린 길을, 하나의 여행으로", d.t(28), bold = true)
                Spacer(Modifier.height(d.u(8)))
                T("여러 주행과 방문 도장을 한 장에 담아보세요", d.t(18), color = muted(), maxLines = 2)
                Spacer(Modifier.height(d.u(20)))
                PrimaryButton("여행 만들기", "create-trip", Modifier.fillMaxWidth(), enabled = ungrouped > 0, onClick = onCreate)
                if (records.isNotEmpty() && ungrouped == 0) {
                    Spacer(Modifier.height(d.u(12)))
                    T("모든 주행이 여행에 담겨 있어요. 기존 여행을 편집하거나 새 드라이브를 기록해 주세요", d.t(18), color = muted(), maxLines = 3)
                }
            }
        }
        if (sorted.isEmpty()) item(key = "empty") {
            Column(Modifier.padding(vertical = d.u(24))) {
                T(if (records.isEmpty()) "첫 드라이브가 여행의 시작이에요" else "기록한 주행을 골라 첫 여행을 만들어보세요", d.t(24), bold = true, maxLines = 2)
                Spacer(Modifier.height(d.u(8)))
                T("개별 주행은 그대로 보관되고, 여행에서 함께 볼 수 있어요", d.t(18), color = muted(), maxLines = 2)
                Spacer(Modifier.height(d.u(16)))
                SecondaryButton("주행 기록 보기", "view-drives", Modifier.fillMaxWidth(), onClick = onDrives)
            }
        }
        items(sorted, key = { it.id }) { trip ->
            Column(Modifier.pressClick(Role.Button) { onTrip(trip) }.semantics { contentDescription = "${trip.title}, 여행 보기" }) {
                TripPostcard(trip, records, Modifier.fillMaxWidth())
                T("여행 열기 ›", d.t(18), Modifier.padding(top = d.u(8)), color = muted())
            }
        }
    }
}

@Composable
fun TripDetailScreen(trip: TripV?, records: List<RecordV>, onBack: () -> Unit, onEdit: () -> Unit, onRecord: (RecordV) -> Unit, onDelete: () -> Unit, busy: Boolean, error: String?) {
    val d = LocalCarDimens.current
    var confirmDelete by rememberConfirmState(trip?.id)
    Frame(onBack) {
        if (trip == null) {
            Column { T("이 여행을 찾을 수 없어요", d.t(24)); SecondaryButton("돌아가기", "missing-trip-back", onClick = onBack) }
        } else {
            val summary = remember(trip, records) { summarizeTrip(trip, records) }
            val visits = remember(summary) { collectedStamps(summary.records).values.toList() }
            val timeline: LazyListScope.() -> Unit = {
                item(key = "timeline-title") {
                    T("여행의 순간", d.t(26), bold = true)
                    if (summary.restSeconds > 0) T("기록에 남긴 휴게 ${elapsedText(summary.restSeconds)}", d.t(18), color = muted())
                }
                if (summary.records.isEmpty()) item(key = "no-drives") { T("주행 기록이 없어도 여행 이름은 보관해요. 편집에서 다른 주행을 담을 수 있어요", d.t(18), maxLines = 3, color = muted()) }
                items(summary.records, key = { "drive-${it.id}" }) { record ->
                    TripDriveRow(record, "기록 보기 ›", enabled = !busy, onClick = { onRecord(record) })
                }
                if (visits.isNotEmpty()) {
                    item(key = "visits-title") { T("이 여행에서 방문한 곳", d.t(26), bold = true) }
                    items(visits, key = { "stamp-${it.place.id}" }) { stamp -> StampLine(StampResult(stamp.place, false), press = false) }
                }
            }
            val actions: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(d.u(12))) {
                    PrimaryButton("여행 이름·주행 편집", "edit-trip", Modifier.fillMaxWidth(), enabled = !busy, onClick = onEdit)
                    if (error != null) T(error, d.t(18), Modifier.testTag("trip-error"), maxLines = 3)
                    T("여행 묶음은 이 차량에 보관돼요", d.t(16), color = muted())
                    SecondaryButton(if (confirmDelete) "한 번 더 누르면 여행 묶음 해제" else "여행 묶음 해제", "delete-trip", Modifier.fillMaxWidth(), enabled = !busy, danger = confirmDelete, size = ButtonSize.SMALL) {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }
                    T("개별 주행과 스탬프는 지우지 않아요", d.t(16), Modifier.padding(top = d.u(8)), color = muted())
                }
            }
            if (d.stacked) LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
                item(key = "cover") { TripPostcard(trip, records, Modifier.fillMaxWidth()) }
                item(key = "actions") { actions() }
                timeline()
            } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.u(32))) {
                Column(Modifier.weight(0.48f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
                    TripPostcard(trip, records, Modifier.fillMaxWidth())
                    actions()
                }
                LazyColumn(Modifier.weight(0.52f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d.u(16)), content = timeline)
            }
        }
    }
}

@Composable
private fun TripDriveRow(record: RecordV, trailing: String, selected: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val format = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(d.u(12)))
        .background(if (selected == true) soft() else androidx.compose.ui.graphics.Color.Transparent)
        .pressClick(Role.Button, enabled = enabled, onClick = onClick)
        .semantics { if (selected != null) this.selected = selected }
        .padding(horizontal = d.u(16), vertical = d.u(12)), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(format.format(Date(record.startedAtMillis)), d.t(16), color = muted())
            T(record.placeName, d.t(22), bold = true, maxLines = 2)
            T("${record.distanceKm?.let(::distanceText) ?: "거리 미상"}${if (record.distanceKm != null) " km" else ""} · ${elapsedText(record.elapsedSeconds)}${if (record.simulated) " · 시뮬레이션" else ""}", d.t(16), color = muted(), maxLines = 2)
        }
        Spacer(Modifier.width(d.u(16)))
        T(trailing, d.t(18), color = muted(), maxLines = 2)
    }
}

@Composable
fun TripEditorScreen(title: String, selectedIds: List<String>, coverId: String?, editingId: String?, trips: List<TripV>, records: List<RecordV>, busy: Boolean, error: String?, onTitle: (String) -> Unit, onToggle: (String) -> Unit, onCover: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sortedRecords = remember(records) { records.sortedByDescending { it.startedAtMillis } }
    val owners = remember(trips, editingId) { trips.filterNot { it.id == editingId }.flatMap { trip -> trip.recordIds.map { it to trip.title } }.toMap() }
    Frame(onBack) {
        Column(Modifier.fillMaxSize()) {
            Heading(if (editingId == null) "여행 만들기" else "여행 편집", 28)
            Spacer(Modifier.height(d.u(12)))
            T("여행 이름", d.t(16), color = muted())
            BasicTextField(title, onTitle, Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(12)))
                .background(soft()).padding(d.u(16)).semantics { contentDescription = "여행 이름, 최대 ${TRIP_TITLE_LIMIT}자" }.testTag("trip-title-input"),
                enabled = !busy, singleLine = true, textStyle = TextStyle(color = fg(), fontSize = d.t(24)), cursorBrush = SolidColor(fg()),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
            Spacer(Modifier.height(d.u(12)))
            T("주행 ${selectedIds.size}건 선택 · 대표 장소를 정할 수 있어요", d.t(18), color = muted(), maxLines = 2)
            if (error != null) T(error, d.t(18), Modifier.testTag("trip-editor-error"), maxLines = 3)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                items(sortedRecords, key = { it.id }) { record ->
                    val selected = record.id in selectedIds
                    val owner = owners[record.id]
                    Column {
                        TripDriveRow(record, if (owner != null) "다른 여행에 담김" else if (selected) "선택됨 ✓" else "선택", selected, !busy && owner == null) { onToggle(record.id) }
                        if (owner != null) T(owner, d.t(16), Modifier.padding(start = d.u(16)), color = muted(), maxLines = 1)
                        if (selected) SecondaryButton(if (coverId == record.id) "대표 장소로 선택됨" else "이 주행의 장소를 대표로", "cover-${record.id}", Modifier.fillMaxWidth(), size = ButtonSize.SMALL, enabled = !busy && coverId != record.id) { onCover(record.id) }
                    }
                }
                if (records.isEmpty()) item { T("여행에 담을 주행이 없어요. 먼저 드라이브를 기록해 주세요", d.t(20), maxLines = 3) }
            }
            PrimaryButton(if (editingId == null) "여행으로 묶기" else "변경 저장", "save-trip", Modifier.fillMaxWidth(), enabled = title.isNotBlank() && selectedIds.isNotEmpty() && !busy, loading = busy) { keyboard?.hide(); onSave() }
        }
    }
}

@Composable
fun TripPickerScreen(trips: List<TripV>, records: List<RecordV>, busy: Boolean, error: String?, onTrip: (TripV) -> Unit, onCreate: () -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onBack) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.u(16))) {
            item {
                Heading("어느 여행에 담을까요?", 28)
                Spacer(Modifier.height(d.u(16)))
                PrimaryButton("새 여행 만들기", "new-trip-from-drive", Modifier.fillMaxWidth(), enabled = !busy, onClick = onCreate)
            }
            if (error != null) item { T(error, d.t(18), maxLines = 3) }
            items(trips, key = { it.id }) { trip ->
                val summary = summarizeTrip(trip, records)
                SecondaryButton("${trip.title} · 주행 ${summary.records.size}건", "add-to-${trip.id}", Modifier.fillMaxWidth(), enabled = !busy) { onTrip(trip) }
            }
            if (trips.isEmpty()) item { T("첫 여행을 만들어 이 주행부터 담아보세요", d.t(20), color = muted(), maxLines = 2) }
        }
    }
}
