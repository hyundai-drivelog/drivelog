package com.drivelog.app.feature.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import com.drivelog.app.core.platform.NaviPlace
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.drivelog.app.core.journey.RecordV
import java.util.Calendar

/**
 * DRIVELOG's own front page: the latest drive's card, and beside it the one thing to do next, starting a drive.
 * Finding a place lives on the screen "드라이브 시작" opens. Parked only, like every screen but the recording one.
 */
@Composable
fun HomeScreen(
    records: List<RecordV>, routeMaps: RouteMapCache, simulation: Boolean,
    onStart: () -> Unit, onRecord: (RecordV) -> Unit, onHistory: () -> Unit, onStamps: () -> Unit,
    onCommunity: (() -> Unit)?, onHold: (() -> Unit)?, linkControl: (@Composable () -> Unit)?,
    onWelcome: (() -> Unit)? = null,
    /** Saved places first, then recent ones: one tap opens the place's own screen, where guidance is asked for. */
    quickPlaces: List<QuickPlace> = emptyList(), onPlace: (NaviPlace) -> Unit = {},
) {
    val d = LocalCarDimens.current
    val latest = remember(records) {
        records.filterNot { barelyMoved(it.distanceKm, it.route.size >= 2) }.maxByOrNull { it.startedAtMillis }
    }
    val card: @Composable (Modifier) -> Unit = { modifier ->
        if (latest != null) LatestDriveCard(routeMaps, latest, modifier) { onRecord(latest) }
        else FirstDriveCard(modifier)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(null, onHold, {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(10))) {
                if (simulation) SimulationBadge()
                linkControl?.invoke()
                if (onWelcome != null) SecondaryButton("앱 소개", "open-welcome", size = ButtonSize.SMALL, icon = CarGlyph.INFO, onClick = onWelcome)
            }
        })
        if (d.stacked) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(18))) {
                HomeSide(records, quickPlaces, onStart, onPlace, onHistory, onStamps, onCommunity)
                card(Modifier.fillMaxWidth().height(d.u(520)))
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = d.u(10)), horizontalArrangement = Arrangement.spacedBy(d.u(32))) {
                card(Modifier.weight(0.52f).fillMaxHeight())
                // Top and bottom line up with the card beside it, instead of floating in the middle.
                Column(Modifier.weight(0.48f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.SpaceBetween) {
                    HomeSide(records, quickPlaces, onStart, onPlace, onHistory, onStamps, onCommunity)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.HomeSide(
    records: List<RecordV>, quickPlaces: List<QuickPlace>, onStart: () -> Unit, onPlace: (NaviPlace) -> Unit,
    onHistory: () -> Unit, onStamps: () -> Unit, onCommunity: (() -> Unit)?,
) {
    val d = LocalCarDimens.current
    val month = remember(records) { thisMonth(records) }
    val stamps = remember(records) { collectedStamps(records).size }
    Column {
        T(greeting(), d.t(20), Modifier.testTag("home-greeting"), color = muted())
        Spacer(Modifier.height(d.u(6)))
        T("오늘은 어디로 떠나볼까요?", d.t(42), display = true, maxLines = 2)
        Spacer(Modifier.height(d.u(22)))
        PrimaryButton("드라이브 시작", "start-journey", Modifier.fillMaxWidth(), chevron = true, icon = CarGlyph.ROUTE, onClick = onStart)
        if (quickPlaces.isNotEmpty()) {
            Spacer(Modifier.height(d.u(22)))
            QuickPlaces(quickPlaces, onPlace)
        }
    }
    // Two groups let the wide layout align its first and last actions with the card's edges.
    Column(Modifier.padding(top = d.u(26))) {
        // Counts only: nothing here is a goal, a streak or a comparison.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("home-summary")) {
            Stat("${month.size}", "회", "이번 달 드라이브", Modifier.weight(1f), size = 40)
            VerticalRule(Modifier.fillMaxHeight())
            Stat(distanceText(month.sumOf { it.distanceKm ?: 0.0 }), "km", "이번 달 거리", Modifier.weight(1f).padding(start = d.u(16)), size = 40)
            VerticalRule(Modifier.fillMaxHeight())
            Stat(
                "$stamps", "/${StampPlaces.size}", "모은 스탬프",
                Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(10)))
                    .pressClick(onClick = onStamps)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = "모은 스탬프 ${stamps}개, 전체 ${StampPlaces.size}개. 스탬프 보기"
                    }.padding(start = d.u(16)).testTag("home-stamp-summary"),
                size = 40,
            )
        }
        if (month.any { it.simulated }) {
            Spacer(Modifier.height(d.u(8)))
            T("이번 달 수치에 시뮬레이션 기록이 포함돼요", d.t(16), color = muted(), maxLines = 2)
        }
        Spacer(Modifier.height(d.u(26)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(10))) {
            SecondaryButton("내 기록", "open-history", Modifier.weight(1f), size = ButtonSize.MEDIUM, icon = CarGlyph.LIST, onClick = onHistory)
            SecondaryButton("스탬프", "open-stamps", Modifier.weight(1f), size = ButtonSize.MEDIUM, icon = CarGlyph.STAMP, onClick = onStamps)
        }
        if (onCommunity != null) {
            Spacer(Modifier.height(d.u(10)))
            SecondaryButton("추천 코스", "open-community", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, icon = CarGlyph.PIN, onClick = onCommunity)
        }
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
                    Box(Modifier.size(d.u(44)).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                        CarIcon(if (quick.saved) CarGlyph.BOOKMARK else CarGlyph.CLOCK, d.u(24), fg())
                    }
                    Spacer(Modifier.width(d.u(14)))
                    Column(Modifier.weight(1f)) {
                        T(quick.place.name, d.t(20), bold = true, maxLines = 1)
                        val address = quick.place.address.takeIf { it.isNotBlank() && it != quick.place.name }
                        T(address ?: if (quick.saved) "저장한 장소" else "최근 장소", d.t(15), color = muted(), maxLines = 1)
                    }
                    CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(22), muted())
                }
            }
        }
    }
}

/** Before the first drive: the mood photo with a line saying what will appear here. */
@Composable
private fun FirstDriveCard(modifier: Modifier) {
    val d = LocalCarDimens.current
    Box(modifier.clip(RoundedCornerShape(d.u(14))).background(DriveCardBackground).testTag("first-drive")) {
        PlacePhoto("", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.60f)))
        T("첫 드라이브를 기다리고 있어요", d.t(17), Modifier.align(Alignment.TopStart).padding(d.u(22)), color = DriveCardText, bold = true)
        Column(Modifier.align(Alignment.BottomStart).padding(d.u(22))) {
            T("달린 길이 카드로 남아요", d.t(32), color = DriveCardText, display = true, maxLines = 2)
            Spacer(Modifier.height(d.u(10)))
            T("목적지를 고르고 기록을 시작해 보세요.\n정차 후 경로와 거리가 여기에 남아요.", d.t(17), color = DriveCardText, maxLines = 3)
        }
    }
}

private fun greeting(now: Calendar = Calendar.getInstance()): String = when (now.get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "좋은 아침이에요"
    in 11..16 -> "좋은 오후예요"
    in 17..21 -> "좋은 저녁이에요"
    else -> "편안한 밤 되세요"
}
