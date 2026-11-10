package com.drivelog.app.feature.car

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivelog.app.BuildConfig
import com.drivelog.app.core.journey.JourneyStore
import com.drivelog.app.core.journey.RecordStatus
import com.drivelog.app.core.journey.RecordV
import androidx.compose.runtime.key
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.core.platform.RouteSummary
import com.drivelog.app.core.platform.SpeechState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal fun posterDate(millis: Long) = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(millis))

// One spacing and corner scale for these screens, on the 8-unit rhythm the Android for Cars templates use.
// The tabs, the search field and the list rows share a radius, so the screen reads as one set of shapes.
private val FieldRadius = CarRadius.CONTROL.dp
/** Left inset of every field, row and note, so icons and text line up down the screen. */
private const val InsetX = 16

/** Controls on the left with the map beside them; narrow windows scroll one column with the map below. */
@Composable
private fun MapPanes(left: @Composable ColumnScope.() -> Unit, stackedHeight: Int = 260, map: @Composable (Modifier) -> Unit) {
    val d = LocalCarDimens.current
    if (d.stacked) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(16))) {
            left()
            map(Modifier.fillMaxWidth().height(d.u(stackedHeight)))
        }
    } else {
        Row(Modifier.fillMaxSize().padding(bottom = d.u(8)), horizontalArrangement = Arrangement.spacedBy(d.u(32))) {
            Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState())) { left() }
            map(Modifier.weight(0.54f).fillMaxHeight())
        }
    }
}

/**
 * Where the car is and where it is going, on a real map fetched while parked. The navigation app's route is not
 * drawn: the SDK does not expose its line, and a line from anywhere else would not be the one it guides.
 */
@Composable
private fun PlaceMap(routeMaps: RouteMapCache, current: MapPoint?, destination: NaviPlace?, modifier: Modifier) {
    // Framed once per map, so a position arriving after the screen opens frames it again with both points.
    key(current != null, destination?.latitude, destination?.longitude) {
        LiveRouteMap(
            routeMaps, listOfNotNull(current), destination?.let { MapPoint(it.latitude, it.longitude) }, destination?.name, modifier,
            startLabel = if (current != null) "현재 위치" else null,
        )
    }
}

/** The navigation app's distance, at one decimal under 10 km. Its distance is reported in metres. */
fun routeKmText(summary: RouteSummary): String {
    val km = summary.distanceMeters / 1000.0
    return if (km < 10) String.format(java.util.Locale.US, "%.1f", km) else "${km.roundToInt()}"
}

/** The navigation app's estimate, rounded up to whole minutes. Its duration is reported in seconds. */
fun routeTimeText(summary: RouteSummary): String {
    val minutes = ((summary.durationSeconds + 59) / 60).coerceAtLeast(1)
    return if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
}

fun routeSummaryText(summary: RouteSummary) = "내비 추천 경로 · ${routeKmText(summary)} km · 약 ${routeTimeText(summary)}"

