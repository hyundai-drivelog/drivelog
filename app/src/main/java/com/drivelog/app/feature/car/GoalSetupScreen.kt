package com.drivelog.app.feature.car

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.core.platform.NaviState

private enum class PlaceSource(val label: String, val emptyMessage: String) {
    BOOKMARKS("즐겨찾기", "내비에 저장한 즐겨찾기가 없어요"),
    RECENTS("최근 목적지", "최근 목적지가 없어요"),
    CURRENT("현재 목적지", "내비에 설정된 목적지가 없어요"),
}

/** Unset home and work bookmark slots arrive without a name or coordinates; they are not places. */
private val List<NaviPlace>.distinctPlaces get() = filter { it.name.isNotBlank() && (it.latitude != 0.0 || it.longitude != 0.0) }.distinctBy { it.latitude to it.longitude }

/** Bookmarks and recent destinations from NaviHelper. The guide puts presets and recents before typing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NaviPlaceList(
    navi: NaviState, simulation: Boolean, isSelected: (NaviPlace) -> Boolean, onTap: (NaviPlace) -> Unit, onOpenNavigation: () -> Unit, modifier: Modifier = Modifier,
) {
    val d = LocalCarDimens.current
    var tab by remember { mutableIntStateOf(0) }
    val source = PlaceSource.entries[tab]
    val simulatedList = simulation && (!navi.available || (navi.bookmarks.isEmpty() && navi.recents.isEmpty() && navi.currentDestination == null))
    val places = when {
        simulatedList -> simulatedNaviPlaces
        source == PlaceSource.BOOKMARKS -> navi.bookmarks
        source == PlaceSource.RECENTS -> navi.recents
        else -> listOfNotNull(navi.currentDestination)
    }.distinctPlaces
    Column(modifier) {
        if (!simulatedList) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(8))) {
                PlaceSource.entries.forEachIndexed { index, item ->
                    val active = index == tab
                    Box(
                        Modifier.weight(1f).heightIn(min = maxOf(d.u(46), 64.dp)).clip(RoundedCornerShape(d.u(10)))
                            .background(if (active) fg() else soft())
                            .combinedClickable(role = Role.Tab, onClick = { tab = index })
                            .semantics { selected = active }.testTag("source-${item.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) { T(item.label, d.t(17), color = if (active) MaterialTheme.colorScheme.background else fg(), bold = true, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(d.u(10)))
        }
        if (places.isEmpty()) {
            // Searching belongs to the navigation app: send the driver there rather than building a second search.
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                T(if (navi.available) source.emptyMessage else "내비게이션에 연결할 수 없어요", d.t(19), Modifier.testTag("places-empty"), color = muted(), align = TextAlign.Center)
                if (navi.available) {
                    Spacer(Modifier.height(d.u(6)))
                    T("내비에서 장소를 찾아 즐겨찾기에 추가해 보세요", d.t(16), color = muted(), align = TextAlign.Center)
                    Spacer(Modifier.height(d.u(14)))
                    SecondaryButton("내비 열기", "open-navigation", Modifier.width(d.u(240)), onClick = onOpenNavigation)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(7))) {
                items(places, key = { "${it.latitude},${it.longitude}" }) { place ->
                    val chosen = isSelected(place)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(60), 64.dp)).clip(RoundedCornerShape(d.u(10)))
                            .background(if (chosen) MaterialTheme.colorScheme.background else soft())
                            .then(if (chosen) Modifier.border(d.u(2), fg(), RoundedCornerShape(d.u(10))) else Modifier)
                            .combinedClickable(role = Role.Checkbox, onClick = { onTap(place) })
                            .padding(horizontal = d.u(16)).testTag("navi-place"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(place.name, d.t(19), bold = true, maxLines = 1)
                            if (place.address.isNotBlank()) T(place.address, d.t(15), color = muted(), maxLines = 1)
                        }
                        if (chosen) CarIcon(CarGlyph.CHECK, d.u(24), fg())
                    }
                }
            }
        }
    }
}

// --- 목표 만들기 -----------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GoalNewScreen(
    navi: NaviState, simulation: Boolean, name: String, selected: List<NaviPlace>,
    onName: (String) -> Unit, onToggle: (NaviPlace) -> Unit, onCreate: () -> Unit, onOpenNavigation: () -> Unit, onBack: () -> Unit,
) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, null, if (simulation) ({ SimulationBadge() }) else null)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Panes(0.46f, left = {
                Column(Modifier.padding(start = d.u(42), end = d.u(6))) {
                    T("어디로 떠날까요?", d.t(36), bold = true)
                    Spacer(Modifier.height(d.u(4)))
                    T("가고 싶은 곳 세 군데를 고르세요", d.t(19), color = muted())
                    Spacer(Modifier.height(d.u(14)))
                    goalNamePresets.forEach { preset ->
                        val active = preset == name
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(46), 64.dp))
                                .combinedClickable(role = Role.RadioButton, onClick = { onName(preset) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(d.u(22)).clip(CircleShape).border(d.u(2), fg(), CircleShape), contentAlignment = Alignment.Center) {
                                if (active) Box(Modifier.size(d.u(12)).clip(CircleShape).background(fg()))
                            }
                            Spacer(Modifier.width(d.u(12)))
                            T(preset, d.t(18), bold = active, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(d.u(10)))
                    T("${selected.size} / 3곳 선택", d.t(18), Modifier.testTag("selected-count"))
                    Spacer(Modifier.height(d.u(6)))
                    ProgressDots(3, selected.size, Modifier.padding(end = d.u(80)))
                    Spacer(Modifier.height(d.u(14)))
                    PrimaryButton("목표 만들기", "create-goal", Modifier.fillMaxWidth(), chevron = true, enabled = selected.size == 3, onClick = onCreate)
                }
            }, right = {
                NaviPlaceList(
                    navi, simulation,
                    isSelected = { place -> selected.any { it.latitude == place.latitude && it.longitude == place.longitude } },
                    onTap = onToggle, onOpenNavigation = onOpenNavigation, modifier = paneFill(420).padding(bottom = d.u(8)),
                )
            })
        }
    }
}

// --- 방문 전 장소 변경 -----------------------------------------------------------------------------

@Composable
fun PlaceEditScreen(navi: NaviState, simulation: Boolean, currentName: String, onPick: (NaviPlace) -> Unit, onOpenNavigation: () -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, null, if (simulation) ({ SimulationBadge() }) else null)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Panes(0.42f, left = {
                Column(Modifier.padding(start = d.u(42), end = d.u(6))) {
                    T("어디로 바꿀까요?", d.t(36), bold = true)
                    Spacer(Modifier.height(d.u(6)))
                    T("지금은 $currentName", d.t(19), color = muted())
                }
            }, right = {
                NaviPlaceList(navi, simulation, isSelected = { false }, onTap = onPick, onOpenNavigation = onOpenNavigation, modifier = paneFill(420).padding(bottom = d.u(8)))
            })
        }
    }
}

@Composable
fun SyncingScreen() {
    val d = LocalCarDimens.current
    Box(Modifier.fillMaxSize().background(Color.Transparent), contentAlignment = Alignment.Center) {
        T("기록을 휴대폰으로 보내고 있어요", d.t(28), Modifier.testTag("syncing"), bold = true, align = TextAlign.Center)
    }
}
