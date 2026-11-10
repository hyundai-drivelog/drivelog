package com.drivelog.app.feature.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.drivelog.app.core.platform.NaviPlace
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.journey.TripV
import com.drivelog.app.core.journey.summarizeTrip

/**
 * DRIVELOG's own front page, kept minimal: a short title, starting a drive, this month's counts, the latest drive
 * as one map row (the app's result, so it shows on the first screen), and two quiet buttons, centred in one column.
 * Parked only, like every screen but the recording one.
 */
@Composable
fun HomeScreen(
    records: List<RecordV>, routeMaps: RouteMapCache,
    onStart: () -> Unit, onRecord: (RecordV) -> Unit, onHistory: () -> Unit, onStamps: () -> Unit,
    onCommunity: (() -> Unit)?, onHold: (() -> Unit)?, linkControl: (@Composable () -> Unit)?,
    onWelcome: (() -> Unit)? = null,
    /** Saved places first, then recent ones: one tap opens the place's own screen, where guidance is asked for. */
    quickPlaces: List<QuickPlace> = emptyList(), onPlace: (NaviPlace) -> Unit = {},
    onPrivacy: (() -> Unit)? = null,
    trips: List<TripV> = emptyList(), onTrip: (TripV) -> Unit = {},
) {
    val d = LocalCarDimens.current
    val month = remember(records) { thisMonth(records) }
    val stamps = remember(records) { collectedStamps(records).size }
    val latest = remember(records) {
        records.filterNot { barelyMoved(it.distanceKm, it.route.size >= 2) }.maxByOrNull { it.startedAtMillis }
    }
    val latestTrip = remember(trips, records) { trips.maxByOrNull { summarizeTrip(it, records).lastAtMillis ?: it.createdAtMillis } }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(null, onHold, {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(10))) {
                linkControl?.invoke()
                if (onWelcome != null) SecondaryButton("앱 소개", "open-welcome", size = ButtonSize.SMALL, onClick = onWelcome)
            }
        })
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = d.u(10)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = d.u(760)).fillMaxWidth()) {
                T("오늘은 어디로 떠나볼까요?", d.t(30), bold = true, maxLines = 2)
                Spacer(Modifier.height(d.u(20)))
                PrimaryButton("드라이브 시작", "start-journey", Modifier.fillMaxWidth(), chevron = true, onClick = onStart)
                if (quickPlaces.isNotEmpty()) {
                    Spacer(Modifier.height(d.u(22)))
                    QuickPlaces(quickPlaces, onPlace)
                }
                Spacer(Modifier.height(d.u(40)))
                // Counts only: nothing here is a goal, a streak or a comparison.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("home-summary")) {
                    Stat("${month.size}", "회", "이번 달 드라이브", Modifier.weight(1f), size = 36)
                    Stat(distanceText(month.sumOf { it.distanceKm ?: 0.0 }), "km", "이번 달 거리", Modifier.weight(1f).padding(start = d.u(16)), size = 36)
                    Stat(
                        "$stamps", "/${StampPlaces.size}", "모은 스탬프 ›",
                        Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(10)))
                            .pressClick(onClick = onStamps)
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                contentDescription = "모은 스탬프 ${stamps}개, 전체 ${StampPlaces.size}개. 스탬프 보기"
                            }.padding(start = d.u(16)).testTag("home-stamp-summary"),
                        size = 36,
                    )
                }
                if (month.any { it.simulated }) {
                    Spacer(Modifier.height(d.u(8)))
                    T("이번 달 합계에 시뮬레이션 기록이 포함돼요", d.t(CarTextSize.CAPTION), Modifier.testTag("home-simulation-note"), color = muted())
                }
                if (latestTrip != null) {
                    Spacer(Modifier.height(d.u(CarSpace.L)))
                    RecentTripRow(latestTrip, records) { onTrip(latestTrip) }
                } else if (latest != null) {
                    Spacer(Modifier.height(d.u(CarSpace.L)))
                    LatestDriveRow(routeMaps, latest) { onRecord(latest) }
                }
                if (onPrivacy != null) {
                    Spacer(Modifier.height(d.u(12)))
                    SecondaryButton("개인정보와 데이터 사용", "open-privacy", Modifier.fillMaxWidth(), size = ButtonSize.SMALL, onClick = onPrivacy)
                }
                Spacer(Modifier.height(d.u(32)))
                // One row of secondary actions. The stamp count above already opens the stamps.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(10))) {
                    SecondaryButton("내 기록", "open-history", Modifier.weight(1f), size = ButtonSize.MEDIUM, onClick = onHistory)
                    if (onCommunity != null) {
                        SecondaryButton("추천 코스", "open-community", Modifier.weight(1f), size = ButtonSize.MEDIUM, onClick = onCommunity)
                    }
                }
            }
        }
    }
}