/** Typing and speech only produce a keyword. The search itself belongs to the navigation app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchBar(query: String, speech: SpeechState, canSearch: Boolean, onQuery: (String) -> Unit, onSearch: () -> Unit, onVoice: () -> Unit, onClear: (() -> Unit)? = null) {
    val d = LocalCarDimens.current
    // Dropping focus, not just hiding the keyboard: a focused field reopens it on return from the navigation app.
    val focus = LocalFocusManager.current
    val search = { if (canSearch && query.isNotBlank()) { focus.clearFocus(); onSearch() } }
    // A filled field, as the Maps and Kakao Navi search boxes are: the one input on the screen, quiet until typed in.
    // The outline only darkens while listening, so the state change is seen without anything moving.
    val fieldShape = RoundedCornerShape(FieldRadius)
    Row(
        Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(72), 72.dp)).clip(fieldShape).background(soft())
            .border(if (speech.listening) 2.dp else 1.dp, if (speech.listening) fg() else line(), fieldShape)
            .padding(start = d.u(InsetX), end = d.u(4)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (speech.listening && speech.ready) Box(Modifier.size(d.u(24)), contentAlignment = Alignment.Center) { PulsingDot(d.u(14), fg()) }
        else CarIcon(CarGlyph.SEARCH, d.u(24), muted())
        Spacer(Modifier.width(d.u(16)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val hint = when {
                !speech.listening -> "장소 또는 주소 검색"
                speech.ready -> speech.partial.ifEmpty { "듣고 있어요" }
                else -> "준비하고 있어요. 잠시만요"
            }
            if (query.isEmpty()) T(hint, d.t(21), Modifier.testTag("search-hint"), color = muted(), maxLines = 1)
            BasicTextField(
                query, onQuery, Modifier.fillMaxWidth().testTag("place-query"), enabled = canSearch, singleLine = true,
                textStyle = TextStyle(color = fg(), fontSize = d.t(21)), cursorBrush = SolidColor(fg()),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
        }
        val action: (@Composable (String, String, () -> Unit) -> Unit) = { label, tag, click ->
            val enabled = canSearch || speech.listening
            // 찾기 is the screen's one primary action, so only it is filled; 말하기 and 그만 듣기 stay plain.
            val primary = tag == "search-place" && enabled
            Row(
                Modifier.padding(vertical = d.u(4)).heightIn(min = 64.dp).clip(RoundedCornerShape(FieldRadius - 4.dp))
                    .background(if (primary) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .pressClick(enabled = enabled, onClick = click)
                    .padding(horizontal = d.u(if (primary) 24 else 16)).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tag == "voice-search") {
                    CarIcon(CarGlyph.MIC, d.u(24), if (enabled) fg() else muted())
                    Spacer(Modifier.width(d.u(8)))
                }
                T(label, d.t(18), color = when { primary -> MaterialTheme.colorScheme.onPrimary; enabled -> fg(); else -> muted() }, bold = true, maxLines = 1)
            }
        }
        when {
            speech.listening -> action("그만 듣기", "voice-search", onVoice)
            query.isNotBlank() -> {
                if (onClear != null) Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .pressClick(enabled = canSearch, onClick = onClear)
                        .semantics { contentDescription = "검색어 지우기" }.testTag("clear-query"),
                    contentAlignment = Alignment.Center,
                ) { T("×", d.t(28), color = muted()) }
                action("찾기", "search-place", { search() })
            }
            speech.available -> action("말하기", "voice-search", onVoice)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tabs(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val d = LocalCarDimens.current
    // A segmented control: the chosen tab is a page-coloured pill on the soft track, so it never reads as the
    // screen's one filled primary action.
    val indicator = MaterialTheme.colorScheme.background
    val outline = line()
    val position by animateFloatAsState(selectedIndex.toFloat(), motionTween(MOTION_STATE_MS), label = "tab indicator")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(FieldRadius)).background(soft()).padding(4.dp).drawBehind {
            val width = size.width / labels.size
            // Inner corner = outer corner − track padding, so the pill sits concentric in the track.
            val corner = (FieldRadius - 4.dp).toPx()
            drawRoundRect(indicator, Offset(width * position, 0f), Size(width, size.height), CornerRadius(corner, corner))
            drawRoundRect(outline, Offset(width * position, 0f), Size(width, size.height), CornerRadius(corner, corner), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        },
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val text by animateColorAsState(if (active) fg() else muted(), motionTween(MOTION_STATE_MS), label = "tab text")
            Box(
                Modifier.weight(1f).heightIn(min = maxOf(d.u(56), 64.dp)).clip(RoundedCornerShape(FieldRadius - 4.dp))
                    .pressClick(Role.Tab) { onSelect(index) }.semantics { selected = active }.testTag("tab-$index"),
                contentAlignment = Alignment.Center,
            ) { T(label, d.t(18), color = text, bold = active, maxLines = 1) }
        }
    }
}

/**
 * A recent or saved place as Maps and TMAP list them: a round icon well, name over a quiet address, and a hairline
 * divider inset to the text. Rows are flat, so the filled search field above stays the screen's only box.
 */
