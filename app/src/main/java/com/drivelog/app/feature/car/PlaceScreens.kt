package com.drivelog.app.feature.car

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import com.drivelog.app.core.platform.NaviPlace
import com.drivelog.app.core.platform.SpeechState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun recordDate(millis: Long) = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(millis))
private fun posterDate(millis: Long) = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(millis))

/** Controls on the left with the map beside them; narrow windows scroll one column with the map below. */
@Composable
private fun MapPanes(left: @Composable ColumnScope.() -> Unit, stackedHeight: Int = 260, map: @Composable (Modifier) -> Unit) {
    val d = LocalCarDimens.current
    if (d.stacked) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(14))) {
            left()
            map(Modifier.fillMaxWidth().height(d.u(stackedHeight)))
        }
    } else {
        Row(Modifier.fillMaxSize().padding(bottom = d.u(10)), horizontalArrangement = Arrangement.spacedBy(d.u(24))) {
            Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState())) { left() }
            map(Modifier.weight(0.54f).fillMaxHeight())
        }
    }
}

/** Only the pins are real positions; the map itself labels its backdrop as an example. */
@Composable
private fun PlaceMap(current: MapPoint?, destination: NaviPlace?, modifier: Modifier) = RouteMap(
    listOfNotNull(current), destination?.let { MapPoint(it.latitude, it.longitude) }, destination?.name, modifier,
    startLabel = if (current != null) "현재 위치" else null,
)

/** The last few drives under the home photo, or a line saying where they will appear. */
@Composable
private fun RecentDrives(records: List<RecordV>, onRecord: (RecordV) -> Unit) {
    val d = LocalCarDimens.current
    val latest = remember(records) { records.sortedByDescending { it.startedAtMillis }.take(3) }
    if (latest.isEmpty()) {
        T("기록한 드라이브가 여기에 모여요", d.t(17), Modifier.fillMaxWidth().padding(vertical = d.u(14)).testTag("recent-empty"), color = muted(), align = TextAlign.Center)
        return
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        latest.forEachIndexed { index, record ->
            if (index > 0) VerticalRule(Modifier.fillMaxHeight().padding(vertical = d.u(10)))
            Row(
                Modifier.weight(1f).heightIn(min = maxOf(d.u(64), 64.dp)).clip(RoundedCornerShape(d.u(10)))
                    .pressClick { onRecord(record) }.padding(horizontal = d.u(12)).testTag("recent-drive"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(d.u(28)).clip(CircleShape).background(fg()), contentAlignment = Alignment.Center) {
                    CarIcon(CarGlyph.CHECK, d.u(16), MaterialTheme.colorScheme.background)
                }
                Spacer(Modifier.width(d.u(10)))
                Column(Modifier.weight(1f)) {
                    T(record.placeName, d.t(17), bold = true, maxLines = 1)
                    T(distanceText(record.distanceKm) + " km" + (if (record.simulated) " · 시뮬레이션" else ""), d.t(14), color = muted(), maxLines = 1)
                }
            }
        }
    }
}