/**
 * The latest drive as one row, as an activity app's home leads with the last workout: its stored map tile, where it
 * went, when, and how far. Pressing it opens the drive's card. A simulated drive keeps its label, as on every card.
 */
@Composable
private fun LatestDriveRow(routeMaps: RouteMapCache, record: RecordV, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val shape = RoundedCornerShape(d.u(CarRadius.TILE))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(soft()).pressClick(onClick = onClick)
            .padding(d.u(CarSpace.S)).padding(end = d.u(CarSpace.S))
            .semantics(mergeDescendants = true) { contentDescription = "최근 드라이브, ${record.placeName}. 카드 보기" }
            .testTag("home-latest-drive"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordThumbnail(routeMaps, record, Modifier.size(d.u(112), d.u(80)))
        Spacer(Modifier.width(d.u(CarSpace.M)))
        Column(Modifier.weight(1f)) {
            T("최근 드라이브" + if (record.simulated) " · 시뮬레이션" else "", d.t(CarTextSize.CAPTION), color = muted(), maxLines = 1)
            T(record.placeName, d.t(20), bold = true, maxLines = 1)
            T(java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA).format(java.util.Date(record.startedAtMillis)), d.t(16), color = muted(), maxLines = 1)
        }
        if (record.distanceKm != null) {
            T(distanceText(record.distanceKm), d.t(28), display = true, tabular = true, maxLines = 1)
            T("km", d.t(16), Modifier.padding(start = d.u(4)), bold = true, maxLines = 1)
        }
        Spacer(Modifier.width(d.u(CarSpace.S)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(22), muted())
    }
}

/** A place to set out for straight from home: a saved one, or one gone to or searched for lately. */
data class QuickPlace(val place: NaviPlace, val saved: Boolean)

/** At most this many, so the list never pushes the month's figures off a parked screen. */
const val QUICK_PLACE_COUNT = 3

/** Saved places lead, then recent ones, each place once. */
fun quickPlaces(saved: List<NaviPlace>, recent: List<NaviPlace>): List<QuickPlace> {
    val out = mutableListOf<QuickPlace>()
    fun add(place: NaviPlace, isSaved: Boolean) {
        if (out.size < QUICK_PLACE_COUNT && out.none { it.place.sameLocation(place) || it.place.name == place.name }) out += QuickPlace(place, isSaved)
    }
    saved.forEach { add(it, true) }
    recent.forEach { add(it, false) }
    return out
}

@Composable
private fun QuickPlaces(places: List<QuickPlace>, onPlace: (NaviPlace) -> Unit) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxWidth().testTag("home-quick-places")) {
        Row(verticalAlignment = Alignment.Bottom) {
            T("바로 출발", d.t(20), bold = true, maxLines = 1)
            Spacer(Modifier.width(d.u(10)))
            T("저장한 장소 · 최근 장소", d.t(15), Modifier.padding(bottom = d.u(2)), color = muted(), maxLines = 1)
        }
        Spacer(Modifier.height(d.u(10)))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(14))).background(soft())) {
            places.forEachIndexed { index, quick ->
                if (index > 0) Spacer(Modifier.fillMaxWidth().padding(horizontal = d.u(18)).height(1.dp).background(line()))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(76), 64.dp)).pressClick(Role.Button) { onPlace(quick.place) }
                        .padding(horizontal = d.u(18), vertical = d.u(10)).testTag("home-quick-place"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val address = quick.place.address.takeIf { it.isNotBlank() && it != quick.place.name }
                    Column(Modifier.weight(1f)) {
                        T(quick.place.name, d.t(20), bold = true, maxLines = 1)
                        T(address ?: if (quick.saved) "저장한 장소" else "최근 장소", d.t(15), color = muted(), maxLines = 1)
                    }
                    // Words in place of the icons the home screen does not use.
                    if (address != null) T(if (quick.saved) "저장" else "최근", d.t(15), Modifier.padding(start = d.u(12)), color = muted(), maxLines = 1)
                }
            }
        }
    }
}