@Composable
private fun PlaceRow(place: NaviPlace, glyph: CarGlyph, divider: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val rule = line()
    val ruleStart = d.u(InsetX + 48 + 16)
    Row(
        Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(80), 64.dp))
            .drawBehind { if (divider) drawLine(rule, Offset(ruleStart.toPx(), size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .clip(RoundedCornerShape(FieldRadius)).pressClick(onClick = onClick).padding(horizontal = d.u(InsetX)).testTag("place-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(d.u(48)).clip(CircleShape).background(soft()), contentAlignment = Alignment.Center) { CarIcon(glyph, d.u(24), fg()) }
        Spacer(Modifier.width(d.u(16)))
        Column(Modifier.weight(1f).padding(vertical = d.u(12))) {
            T(place.name, d.t(CarTextSize.BODY), bold = true, maxLines = 1)
            if (place.address.isNotBlank()) {
                Spacer(Modifier.height(d.u(2)))
                T(place.address, d.t(CarTextSize.CAPTION), color = muted(), maxLines = 1)
            }
        }
        Spacer(Modifier.width(d.u(16)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(24), muted())
    }
}

// --- 01 목적지: 장소 검색, 최근 검색, 저장한 장소, 지도 --------------------------------------------------

class SearchActions(val onQuery: (String) -> Unit, val onSearch: () -> Unit, val onVoice: () -> Unit, val onClear: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DestinationScreen(
    query: String, speech: SpeechState, canSearch: Boolean, simulation: Boolean, note: String?, recents: List<NaviPlace>, saved: List<NaviPlace>,
    actions: SearchActions, onPlace: (NaviPlace) -> Unit, onBack: () -> Unit,
) {
    val d = LocalCarDimens.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, null, if (simulation) ({ SimulationBadge() }) else null, title = "드라이브 시작")
        // Only finding a place: the drives themselves are on the home screen and in 내 기록.
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = d.u(16))) {
            run {
                T("어디로 떠나볼까요?", d.t(CarTextSize.HEADING), display = true)
                Spacer(Modifier.height(d.u(24)))
                // 지우기 too: spoken text now waits here, and a misheard phrase needs a way out without the keyboard.
                SearchBar(query, speech, canSearch, actions.onQuery, actions.onSearch, actions.onVoice, actions.onClear)
                Spacer(Modifier.height(d.u(16)))
                // Helper text under the field, not a second box: it explains the field, it is not something to press.
                // Its icon sits in the same column as the search icon above it.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = d.u(InsetX)).testTag("navigation-handoff"),
                    verticalAlignment = Alignment.Top,
                ) {
                    CarIcon(CarGlyph.PIN, d.u(24), muted(), Modifier.padding(top = d.u(2)))
                    Spacer(Modifier.width(d.u(16)))
                    Column(Modifier.weight(1f)) {
                        T(if (canSearch) "찾기를 누르면 내비가 열려요" else "저장한 장소에서 출발할 수 있어요", d.t(18), bold = true)
                        Spacer(Modifier.height(d.u(2)))
                        T(if (canSearch) "목적지를 정하고 DRIVELOG로 돌아오면 기록을 시작해요" else "내비 연결을 기다리거나 아래 목록에서 장소를 골라주세요", d.t(16), color = muted())
                    }
                }
                val line = when {
                    !canSearch -> "내비게이션에 연결할 수 없어요"
                    // The search leaves for the navigation app, and nothing there says to come back here.
                    else -> speech.message ?: note
                }
                if (line != null) {
                    Spacer(Modifier.height(d.u(8)))
                    // Listening, heard, not found: each new word of state replaces the last in place. Indented to the
                    // helper text above, so it reads as the next line of it.
                    Crossfade(line, Modifier.padding(start = d.u(InsetX + 24 + 16)), label = "search note", animationSpec = motionTween<Float>(MOTION_ENTER_MS)) { shownLine ->
                        T(shownLine, d.t(16), Modifier.testTag("search-note"), color = if (!canSearch) MaterialTheme.colorScheme.error else muted(), bold = !canSearch, maxLines = 2)
                    }
                }
                Spacer(Modifier.height(d.u(32)))
                Tabs(listOf("최근 검색", "저장한 장소"), tab) { tab = it }
                Spacer(Modifier.height(d.u(8)))
                val places = if (tab == 0) recents else saved
                if (places.isEmpty()) {
                    T(if (tab == 0) "가고 싶은 곳을 말하거나 입력해 보세요" else "장소를 고른 뒤 저장하면 여기에 모여요", d.t(18), Modifier.fillMaxWidth().padding(vertical = d.u(40)).testTag("places-empty"), color = muted(), align = TextAlign.Center)
                } else places.forEachIndexed { index, place ->
                    PlaceRow(place, if (tab == 0) CarGlyph.CLOCK else CarGlyph.BOOKMARK, divider = index < places.lastIndex) { onPlace(place) }
                }
            }
        }
    }
}

