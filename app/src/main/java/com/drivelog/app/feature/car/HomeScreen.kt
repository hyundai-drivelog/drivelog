package com.drivelog.app.feature.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.platform.NaviPlace

/**
 * DRIVELOG's own front page: a short title and starting a drive lead, with the phone link in the top bar. Under them,
 * one row of tiles opens the app's other places, each with a single line of counts. Parked only, like every screen
 * but the recording one.
 */
@Composable
fun HomeScreen(
    records: List<RecordV>,
    onStart: () -> Unit, onHistory: () -> Unit, onStamps: () -> Unit,
    onCommunity: (() -> Unit)?, onPrivacy: (() -> Unit)?,
    onHold: (() -> Unit)?, linkControl: (@Composable () -> Unit)?,
) {
    val d = LocalCarDimens.current
    val month = remember(records) { thisMonth(records) }
    val stamps = remember(records) { collectedStamps(records).size }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(null, onHold, {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(10))) {
                linkControl?.invoke()
            }
        })
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = d.u(760)).fillMaxWidth().padding(vertical = d.u(CarSpace.M))) {
                T("오늘은 어디로 떠나볼까요?", d.t(30), bold = true, maxLines = 2)
                Spacer(Modifier.height(d.u(20)))
                PrimaryButton("드라이브 시작", "start-journey", Modifier.fillMaxWidth(), chevron = true, onClick = onStart)
                Spacer(Modifier.height(d.u(CarSpace.XL)))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.u(12))) {
                    val tile = Modifier.weight(1f).fillMaxHeight()
                    // A simulated drive in the month's figures says so, as every card does.
                    val historyLine = when {
                        month.isEmpty() && records.isEmpty() -> "아직 기록이 없어요"
                        month.isEmpty() -> "이번 달 기록 없음"
                        else -> "이번 달 ${month.size}회 · ${distanceText(month.sumOf { it.distanceKm ?: 0.0 })}km" +
                            if (month.any { it.simulated }) " · 시뮬레이션 포함" else ""
                    }
                    HomeTile(CarGlyph.LIST, "내 기록", historyLine, "open-history", tile, onHistory)
                    HomeTile(CarGlyph.STAMP, "스탬프", "$stamps / ${StampPlaces.size} 모음", "open-stamps", tile, onStamps)
                    if (onCommunity != null) HomeTile(CarGlyph.ROUTE, "추천 코스", "둘러볼 드라이브 코스", "open-community", tile, onCommunity)
                }
                if (onPrivacy != null) {
                    Spacer(Modifier.height(d.u(CarSpace.M)))
                    SecondaryButton("개인정보와 데이터 사용", "open-privacy", Modifier.fillMaxWidth(), size = ButtonSize.SMALL, onClick = onPrivacy)
                }
            }
        }
    }
}

/** One of the home row's tiles: an icon, a name, one quiet line, and a chevron. The whole tile opens the place. */
@Composable
private fun HomeTile(glyph: CarGlyph, title: String, line: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    Column(
        modifier.heightIn(min = maxOf(d.u(132), 64.dp)).clip(RoundedCornerShape(d.u(CarRadius.TILE))).background(soft())
            .pressClick(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $line" }
            .padding(d.u(CarSpace.M) + d.u(2)).testTag(tag),
        // Top-aligned, so a line that wraps grows downward and the titles stay level across the row.
        verticalArrangement = Arrangement.Top,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CarIcon(glyph, d.u(30), fg())
            Spacer(Modifier.weight(1f))
            CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(20), muted())
        }
        Spacer(Modifier.height(d.u(CarSpace.M)))
        Column {
            T(title, d.t(20), bold = true, maxLines = 1)
            Spacer(Modifier.width(d.u(CarSpace.XS)))
            T(line, d.t(15), color = muted(), maxLines = 2)
        }
    }
}

/** A place to set out for: a saved one, or one gone to or searched for lately. */
data class QuickPlace(val place: NaviPlace, val saved: Boolean)

/** At most this many places in a quick list. */
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