/** Typing and speech only produce a keyword. The search itself belongs to the navigation app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchBar(query: String, speech: SpeechState, canSearch: Boolean, onQuery: (String) -> Unit, onSearch: () -> Unit, onVoice: () -> Unit, onClear: (() -> Unit)? = null) {
    val d = LocalCarDimens.current
    // Dropping focus, not just hiding the keyboard: a focused field reopens it on return from the navigation app.
    val focus = LocalFocusManager.current
    val search = { focus.clearFocus(); onSearch() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(58), 64.dp)).clip(RoundedCornerShape(d.u(12))).border(d.u(2), fg(), RoundedCornerShape(d.u(12))).padding(start = d.u(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (speech.listening && speech.ready) Box(Modifier.size(d.u(24)), contentAlignment = Alignment.Center) { PulsingDot(d.u(14), fg()) }
        else CarIcon(CarGlyph.SEARCH, d.u(24), fg())
        Spacer(Modifier.width(d.u(12)))
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
            Row(
                Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(10))).pressClick(onClick = click).padding(horizontal = d.u(14), vertical = d.u(16)).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tag == "voice-search") {
                    CarIcon(CarGlyph.MIC, d.u(22), fg())
                    Spacer(Modifier.width(d.u(6)))
                }
                T(label, d.t(18), bold = true, maxLines = 1)
            }
        }
        when {
            speech.listening -> action("그만 듣기", "voice-search", onVoice)
            query.isNotBlank() -> {
                if (onClear != null) action("지우기", "clear-query", onClear)
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
    val indicator = MaterialTheme.colorScheme.primary
    val position by animateFloatAsState(selectedIndex.toFloat(), tween(220, easing = FastOutSlowInEasing), label = "tab indicator")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(10))).background(soft()).drawBehind {
            val width = size.width / labels.size
            val corner = d.u(10).toPx()
            drawRoundRect(indicator, Offset(width * position, 0f), Size(width, size.height), CornerRadius(corner, corner))
        },
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val text by animateColorAsState(if (active) MaterialTheme.colorScheme.onPrimary else fg(), tween(180), label = "tab text")
            Box(
                Modifier.weight(1f).heightIn(min = maxOf(d.u(48), 64.dp)).clip(RoundedCornerShape(d.u(10)))
                    .pressClick(Role.Tab) { onSelect(index) }.semantics { selected = active }.testTag("tab-$index"),
                contentAlignment = Alignment.Center,
            ) { T(label, d.t(18), color = text, bold = active, maxLines = 1) }
        }
    }
}

// --- 01 홈: 장소 검색, 최근 검색, 저장한 장소, 지도 --------------------------------------------------

class SearchActions(val onQuery: (String) -> Unit, val onSearch: () -> Unit, val onVoice: () -> Unit, val onClear: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    query: String, speech: SpeechState, canSearch: Boolean, simulation: Boolean, note: String?, recents: List<NaviPlace>, saved: List<NaviPlace>, records: List<RecordV>,
    actions: SearchActions, onPlace: (NaviPlace) -> Unit, onRecord: (RecordV) -> Unit, onHistory: () -> Unit, onHold: () -> Unit, linkControl: (@Composable () -> Unit)?,
) {
    val d = LocalCarDimens.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(null, onHold, {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(10))) {
                if (simulation) SimulationBadge()
                linkControl?.invoke()
                SecondaryButton("내 기록", "open-history", size = ButtonSize.MEDIUM, icon = CarGlyph.LIST, onClick = onHistory)
            }
        })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MapPanes(left = {
                T("어디로 떠나볼까요?", d.t(42), display = true)
                Spacer(Modifier.height(d.u(16)))
                // 지우기 too: spoken text now waits here, and a misheard phrase needs a way out without the keyboard.
                SearchBar(query, speech, canSearch, actions.onQuery, actions.onSearch, actions.onVoice, actions.onClear)
                val line = when {
                    !canSearch -> "내비게이션에 연결할 수 없어요"
                    else -> speech.message ?: note
                }
                if (line != null) {
                    Spacer(Modifier.height(d.u(6)))
                    T(line, d.t(16), Modifier.testTag("search-note"), color = muted(), maxLines = 2)
                }
                Spacer(Modifier.height(d.u(12)))
                Tabs(listOf("최근 검색", "저장한 장소"), tab) { tab = it }
                Spacer(Modifier.height(d.u(8)))
                val places = if (tab == 0) recents else saved
                if (places.isEmpty()) {
                    T(if (tab == 0) "아직 검색한 장소가 없어요" else "저장한 장소가 없어요", d.t(18), Modifier.fillMaxWidth().padding(vertical = d.u(24)).testTag("places-empty"), color = muted(), align = TextAlign.Center)
                } else places.forEach { place ->
                    Row(
                        Modifier.padding(bottom = d.u(7)).fillMaxWidth().heightIn(min = maxOf(d.u(62), 64.dp)).clip(RoundedCornerShape(d.u(10))).background(soft())
                            .pressClick { onPlace(place) }.padding(horizontal = d.u(16)).testTag("place-row"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CarIcon(if (tab == 0) CarGlyph.CLOCK else CarGlyph.BOOKMARK, d.u(24), fg())
                        Spacer(Modifier.width(d.u(16)))
                        Column(Modifier.weight(1f)) {
                            T(place.name, d.t(19), bold = true, maxLines = 1)
                            if (place.address.isNotBlank()) T(place.address, d.t(15), color = muted(), maxLines = 1)
                        }
                        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(20), fg())
                    }
                }
            }, stackedHeight = 360) { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                    // A mood image, not a place, so it is the one photo that names nowhere.
                    PlacePhoto("", Modifier.fillMaxWidth().weight(1f))
                    RecentDrives(records, onRecord)
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
) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, null, if (simulation) ({ SimulationBadge() }) else null, title = "장소 선택")
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MapPanes(left = {
                SearchBar(query, speech, canSearch, actions.onQuery, actions.onSearch, actions.onVoice, actions.onClear)
                Spacer(Modifier.height(d.u(14)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(place.name, d.t(38), Modifier.testTag("place-name"), maxLines = 2, display = true)
                        if (place.address.isNotBlank()) T(place.address, d.t(18), color = muted(), maxLines = 2)
                    }
                    Spacer(Modifier.width(d.u(12)))
                    SecondaryButton(if (isSaved) "저장 취소" else "장소 저장", "save-place", size = ButtonSize.MEDIUM, icon = CarGlyph.BOOKMARK, onClick = onSave)
                }
                Spacer(Modifier.height(d.u(14)))
                Box(Modifier.fillMaxWidth().height(1.dp).background(line()))
                Spacer(Modifier.height(d.u(14)))
                T(if (BuildConfig.PHONE_LINK_ENABLED) "이번 드라이브를 사진과 함께 남겨보세요" else "이번 드라이브를 경로와 시간으로 남겨보세요", d.t(20))
                Spacer(Modifier.height(d.u(16)))
                PrimaryButton(if (naviAvailable) "길 안내 및 기록 시작" else "기록 시작", "start-drive", Modifier.fillMaxWidth(), chevron = true, onClick = onStart)
                Spacer(Modifier.height(d.u(8)))
                T(
                    message ?: speech.message ?: when {
                        guidanceActive -> "내비 안내가 이미 켜져 있어요. 시작하면 기록만 더해요"
                        naviAvailable -> "길 안내는 Pleos 내비게이션으로 연결돼요"
                        else -> "내비게이션에 연결할 수 없어 기록만 시작해요"
                    },
                    d.t(16), Modifier.fillMaxWidth().testTag("place-note"), color = muted(), align = TextAlign.Center, maxLines = 2,
                )
                // Choosing a place in the navigation app has already started guidance, which may not be wanted yet.
                if (guidanceActive) {
                    Spacer(Modifier.height(d.u(4)))
                    SecondaryButton("내비 안내 끄기", "cancel-route", Modifier.fillMaxWidth(), onClick = onCancelRoute)
                }
            }, stackedHeight = 400) { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(d.u(10))) {
                    // A banner, because the bundled photos are small and blur when they fill the pane.
                    if (placeHasPhoto(place.name)) PlacePhoto(place.name, Modifier.fillMaxWidth().aspectRatio(3.2f))
                    PlaceMap(current, place, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

// --- 03 내 기록: 이전 드라이브, 사진 카드 ------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(store: JourneyStore, routeMaps: RouteMapCache, records: List<RecordV>, onRecord: (RecordV) -> Unit, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sorted = remember(records) { records.sortedByDescending { it.startedAtMillis } }
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, null, if (sorted.any { it.simulated }) ({ SimulationBadge() }) else null, title = "내 기록")
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = d.u(22))) {
            Tabs(listOf("이전 드라이브", "사진 카드"), tab) { tab = it }
            Spacer(Modifier.height(d.u(12)))
            when {
                sorted.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    T("아직 기록한 드라이브가 없어요", d.t(20), Modifier.testTag("history-empty"), color = muted(), align = TextAlign.Center)
                }
                tab == 0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                    sorted.forEach { record -> DriveRow(record) { onRecord(record) } }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    if (!BuildConfig.PHONE_LINK_ENABLED) {
                        T("사진은 휴대폰 연동을 추가한 뒤 넣을 수 있어요. 지금은 경로로 카드를 만들어요", d.t(16), Modifier.testTag("photo-note"), color = muted(), maxLines = 2)
                    }
                    // Sending the area of a drive to an outside service is stated where its result appears.
                    T("지도는 정차 중에만 받아요. 이때 주행 지역이 지도 서비스(OpenFreeMap)로 전달돼요", d.t(16), Modifier.testTag("map-privacy-note"), color = muted(), maxLines = 2)
                    Spacer(Modifier.height(d.u(10)))
                    LazyVerticalGrid(
                        GridCells.Fixed(if (d.stacked) 1 else 3), Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(d.u(12)), verticalArrangement = Arrangement.spacedBy(d.u(12)),
                    ) {
                        items(sorted, key = { it.id }) { record -> DrivePoster(store, routeMaps, record) { onRecord(record) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveRow(record: RecordV, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(72), 64.dp)).clip(RoundedCornerShape(d.u(10))).background(soft())
            .pressClick(onClick = onClick).padding(horizontal = d.u(18)).testTag("drive-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(record.placeName, d.t(20), bold = true, maxLines = 1)
            T(recordDate(record.startedAtMillis) + labels(record), d.t(15), color = muted(), maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            T("${distanceText(record.distanceKm)} km", d.t(24), maxLines = 1, display = true)
            T("${minutes(record.elapsedSeconds)}분 · ${distanceLabel(record.distanceSource)}", d.t(14), color = muted(), maxLines = 1)
        }
        Spacer(Modifier.width(d.u(14)))
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(20), fg())
    }
}

private fun labels(record: RecordV) = buildString {
    if (record.simulated) append(" · 시뮬레이션")
    if (record.status == RecordStatus.PARTIAL) append(" · 불완전한 기록")
}

/** A keepsake rather than a list row: a title, the route, and the distance set as large as it will go. */
@Composable
private fun DrivePoster(store: JourneyStore, routeMaps: RouteMapCache, record: RecordV, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val photo = rememberRecordPhoto(store, record)
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(14))).background(if (pressed) DriveCardPressed else DriveCardBackground)
            .pressClick(source = press, indication = null, onClick = onClick).padding(d.u(14)).testTag("drive-card"),
    ) {
        T("A DRIVE TO REMEMBER", d.t(14), color = DriveCardMuted, maxLines = 1, tracking = 0.2f)
        T("${shortName(record.placeName)}에 남긴 하루", d.t(24), color = DriveCardText, maxLines = 1, display = true)
        Spacer(Modifier.height(d.u(8)))
        // The image's own shape, so cropping cannot remove the map attribution drawn on it.
        val visual = Modifier.fillMaxWidth().aspectRatio(ROUTE_IMAGE_ASPECT)
        if (photo != null) PlacePhoto(record.placeName, visual, photo)
        else RouteVisual(routeMaps, record, null, visual)
        Spacer(Modifier.height(d.u(8)))
        Row(verticalAlignment = Alignment.Bottom) {
            T(distanceText(record.distanceKm), d.t(46), color = DriveCardText, maxLines = 1, display = true)
            if (record.distanceKm != null) T("km", d.t(22), Modifier.padding(start = d.u(5), bottom = d.u(6)), color = DriveCardText, maxLines = 1, display = true)
            Spacer(Modifier.weight(1f))
            // Kept beside the distance so a simulated drive says so without scrolling.
            Column(Modifier.padding(bottom = d.u(4)), horizontalAlignment = Alignment.End) {
                T(labels(record).removePrefix(" · ").ifEmpty { distanceLabel(record.distanceSource) }, d.t(14), color = DriveCardMuted, maxLines = 1)
                T("${minutes(record.elapsedSeconds)}분 · ${posterDate(record.startedAtMillis)}", d.t(15), color = DriveCardText, maxLines = 1)
            }
        }
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