// --- 02 목적지 확인: 길 안내 및 기록 시작 ------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaceConfirmScreen(
    place: NaviPlace, query: String, speech: SpeechState, canSearch: Boolean, simulation: Boolean, naviAvailable: Boolean, guidanceActive: Boolean, isSaved: Boolean, message: String?, current: MapPoint?,
    actions: SearchActions, onSave: () -> Unit, onStart: () -> Unit, onCancelRoute: () -> Unit, onBack: () -> Unit,
    /** Set once recording was refused for lack of a location grant, which a repeat request may no longer offer. */
    onOpenSettings: (() -> Unit)? = null,
    routeMaps: RouteMapCache? = null,
    /** The navigation app's figures for the route it is already guiding to this place. */
    routeSummary: RouteSummary? = null,
    onShowRoute: (() -> Unit)? = null,
) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, null, if (simulation) ({ SimulationBadge() }) else null, title = "장소 선택")
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MapPanes(left = {
                SearchBar(query, speech, canSearch, actions.onQuery, actions.onSearch, actions.onVoice, actions.onClear)
                Spacer(Modifier.height(d.u(24)))
                // A place sheet as Google Maps and Android for Cars' place template set one: name, address, then
                // the one decision. Saving is a secondary action beside the address, not competing with the start.
                T(place.name, d.t(CarTextSize.HEADING.baseSp - 4), Modifier.fillMaxWidth().testTag("place-name"), maxLines = 2, display = true)
                Spacer(Modifier.height(d.u(8)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (place.address.isNotBlank()) T(place.address, d.t(18), color = muted(), maxLines = 2)
                    }
                    Spacer(Modifier.width(d.u(16)))
                    SecondaryButton(if (isSaved) "저장 취소" else "장소 저장", "save-place", size = ButtonSize.SMALL, icon = CarGlyph.BOOKMARK, onClick = onSave)
                }
                Spacer(Modifier.height(d.u(16)))
                Box(Modifier.fillMaxWidth().height(1.dp).background(line()))
                Spacer(Modifier.height(d.u(16)))
                // The navigation app's own figures lead when it is already guiding: they are what the driver decides on.
                // Number first, its label under it, as every figure in the app is set.
                if (guidanceActive && routeSummary != null) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("route-summary")) {
                        Stat(routeKmText(routeSummary), "km", "내비 추천 경로", Modifier.weight(1f), size = CarTextSize.TITLE.baseSp + 4)
                        Spacer(Modifier.width(d.u(16)))
                        VerticalRule(Modifier.fillMaxHeight())
                        Spacer(Modifier.width(d.u(24)))
                        Stat(routeTimeText(routeSummary), "", "예상 시간", Modifier.weight(1f), size = CarTextSize.TITLE.baseSp + 4)
                    }
                } else T(if (BuildConfig.PHONE_LINK_ENABLED) "이번 드라이브를 사진과 함께 남겨보세요" else "이번 드라이브를 경로와 시간으로 남겨보세요", d.t(20), color = muted())
                Spacer(Modifier.height(d.u(24)))
                PrimaryButton(if (naviAvailable) "길 안내 및 기록 시작" else "기록 시작", "start-drive", Modifier.fillMaxWidth(), chevron = true, onClick = onStart)
                Spacer(Modifier.height(d.u(8)))
                T(
                    message ?: speech.message ?: when {
                        guidanceActive -> "내비 안내가 이미 켜져 있어요. 시작하면 기록만 더해요"
                        naviAvailable -> "길 안내는 Pleos 내비게이션으로 연결돼요"
                        else -> "내비게이션에 연결할 수 없어 기록만 시작해요"
                    },
                    d.t(16), Modifier.fillMaxWidth().testTag("place-note"), color = if (onOpenSettings != null) MaterialTheme.colorScheme.error else muted(), bold = onOpenSettings != null, align = TextAlign.Center, maxLines = 2,
                )
                if (onOpenSettings != null) {
                    Spacer(Modifier.height(d.u(8)))
                    SecondaryButton("설정에서 권한 허용", "open-settings", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onOpenSettings)
                }
                // Choosing a place in the navigation app has already started guidance, which may not be wanted yet.
                if (guidanceActive) {
                    Spacer(Modifier.height(d.u(8)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(8))) {
                        // The route the navigation app chose, shown by it: the only way to see that exact line.
                        if (onShowRoute != null) SecondaryButton("내비에서 경로 보기", "show-route", Modifier.weight(1f), size = ButtonSize.MEDIUM, onClick = onShowRoute)
                        SecondaryButton("내비 안내 끄기", "cancel-route", Modifier.weight(1f), size = ButtonSize.MEDIUM, onClick = onCancelRoute)
                    }
                }
            }, stackedHeight = 400) { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                    // A banner, because the bundled photos are small and blur when they fill the pane.
                    if (placeHasPhoto(place.name)) PlacePhoto(place.name, Modifier.fillMaxWidth().aspectRatio(3.2f))
                    if (routeMaps != null) PlaceMap(routeMaps, current, place, Modifier.fillMaxWidth().weight(1f))
                    else RouteMap(listOfNotNull(current), MapPoint(place.latitude, place.longitude), place.name, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

// --- 03 내 기록: 이전 드라이브, 드라이브 카드------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(store: JourneyStore, routeMaps: RouteMapCache, records: List<RecordV>, onRecord: (RecordV) -> Unit, onBack: () -> Unit, initialTab: Int = 0) {
    val d = LocalCarDimens.current
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var shortOpen by rememberSaveable { mutableStateOf(false) }
    val sorted = remember(records) { records.sortedByDescending { it.startedAtMillis } }
    val (drives, shortDrives) = remember(sorted) { sorted.partition { !barelyMoved(it.distanceKm, it.route.size >= 2) } }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, null, if (sorted.any { it.simulated }) ({ SimulationBadge() }) else null, title = "내 기록")
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Tabs(listOf("이전 드라이브", "드라이브 카드", "스탬프"), tab) { tab = it }
            Spacer(Modifier.height(d.u(16)))
            Crossfade(tab, Modifier.fillMaxSize(), tween(if (LocalMotionEnabled.current) 220 else 0), label = "history tab") { shownTab ->
            when {
                // Every place shows from the start, so a first-time driver sees what there is to collect.
                shownTab == 2 -> StampBoard(sorted)
                sorted.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    T("아직 기록한 드라이브가 없어요. 장소를 고르고 기록을 시작해 보세요", d.t(20), Modifier.padding(horizontal = d.u(48)).testTag("history-empty"), color = muted(), align = TextAlign.Center)
                }
                // Lazy, as every row converts and draws a whole route and the list only ever grows.
                shownTab == 0 -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                    item(key = "summary") {
                        Column {
                            HistorySummary(sorted)
                            MonthLine(sorted)
                        }
                    }
                    driveTimeline(routeMaps, drives, onRecord)
                    if (shortDrives.isNotEmpty()) {
                        item(key = "short") { ShortDrivesRow(shortDrives.size, shortOpen) { shortOpen = !shortOpen } }
                        if (shortOpen) items(shortDrives, key = { "short-" + it.id }) { record -> DriveRow(routeMaps, record, 0) { onRecord(record) } }
                    }
                    // The thumbnails show the drive card's stored maps, so their credit goes once under the list.
                    item(key = "map-credit") { T(ATTRIBUTION, d.t(12), Modifier.padding(start = d.u(4), top = d.u(16), bottom = d.u(8)).testTag("list-map-credit"), color = muted(), maxLines = 2) }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    // A poster of a drive that went nowhere has nothing to show. Those stay in the list tab.
                    val posters = remember(sorted) { sorted.filterNot { barelyMoved(it.distanceKm, it.route.size >= 2) } }
                    if (posters.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        T("드라이브를 기록하면 여기에 카드가 만들어져요", d.t(20), Modifier.padding(horizontal = d.u(48)).testTag("posters-empty"), color = muted(), align = TextAlign.Center)
                    }
                    else LazyVerticalGrid(
                        GridCells.Fixed(if (d.stacked) 1 else 3), Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(d.u(16)), verticalArrangement = Arrangement.spacedBy(d.u(16)),
                    ) {
                        itemsIndexed(posters, key = { _, record -> record.id }) { index, record -> DrivePoster(store, routeMaps, record, index) { onRecord(record) } }
                    }
                }
            }
            }
        }
    }
}

/** The whole journey at a glance, above the list. Totals never hide what they are made of. */
@Composable
private fun HistorySummary(records: List<RecordV>) {
    val d = LocalCarDimens.current
    val totalKm = remember(records) { records.sumOf { it.distanceKm ?: 0.0 } }
    val totalSeconds = remember(records) { records.sumOf { it.elapsedSeconds } }
    val simulated = remember(records) { records.any { it.simulated } }
    val shownKm = rememberCountUp(totalKm)
    val shownCount = rememberCountUp(records.size.toDouble())
    val shownSeconds = rememberCountUp(totalSeconds.toDouble())
    // Three equal columns, as the Nike Run Club and Apple Fitness history headers set their totals, so a long
    // figure in one never pushes the others off the row. One step under the display size: the list is the content.
    val size = if (d.stacked) CarTextSize.TITLE.baseSp + 4 else CarTextSize.HEADING.baseSp + 8
    Row(Modifier.revealOnce().fillMaxWidth().height(IntrinsicSize.Min).padding(top = d.u(8), bottom = d.u(16)).testTag("history-summary")) {
        Stat(distanceText(shownKm), "km", "지금까지 달린 거리 · 추정 포함", Modifier.weight(1f), size = size)
        VerticalRule(Modifier.fillMaxHeight())
        Spacer(Modifier.width(d.u(24)))
        Stat("${shownCount.roundToInt()}", "회", if (simulated) "드라이브 · 시뮬레이션 포함" else "드라이브", Modifier.weight(1f), size = size)
        VerticalRule(Modifier.fillMaxHeight())
        Spacer(Modifier.width(d.u(24)))
        Stat(elapsedText(shownSeconds.roundToLong()), "", "기록 시간", Modifier.weight(1f), size = size)
        // Only once a real drive has been measured; simulated drives never carry one.
        // A narrow window has no room for a fourth figure; the month line below still carries the average.
        val average = remember(records) { averageEfficiency(records) }
        if (average != null && !d.stacked) {
            VerticalRule(Modifier.fillMaxHeight())
            Spacer(Modifier.width(d.u(24)))
            Box(Modifier.weight(1f).testTag("history-efficiency")) {
                Stat(average.first, average.second, "평균 ${efficiencyName(average.second)} · 실측 기록", size = size)
            }
        }
    }
}

/** This month beside the all-time totals, so a returning driver sees the month build up. Nothing is compared or ranked. */
@Composable
private fun MonthLine(records: List<RecordV>) {
    val d = LocalCarDimens.current
    val month = remember(records) { thisMonth(records) }
    // Counts up with the totals above it, so the month reads as part of the same summary.
    val shownCount = rememberCountUp(month.size.toDouble())
    val shownKm = rememberCountUp(month.sumOf { it.distanceKm ?: 0.0 })
    val text = if (month.isEmpty()) "이번 달은 아직 드라이브가 없어요"
    else "이번 달 ${shownCount.roundToInt()}회 · ${distanceText(shownKm)} km" +
        averageEfficiency(month)?.let { (value, unit) -> " · 평균 ${efficiencyName(unit)} $value $unit" }.orEmpty()
    // A hairline closes the summary, so the dated list below reads as its own section.
    Column(Modifier.fillMaxWidth()) {
        T(text, d.t(16), Modifier.padding(bottom = d.u(16)).testTag("history-month"), color = muted(), maxLines = 1)
        Box(Modifier.fillMaxWidth().height(1.dp).background(line()))
    }
}

internal fun thisMonth(records: List<RecordV>, now: Long = System.currentTimeMillis()): List<RecordV> {
    val key = SimpleDateFormat("yyyyMM", Locale.KOREA)
    val current = key.format(Date(now))
    return records.filter { key.format(Date(it.startedAtMillis)) == current }
}

private fun recordDay(millis: Long) = SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(Date(millis))
private fun recordClock(millis: Long) = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(millis))

/** Drives by day, newest first, as a timeline reads. Order is time only: nothing is ranked. */
private fun LazyListScope.driveTimeline(routeMaps: RouteMapCache, records: List<RecordV>, onRecord: (RecordV) -> Unit) {
    var index = 0
    records.groupBy { recordDay(it.startedAtMillis) }.forEach { (day, drives) ->
        item(key = "day-$day") {
            val d = LocalCarDimens.current
            // A section label, as Strava and Google Timeline date their lists: small, quiet, with air above it.
            T(day, d.t(16), Modifier.padding(start = d.u(4), top = d.u(16)).testTag("day-header"), color = muted(), bold = true)
        }
        drives.forEach { record ->
            val shown = index++
            item(key = record.id) { DriveRow(routeMaps, record, shown) { onRecord(record) } }
        }
    }
}

/** Card radius = the thumbnail's 12 + the 8 around it, so the tile sits concentric in its row. */
private const val DriveRowRadius = CarRadius.TILE

@Composable
private fun DriveRow(routeMaps: RouteMapCache, record: RecordV, index: Int, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    Row(
        Modifier.revealOnce(index).fillMaxWidth().heightIn(min = maxOf(d.u(112), 64.dp)).clip(RoundedCornerShape(d.u(DriveRowRadius))).background(soft())
            .pressClick(onClick = onClick).padding(d.u(8)).padding(end = d.u(InsetX - 8)).testTag("drive-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A drive without a route shows a pin on the tile: a lone ring looked like a radio button.
        RecordThumbnail(routeMaps, record, Modifier.size(d.u(128), d.u(96)))
        Spacer(Modifier.width(d.u(24)))
        Column(Modifier.weight(1f)) {
            T(record.placeName, d.t(CarTextSize.BODY), bold = true, maxLines = 1)
            Spacer(Modifier.height(d.u(4)))
            T(
                listOfNotNull(recordClock(record.startedAtMillis), elapsedText(record.elapsedSeconds), efficiencyText(record)).joinToString(" · ") + labels(record),
                d.t(16), color = muted(), maxLines = 1,
            )
        }
        Spacer(Modifier.width(d.u(16)))
        // The figure leads the right edge, its unit smaller and quiet, as the activity apps set a row's distance.
        Row(verticalAlignment = Alignment.Bottom) {
            T(distanceText(record.distanceKm), d.t(28), maxLines = 1, display = true, tabular = true)
            if (record.distanceKm != null) T("km", d.t(16), Modifier.padding(start = d.u(4), bottom = d.u(3)), color = muted(), bold = true, maxLines = 1)
        }
        Spacer(Modifier.width(d.u(16)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(24), muted())
    }
}

/** Starts pressed by mistake, gathered under one row so the drives themselves lead the list. Nothing is deleted. */
@Composable
private fun ShortDrivesRow(count: Int, open: Boolean, onToggle: () -> Unit) {
    val d = LocalCarDimens.current
    // Outlined, not filled: it folds the list rather than opening a drive, so it must not look like one.
    val shape = RoundedCornerShape(d.u(DriveRowRadius))
    Row(
        Modifier.padding(top = d.u(8)).fillMaxWidth().heightIn(min = 64.dp).clip(shape).border(1.dp, line(), shape).pressClick(onClick = onToggle)
            .padding(horizontal = d.u(InsetX + 8)).testTag("short-drives"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("짧은 기록 ${count}개", d.t(18), Modifier.weight(1f), color = muted(), bold = true)
        T(if (open) "접기" else "펼치기", d.t(16), color = fg(), bold = true)
    }
}

private fun labels(record: RecordV) = buildString {
    if (record.simulated) append(" · 시뮬레이션")
    if (record.status == RecordStatus.PARTIAL) append(" · 불완전한 기록")
}

private const val POSTER_ASPECT = 0.68f
private val PosterScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.62f), 0.36f to Color.Transparent, 0.58f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f),
)

/** The drive as a poster, as the mockup cards set it: over a photo when one is sharp enough, otherwise on black. */
@Composable
private fun DrivePoster(store: JourneyStore, routeMaps: RouteMapCache, record: RecordV, index: Int, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val photo = rememberRecordPhoto(store, record)
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    Box(
        Modifier.revealOnce(index).fillMaxWidth().aspectRatio(POSTER_ASPECT).clip(RoundedCornerShape(d.u(16))).background(DriveCardBackground)
            .pressClick(source = press, indication = null, onClick = onClick).testTag("drive-card"),
    ) {
        // The driver's own photo makes a photo poster. Otherwise the drive is shown on its map, as the mockups draw
        // it; a bundled photo of the place is only an example, so it stays a small banner under the map.
        if (photo != null) PhotoPoster(record, photo) else BlackPoster(routeMaps, record)
        if (pressed) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
    }
}

/** Says what the number is, and that a simulated drive is one, right beside it. */
private fun posterLabel(record: RecordV) = (listOf(distanceLabel(record.distanceSource)) + listOfNotNull(efficiencyText(record))).joinToString(" · ") + labels(record)

@Composable
internal fun PosterDistance(record: RecordV, size: Int, unitColor: Color = DriveCardText) {
    val d = LocalCarDimens.current
    val shown = rememberCountUp(record.distanceKm ?: 0.0)
    Row(verticalAlignment = Alignment.Bottom) {
        T(if (record.distanceKm != null) distanceText(shown) else distanceText(null), d.t(size), color = DriveCardText, maxLines = 1, display = true)
        if (record.distanceKm != null) T("km", d.t(size * 2 / 5), Modifier.padding(start = d.u(6), bottom = d.u(size / 8)), color = unitColor, maxLines = 1, display = true)
    }
}

/** One footer for every card, in the car and on the phone: place · time · date. */
@Composable
internal fun PosterFooter(record: RecordV) {
    val d = LocalCarDimens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardFooterText(listOf(shortName(record.placeName), elapsedText(record.elapsedSeconds), posterDate(record.startedAtMillis)), Modifier.weight(1f))
        Spacer(Modifier.width(d.u(6)))
        BrandWordmark(d.t(16), DriveCardText)
    }
}

@Composable
private fun BoxScope.PhotoPoster(record: RecordV, photo: ImageBitmap) {
    val d = LocalCarDimens.current
    PosterPhoto(record.placeName, photo, null, Modifier.matchParentSize())
    // White type stays readable over any photo, and the middle is left clear for the picture and the route.
    Box(Modifier.matchParentSize().background(PosterScrim))
    // The same order as every other card: title, route, the distance, then the footer.
    Column(Modifier.matchParentSize().padding(d.u(18))) {
        CardHeading(record, "나의 드라이브", 26, eyebrowColor = DriveCardText)
        RouteGlyph(remember(record.id) { record.route.toMapPoints() }, Modifier.fillMaxWidth().weight(1f).padding(vertical = d.u(8)), DriveCardText, d.u(3), trace = true)
        PosterDistance(record, 58)
        T(posterLabel(record), d.t(16), color = DriveCardText, maxLines = 1)
        Spacer(Modifier.height(d.u(4)))
        PosterFooter(record)
    }
}

@Composable
private fun BlackPoster(routeMaps: RouteMapCache, record: RecordV) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(d.u(18))) {
        CardHeading(record, "나의 드라이브", 26)
        Spacer(Modifier.height(d.u(10)))
        // The image's own shape, so the map is shown whole; its credit is the caption at the foot of the card.
        // A north–south drive's tall map takes the height the card has, centred, with no banner under it.
        val aspect = routeImageAspect(record)
        val tall = aspect < 1f
        if (tall) RouteVisual(
            routeMaps, record, null,
            Modifier.weight(1f).aspectRatio(aspect, matchHeightConstraintsFirst = true).align(Alignment.CenterHorizontally),
        ) else RouteVisual(routeMaps, record, null, Modifier.fillMaxWidth().aspectRatio(aspect))
        if (!tall && placeHasPhoto(record.placeName)) {
            Spacer(Modifier.height(d.u(8)))
            // A banner, because these bundled photos blur at any larger size.
            PlacePhoto(record.placeName, Modifier.fillMaxWidth().aspectRatio(4.2f))
        }
        if (!tall) Spacer(Modifier.weight(1f))
        CardTearLine(d.u(18), Modifier.padding(vertical = d.u(4)))
        PosterDistance(record, 58, DriveCardMuted)
        T(posterLabel(record), d.t(14), color = DriveCardMuted, maxLines = 1)
        Spacer(Modifier.height(d.u(4)))
        PosterFooter(record)
    }
}

@androidx.compose.runtime.Composable
fun SyncingScreen() {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingIndicator(LoadingStyle.TENSION)
        Spacer(Modifier.height(d.u(14)))
        T("기록을 휴대폰으로 보내고 있어요", d.t(28), Modifier.testTag("syncing"), bold = true, align = TextAlign.Center)
    }
}
