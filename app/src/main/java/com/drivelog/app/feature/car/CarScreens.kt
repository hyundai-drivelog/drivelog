package com.drivelog.app.feature.car

import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import com.drivelog.app.ui.theme.NightColors
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import java.util.Locale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivelog.app.BuildConfig
import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.journey.restSeconds
import com.drivelog.app.core.platform.RouteSummary
import com.drivelog.app.core.journey.JourneyStore
import com.drivelog.app.core.platform.CarGear
import com.drivelog.app.core.recording.Recording
import com.drivelog.app.core.recording.VISIT_CANDIDATE_RADIUS_KM
import com.drivelog.app.core.recording.haversineKm
import com.drivelog.app.core.recording.kmToPlace
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The name as a card says it: 송정 for 송정해수욕장, 부산 for 부산광역시. */
fun shortName(name: String) = name.removeSuffix("해수욕장").removeSuffix("해변").removeSuffix("광역시").removeSuffix("특별자치시")
    .removeSuffix("특별자치도").removeSuffix("특별시").trim().ifEmpty { name }
/** 으로 after a final consonant except ㄹ, otherwise 로: 송정으로, 광안리로, 다대포로. */
fun withDirectionParticle(name: String): String {
    // Names come from any search result now, so one can end in Latin letters or digits ("수서역 SRT").
    val last = name.lastOrNull()?.takeIf { it in '\uAC00'..'\uD7A3' } ?: return "$name(으)로"
    val finalConsonant = (last - '\uAC00') % 28
    return name + if (finalConsonant == 0 || finalConsonant == 8) "로" else "으로"
}
private fun pad(value: Long) = value.toString().padStart(2, '0')
/** Whole minutes: this is the largest thing on the driving screen, and a number changing every second draws the eye. */
fun elapsedText(seconds: Long): String {
    val total = seconds / 60
    return when {
        total < 1 -> "1분 미만"
        total < 60 -> "${total}분"
        else -> "${total / 60}시간 ${total % 60}분"
    }
}
fun minutes(seconds: Long) = (seconds / 60).toString()
fun distanceText(km: Double?) = km?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"
fun distanceLabel(source: String) = if (source == DistanceSource.VEHICLE) "차량 주행거리" else "추정 거리"

@Composable
internal fun Heading(text: String, size: Int = 40, modifier: Modifier = Modifier, align: TextAlign? = null) {
    val d = LocalCarDimens.current
    T(text, d.t(size), modifier.semantics { heading() }, align = align, display = true)
}

@Composable
internal fun Frame(
    onBack: (() -> Unit)?, onTitleHold: (() -> Unit)? = null, simulated: Boolean = false,
    trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit,
) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, onTitleHold, trailing ?: if (simulated) ({ SimulationBadge() }) else null)
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/** Two corner radii on the drive-flow screens: cards and maps, and the smaller tiles and chips inside a pane. */
private const val CARD_RADIUS = CarRadius.CARD
private const val TILE_RADIUS = CarRadius.CONTROL
/** The drive card's inner margin, on the 8-unit rhythm. */
private const val CARD_PAD = 24

// --- 휴대폰 연결 버튼 -------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkControl(linked: Boolean, onLink: () -> Unit, onUnlink: () -> Unit) {
    val d = LocalCarDimens.current
    T(if (linked) "휴대폰 연결 해제" else "휴대폰 연결", d.t(16),
        Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).pressClick(onClick = if (linked) onUnlink else onLink)
            .padding(horizontal = d.u(10), vertical = d.u(14)).testTag(if (linked) "unlink" else "open-link"), bold = true)
}

@Composable
fun DrivingLockScreen() {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(d.u(24)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // One headline and one quiet line, as Android for Cars' parked-only notice: nothing else to read while moving.
        Heading("주행 중이에요", CarTextSize.HEADING.baseSp, align = TextAlign.Center)
        Spacer(Modifier.height(d.u(16)))
        T("정차 후 이어서 사용할 수 있어요", d.t(CarTextSize.LABEL), color = muted(), align = TextAlign.Center)
    }
}

// --- 03 / 04 기록 중, 12 분할 화면 ------------------------------------------------------------------

@Composable
fun RecordingScreen(
    recording: Recording, parked: Boolean, gear: CarGear, showSimControls: Boolean, naviAvailable: Boolean, routeMessage: String?, routeEnded: Boolean,
    onEnd: () -> Unit, onSimGear: (CarGear) -> Unit, onOpenNavigation: () -> Unit,
) {
    val d = LocalCarDimens.current
    val distance = if (recording.points.size >= 2) distanceText(recording.distanceKm) else "—"
    // While moving only a change worth a glance is shown; the reminder to stop first is spoken on reaching P.
    val status = when {
        parked -> null
        routeEnded -> "안내가 끝났어요 · 정차하면 저장해요"
        recording.locationStale -> "위치 신호를 기다리는 중이에요"
        recording.arrived -> "목적지 근처예요"
        else -> null
    }
    val arrivalShown = !parked && !routeEnded && !recording.locationStale && recording.arrived
    // A trip computer's hierarchy (Polestar, Tesla): where, then one hero number, then one secondary number.
    // No road drawing or divider: space alone separates the figures from the parked actions.
    Box(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            // No back arrow: Back hides the app everywhere else, and ending a drive has its own labelled button.
            TopBar(null, null, if (recording.simulated) ({ SimulationBadge() }) else null)
        }
        Column(
            Modifier.align(Alignment.Center).padding(top = d.u(24)).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (d.stacked) {
                T("드라이브 기록 중", d.t(CarTextSize.TITLE), bold = true)
                Spacer(Modifier.height(d.u(24)))
                T(elapsedText(recording.elapsedSeconds), d.t(80), Modifier.testTag("elapsed"), maxLines = 1, display = true, tabular = true)
                Spacer(Modifier.height(d.u(24)))
                T(recording.place.name, d.t(CarTextSize.TITLE), bold = true, align = TextAlign.Center)
                Spacer(Modifier.height(d.u(8)))
                T("$distance km · 추정 거리", d.t(CarTextSize.LABEL), color = muted(), tabular = true)
            } else {
                T("${withDirectionParticle(shortName(recording.place.name))} 가는 길", d.t(CarTextSize.HEADING), align = TextAlign.Center, display = true, maxLines = 2)
                Spacer(Modifier.height(d.u(16)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(d.u(12), fg())
                    Spacer(Modifier.width(d.u(8)))
                    T("기록 중", d.t(CarTextSize.BODY), color = muted())
                }
                T(elapsedText(recording.elapsedSeconds), d.t(CarTextSize.HERO), Modifier.testTag("elapsed"), maxLines = 1, display = true, tabular = true)
                Spacer(Modifier.height(d.u(8)))
                // Number, a unit at 0.4 of it, and the label quieter beside: it never competes with the time above.
                Row(verticalAlignment = Alignment.Bottom) {
                    Row(Modifier.semantics(mergeDescendants = true) {}.testTag("distance"), verticalAlignment = Alignment.Bottom) {
                        T(distance, d.t(CarTextSize.HEADING), maxLines = 1, display = true, tabular = true)
                        T(" km", d.t(CarTextSize.CAPTION), Modifier.padding(bottom = d.u(6)), bold = true, maxLines = 1)
                    }
                    Spacer(Modifier.width(d.u(16)))
                    T("추정 거리", d.t(CarTextSize.BODY), Modifier.padding(bottom = d.u(4)), color = muted(), maxLines = 1)
                }
            }
            if (parked || status != null) Spacer(Modifier.height(d.u(32)))
            if (parked) {
                if (recording.restingSinceMillis != null) {
                    T("쉬는 중이에요 · 출발하면 기록이 이어져요", d.t(CarTextSize.BODY), Modifier.testTag("resting"), color = muted(), align = TextAlign.Center)
                    Spacer(Modifier.height(d.u(16)))
                }
                // Reaching P is what makes these usable, so they arrive with it (motion is on only from that moment).
                PrimaryButton("기록 종료", "end-recording", Modifier.width(d.u(320)).appear(parked), onClick = onEnd)
                if (naviAvailable) {
                    Spacer(Modifier.height(d.u(8)))
                    SecondaryButton("내비 화면 보기", "open-navigation", Modifier.width(d.u(320)).appear(parked, 60), onClick = onOpenNavigation)
                }
                if (routeMessage != null) {
                    Spacer(Modifier.height(d.u(8)))
                    T(routeMessage, d.t(CarTextSize.CAPTION), color = muted(), align = TextAlign.Center)
                }
            } else if (status != null) {
                // Arriving is the one change on this screen the drive has been heading for.
                T(status, d.t(if (arrivalShown) CarTextSize.TITLE.baseSp else if (d.stacked) 22 else CarTextSize.BODY.baseSp), color = if (arrivalShown) fg() else muted(), bold = arrivalShown, align = TextAlign.Center)
            }
        }
        if (showSimControls) SimGearControl(gear, onSimGear, Modifier.align(Alignment.BottomEnd))
    }
}

/** Debug only, and only with simulation visibly on: stands in for the gear signal on an emulator. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimGearControl(gear: CarGear, onSimGear: (CarGear) -> Unit, modifier: Modifier) {
    val d = LocalCarDimens.current
    Row(modifier.clip(RoundedCornerShape(d.u(10))).background(soft()), verticalAlignment = Alignment.CenterVertically) {
        listOf(CarGear.PARK to "P", CarGear.DRIVE to "D").forEach { (value, label) ->
            Box(
                Modifier.size(64.dp).background(if (gear == value) fg() else Color.Transparent)
                    .combinedClickable(role = Role.Button, onClick = { onSimGear(value) }).testTag("sim-gear-$label"),
                contentAlignment = Alignment.Center,
            ) { T(label, d.t(20), color = if (gear == value) MaterialTheme.colorScheme.background else fg(), bold = true) }
        }
    }
}

// --- 05 정차 후 기록 종료 ---------------------------------------------------------------------------

@Composable
fun StopConfirmScreen(routeMaps: RouteMapCache, recording: Recording, onEnd: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit, saving: Boolean = false, onRest: (() -> Unit)? = null) {
    val d = LocalCarDimens.current
    val away = recording.kmToPlace()
    val hasRoute = recording.points.size >= 2
    val barelyMoved = barelyMoved(recording.distanceKm, hasRoute)
    var confirmDiscard by rememberConfirmState()
    val routePoints = remember(recording.points) { recording.points.map { MapPoint(it.latitude, it.longitude, it.atMillis) } }
    Frame(onContinue, simulated = recording.simulated) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Panes(0.5f, left = {
                    Column {
                        Heading(if (barelyMoved) "기록할 만큼 움직이지 않았어요" else "드라이브를 마칠까요?", CarTextSize.HEADING.baseSp)
                        Spacer(Modifier.height(d.u(32)))
                        // Apple Fitness' summary grid: figures side by side on one baseline, held apart by space alone.
                        Row(horizontalArrangement = Arrangement.spacedBy(d.u(48))) {
                            Stat(if (hasRoute) distanceText(recording.distanceKm) else "—", if (hasRoute) "km" else "", "추정 거리")
                            Stat(elapsedText(recording.elapsedSeconds), "", "기록 시간")
                        }
                        Spacer(Modifier.height(d.u(32)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CarIcon(CarGlyph.PIN, d.u(24), muted())
                            Spacer(Modifier.width(d.u(8)))
                            T(
                                when {
                                    away == null -> "위치 정보를 확인할 수 없어요"
                                    away <= VISIT_CANDIDATE_RADIUS_KM -> "${recording.place.name} 주변"
                                    else -> "${recording.place.name}까지 ${distanceText(away)} km"
                                },
                                d.t(CarTextSize.BODY), bold = true,
                            )
                        }
                        if (barelyMoved) {
                            Spacer(Modifier.height(d.u(16)))
                            T("거의 움직이지 않았어요. 저장하지 않고 끝낼 수도 있어요", d.t(18), Modifier.testTag("barely-moved"), color = muted())
                        }
                        // Told only here, parked: the driving screen shows place, minutes and distance and nothing else.
                        if (recording.storageFailed) {
                            Spacer(Modifier.height(d.u(16)))
                            T("저장 공간이 부족해 경로 일부를 기기에 남기지 못했어요. 앱이 꺼지면 그 구간은 복구되지 않아요", d.t(18), Modifier.testTag("storage-failed"), color = muted(), maxLines = 2)
                        }
                    }
                }, right = {
                    // Without a route there is nothing to place on a map, so none is requested.
                    if (hasRoute) LiveRouteMap(routeMaps, routePoints, null, null, paneFill(280))
                    else Box(paneFill(280).clip(RoundedCornerShape(d.u(CARD_RADIUS))).background(soft()), contentAlignment = Alignment.Center) {
                        T("기록된 경로가 없어요", d.t(18), Modifier.testTag("no-route"), color = muted())
                    }
                })
            }
            Spacer(Modifier.height(d.u(16)))
            if (!barelyMoved && onRest != null) {
                T("잠깐 쉬는 동안에도 기록은 유지돼요. 출발하면 휴게가 끝나요", d.t(CarTextSize.CAPTION),
                    Modifier.fillMaxWidth().padding(bottom = d.u(12)).testTag("rest-explanation"), color = muted(), maxLines = 2)
            }
            // Full-width choices keep their labels readable when navigation shares the display.
            val actions: @Composable (Modifier, Modifier) -> Unit = { primaryModifier, secondaryModifier ->
                // While the drive is written out the guide's Loading state holds every choice, so none can race the save.
                PrimaryButton("저장하고 마치기", "confirm-end", primaryModifier, loading = saving, onClick = onEnd)
                SecondaryButton("계속 기록", "keep-recording", secondaryModifier, enabled = !saving, onClick = onContinue)
                // A stop on the way, such as a rest area: recording goes on and the time is marked as a break.
                if (!barelyMoved && onRest != null) SecondaryButton("잠깐 쉬어요", "rest-recording", secondaryModifier, enabled = !saving, onClick = onRest)
                if (barelyMoved) SecondaryButton(if (confirmDiscard) "한 번 더 누르면 삭제" else "저장 안 함", "discard-recording", secondaryModifier, enabled = !saving, danger = confirmDiscard) {
                    if (confirmDiscard) onDiscard() else confirmDiscard = true
                }
            }
            if (d.stacked) {
                Column(Modifier.fillMaxWidth().padding(bottom = d.u(8)), verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                    actions(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = d.u(8)), horizontalArrangement = Arrangement.spacedBy(d.u(16))) {
                    actions(Modifier.weight(1.3f), Modifier.weight(1f))
                }
            }
        }
    }
}

// --- 07 휴대폰 연결 --------------------------------------------------------------------------------

@Composable
fun PhoneLinkScreen(
    code: String?, expiresAt: Long, webUrl: String?, failed: Boolean, onBack: () -> Unit, onNewCode: () -> Unit,
    /** Set when this is only a preview: the code connects to nothing, so this moves on in place of a phone. */
    onPreviewNext: (() -> Unit)? = null,
) {
    val d = LocalCarDimens.current
    val preview = onPreviewNext != null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = ((expiresAt - now) / 1000).coerceAtLeast(0)
    val expired = code != null && left == 0L
    Frame(onBack, simulated = preview) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Heading("휴대폰과 DRIVELOG를 연결하세요", CarTextSize.HEADING.baseSp, align = TextAlign.Center)
            Spacer(Modifier.height(d.u(8)))
            T(if (preview) "차량에서 기록한 여정을 휴대폰으로 이어볼 수 있어요" else "차량에서 기록한 여정을 휴대폰으로 이어볼 수 있어요", d.t(CarTextSize.BODY), color = muted(), align = TextAlign.Center)
            Spacer(Modifier.height(d.u(32)))
            val steps = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(d.u(24))) {
                    // The phone side is the DRIVELOG phone app (drivelog-mobile), not a web page; webUrl is no longer shown.
                    LinkStep(1, "휴대폰에서 DRIVELOG 앱 열기", "휴대폰에 설치한 DRIVELOG 앱을 실행하세요.")
                    LinkStep(2, "연결 코드 입력", "앱의 \"차량과 연결\" 화면에\n${if (d.stacked) "아래" else "오른쪽"} 코드를 입력하세요.")
                }
            }
            val codeCard = @Composable { modifier: Modifier ->
                Column(
                    modifier.clip(RoundedCornerShape(d.u(CARD_RADIUS))).background(soft()).padding(d.u(24)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        failed -> T("서버에 연결할 수 없어요", d.t(CarTextSize.BODY), bold = true, align = TextAlign.Center)
                        code == null -> {
                            LoadingIndicator(LoadingStyle.TENSION)
                            Spacer(Modifier.height(d.u(8)))
                            T("코드를 받는 중이에요", d.t(CarTextSize.BODY), color = muted())
                        }
                        expired -> T("코드가 만료되었어요", d.t(CarTextSize.BODY), bold = true)
                        else -> {
                            // The code is the one thing to read: one display size, equal-width digits; the timer is a quiet caption.
                            T("연결 코드 · 남은 시간  ${pad(left / 60)}:${pad(left % 60)}", d.t(CarTextSize.CAPTION), color = muted(), tabular = true)
                            Spacer(Modifier.height(d.u(8)))
                            T("${code.take(3)} ${code.drop(3)}", d.t(CarTextSize.DISPLAY), Modifier.testTag("link-code"), maxLines = 1, display = true, tabular = true)
                            if (preview) T("예시 코드예요. 실제로 연결되지 않아요", d.t(CarTextSize.CAPTION), Modifier.testTag("preview-note"), color = muted(), align = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(d.u(16)))
                    SecondaryButton("새 코드 받기", "new-code", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, icon = CarGlyph.REFRESH, onClick = onNewCode)
                    if (onPreviewNext != null) {
                        Spacer(Modifier.height(d.u(8)))
                        PrimaryButton("연결 후 화면 보기", "preview-next", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onPreviewNext)
                    }
                }
            }
            if (d.stacked) {
                steps(); Spacer(Modifier.height(d.u(24))); codeCard(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(40))) {
                    // Inside the app the brand is the wordmark alone; the route mark stays on the launcher icon.
                    PhoneFrame(if (preview) Modifier.width(d.u(136)).height(d.u(256)) else Modifier.width(d.u(112)).height(d.u(208))) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BrandWordmark(d.t(13), fg()) }
                    }
                    Box(Modifier.weight(1f)) { steps() }
                    codeCard(Modifier.width(d.u(392)))
                }
            }
        }
    }
}

/** Numbered steps as in a setup guide: a small filled number, a bold line, then a quieter one. */
@Composable
private fun LinkStep(number: Int, title: String, body: String) {
    val d = LocalCarDimens.current
    Row {
        Box(Modifier.size(d.u(40)).clip(CircleShape).background(fg()), contentAlignment = Alignment.Center) {
            T("$number", d.t(CarTextSize.BODY), color = MaterialTheme.colorScheme.background, bold = true)
        }
        Spacer(Modifier.width(d.u(16)))
        Column(Modifier.padding(top = d.u(4))) {
            T(title, d.t(CarTextSize.BODY), bold = true)
            Spacer(Modifier.height(d.u(4)))
            T(body, d.t(18), color = muted())
        }
    }
}

// --- 08 모바일로 이어가기 --------------------------------------------------------------------------

@Composable
fun ContinueOnPhoneScreen(record: RecordV?, onBackToGoal: () -> Unit, preview: Boolean = false) {
    val d = LocalCarDimens.current
    Frame(onBackToGoal, simulated = preview || record?.simulated == true) {
        val visual = @Composable { modifier: Modifier ->
            Box(modifier) {
                PlacePhoto(record?.placeName ?: "", Modifier.fillMaxWidth(0.82f).fillMaxHeight(0.86f).align(Alignment.CenterStart))
                PhoneFrame(Modifier.align(Alignment.BottomEnd).fillMaxHeight().fillMaxWidth(0.5f)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.u(4))) {
                        PlacePhoto(record?.placeName ?: "", Modifier.fillMaxWidth().weight(1f))
                        T(record?.placeName ?: "", d.t(12), bold = true, maxLines = 1)
                        listOf(1f, 0.8f, 0.9f).forEach { width ->
                            Box(Modifier.fillMaxWidth(width).height(d.u(7)).clip(CircleShape).background(soft()))
                        }
                    }
                }
            }
        }
        val status = @Composable {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Heading("기록이 휴대폰에 도착했어요", CarTextSize.TITLE.baseSp, align = TextAlign.Center)
                Spacer(Modifier.height(d.u(8)))
                T(if (preview) "이제 휴대폰에서 추억으로 완성해 보세요" else "이제 휴대폰에서 더 특별한 추억으로 완성해보세요.", d.t(18), color = muted(), align = TextAlign.Center)
                Spacer(Modifier.height(d.u(24)))
                AnimatedCheck(d.u(64))
                Spacer(Modifier.height(d.u(8)))
                T("동기화 완료", d.t(CarTextSize.BODY), Modifier.testTag("sync-done"), bold = true)
                if (record != null) {
                    Spacer(Modifier.height(d.u(16)))
                    // One tile, three equal columns: the same place · distance · time the cards end with.
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(TILE_RADIUS))).background(soft()).padding(vertical = d.u(16), horizontal = d.u(8))) {
                        listOf(record.placeName, "${distanceText(record.distanceKm)} km", elapsedText(record.elapsedSeconds)).forEach { value ->
                            T(value, d.t(18), Modifier.weight(1f), bold = true, align = TextAlign.Center, maxLines = 1, tabular = true)
                        }
                    }
                    Spacer(Modifier.height(d.u(8)))
                    T("${distanceLabel(record.distanceSource)} · 기록 경과 시간", d.t(CarTextSize.CAPTION), color = muted())
                }
                if (preview) {
                    Spacer(Modifier.height(d.u(16)))
                    T("예시 화면이에요. 실제로 전송되지 않았어요", d.t(CarTextSize.CAPTION), Modifier.testTag("preview-note"), bold = true, align = TextAlign.Center)
                }
            }
        }
        val next = @Composable {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhoneFrame(Modifier.width(d.u(56)).height(d.u(96))) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CarIcon(CarGlyph.PHOTO, d.u(24), fg()) }
                    }
                    Spacer(Modifier.width(d.u(16)))
                    T("사진 선택과 카드 편집은\n휴대폰에서 이어가세요.", d.t(18), color = muted())
                }
                Spacer(Modifier.height(d.u(32)))
                PrimaryButton("돌아가기", "back-to-goal", Modifier.fillMaxWidth(), onClick = onBackToGoal)
            }
        }
        if (d.stacked) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(24))) {
                visual(Modifier.fillMaxWidth().height(d.u(224))); status(); next()
            }
        } else {
            // Three columns held apart by space alone, no rule between them.
            Row(Modifier.fillMaxSize().padding(bottom = d.u(8)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(32))) {
                // A fixed shape: in a tall window, filling the height stretches the phone and blurs the small photo.
                Box(Modifier.weight(0.31f), contentAlignment = Alignment.Center) { visual(Modifier.fillMaxWidth().aspectRatio(0.95f)) }
                Box(Modifier.weight(0.40f)) { status() }
                Box(Modifier.weight(0.27f)) { next() }
            }
        }
    }
}

// --- 기록 저장 완료, 기록 상세 ----------------------------------------------------------------------

/** Shown after a drive and again from 내 기록; only the latter offers deletion, as a two-step action. */
@Composable
fun RecordSavedScreen(
    routeMaps: RouteMapCache, record: RecordV?, title: String, onBack: () -> Unit, onHistory: (() -> Unit)?, onDelete: (() -> Unit)?,
    onPhonePreview: (() -> Unit)? = null, onRecommend: (() -> Unit)? = null, stamp: StampResult? = null,
    /** Opened from the home card, so leaving goes home rather than to 내 기록. */
    returnsHome: Boolean = false,
    onTrip: (() -> Unit)? = null, tripLabel: String = "여행에 담기",
) {
    val d = LocalCarDimens.current
    var confirmDelete by rememberConfirmState(record?.id)
    var replay by remember(record?.id) { mutableIntStateOf(0) }
    val splits = remember(record?.id) { record?.let { kmSplits(it.route.toMapPoints(), it.rests.orEmpty()) }.orEmpty() }
    val onReplay: (() -> Unit)? = if (record != null && record.route.size >= 2) ({ replay += 1 }) else null
    val motion = LocalMotionEnabled.current
    var open by remember(record?.id) { mutableStateOf(false) }
    // 0 is the face alone, 1 the face with the drive's figures beside it: the card widens to the side, using the
    // landscape screen, and the figures are part of the card rather than a panel next to it.
    val opened by animateFloatAsState(if (open) 1f else 0f, if (motion) tween(RECORD_OPEN_MS, easing = FastOutSlowInEasing) else snap(), label = "record card")
    val figures: @Composable (Modifier) -> Unit = { modifier ->
        NightColors {
            Column(modifier.testTag("card-figures")) {
                // Strava's activity detail: a title, the figures as value-over-label cells, then the stretches as a table.
                Heading(title, 28, Modifier.testTag("record-title"))
                if (record != null) {
                    Spacer(Modifier.height(d.u(24)))
                    RecordStats(record, headline = false)
                    if (stamp != null) {
                        Spacer(Modifier.height(d.u(24)))
                        // The stamp is pressed once, on the card's face; here it is simply listed.
                        StampLine(stamp, press = false)
                    }
                    if (splits.size >= 2) {
                        Spacer(Modifier.height(d.u(32)))
                        SplitList(splits)
                    }
                }
                Spacer(Modifier.height(d.u(32)))
                if (onTrip != null) {
                    SecondaryButton(tripLabel, "record-add-to-trip", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onTrip)
                    Spacer(Modifier.height(d.u(8)))
                }
                if (onHistory != null) {
                    SecondaryButton("내 기록 보기", "open-history-after", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onHistory)
                    Spacer(Modifier.height(d.u(8)))
                }
                if (onPhonePreview != null) {
                    SecondaryButton("휴대폰으로 보내기", "open-phone-preview", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onPhonePreview)
                    Spacer(Modifier.height(d.u(8)))
                }
                if (onRecommend != null) {
                    SecondaryButton("코스 추천하기", "open-course-share", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onRecommend)
                    Spacer(Modifier.height(d.u(8)))
                }
                if (onDelete != null) {
                    // Last and smaller, apart from what to do next: it is the one action that cannot be undone.
                    Spacer(Modifier.height(d.u(24)))
                    SecondaryButton(if (confirmDelete) "한 번 더 누르면 삭제" else "기록 삭제", "delete-record", size = ButtonSize.SMALL, danger = confirmDelete) {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }
                }
            }
        }
    }
    val shape = RoundedCornerShape(d.u(CARD_RADIUS))
    val cardFrame = Modifier.clip(shape).background(DriveCardBackground).border(1.dp, line(), shape).testTag("record-saved")
    // A sideways swipe on the card opens or closes the figures, as the button does.
    val swipe = Modifier.pointerInput(record?.id) {
        var dragged = 0f
        detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
            if (dragged < -SWIPE_PX) open = true else if (dragged > SWIPE_PX) open = false
        }) { _, amount -> dragged += amount }
    }
    // Back closes the card first, as the arrow does; only a closed card leaves the screen.
    androidx.activity.compose.BackHandler(enabled = open) { open = false }
    // Right after saving the card says so; opened from 내 기록 it is simply the drive.
    val eyebrow = if (onHistory != null) "방금 저장했어요" else "나의 드라이브"
    val actions: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.u(16))) {
            if (!d.stacked) PrimaryButton(if (open) "카드만 보기" else "기록 보기", "record-details", Modifier.weight(1f), size = ButtonSize.MEDIUM) { open = !open }
            SecondaryButton(if (onHistory != null || returnsHome) "홈으로" else "돌아가기", "back-from-record", Modifier.weight(1f), size = ButtonSize.MEDIUM, onClick = onBack)
        }
    }
    Frame(if (open) ({ open = false }) else onBack, simulated = record?.simulated == true) {
        if (d.stacked) Column(Modifier.fillMaxSize().padding(bottom = d.u(8))) {
            // A narrow window has no width to spare: the figures follow the face downwards instead.
            Column(Modifier.fillMaxWidth().weight(1f).then(cardFrame).verticalScroll(rememberScrollState())) {
                RecordPoster(routeMaps, record, Modifier.fillMaxWidth().height(d.u(560)), fillHeight = true, replay = replay, onReplay = onReplay, framed = false, eyebrow = eyebrow, pressStamp = stamp?.isNew == true)
                CardTearLine(d.u(CARD_PAD), Modifier.padding(bottom = d.u(16)))
                figures(Modifier.fillMaxWidth().padding(horizontal = d.u(CARD_PAD)).padding(bottom = d.u(CARD_PAD)))
            }
            Spacer(Modifier.height(d.u(16)))
            actions(Modifier.fillMaxWidth())
        } else BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = d.u(8)), contentAlignment = Alignment.TopCenter) {
            val actionsHeight = ButtonSize.MEDIUM.height + d.u(16)
            val face = minOf((maxHeight - actionsHeight) * 0.82f, maxWidth * 0.5f)
            val cardWidth = lerp(face, maxWidth, opened)
            val figuresWidth = maxWidth - face - 1.dp
            Column(Modifier.width(cardWidth).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().weight(1f).then(cardFrame).then(swipe)) {
                    Box(Modifier.width(face).fillMaxHeight()) {
                        // A tap on the face turns the card either way, as the swipe and the button do.
                        RecordPoster(
                            routeMaps, record, Modifier.matchParentSize().pressClick { open = !open },
                            fillHeight = true, replay = replay, onReplay = onReplay, framed = false,
                            eyebrow = eyebrow, pressStamp = stamp?.isNew == true, tearNotch = 1f - opened,
                        )
                        // What says the card opens: a tab on the face's right edge, gone once it is open.
                        if (opened < 1f) Box(
                            Modifier.align(Alignment.CenterEnd).size(64.dp).graphicsLayer { alpha = 1f - opened }
                                .pressClick { open = true }.semantics { contentDescription = "기록 열기" }.testTag("card-open-tab"),
                            contentAlignment = Alignment.CenterEnd,
                        ) { CarIcon(CarGlyph.CHEVRON_RIGHT, 40.dp, DriveCardMuted) }
                    }
                    // Always composed, at no width while closed, so what it remembers (the stamp already pressed) stays.
                    if (opened > 0f) CardTearLineVertical(Modifier.fillMaxHeight().padding(vertical = d.u(CARD_PAD)).graphicsLayer { alpha = opened })
                    Box(Modifier.weight(1f, fill = true).fillMaxHeight().clipToBounds()) {
                        // Full width from the start and uncovered as the card widens, so no line reflows mid-way.
                        figures(
                            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(figuresWidth).fillMaxHeight()
                                .verticalScroll(rememberScrollState()).padding(d.u(32)).graphicsLayer { alpha = opened },
                        )
                    }
                }
                Spacer(Modifier.height(d.u(16)))
                // The buttons follow the card's own width, closed or open.
                actions(Modifier.fillMaxWidth())
            }
        }
    }
}

/** How long the card takes to widen and show the drive's figures beside its face. */
private const val RECORD_OPEN_MS = MOTION_ENTER_MS

/** How far a sideways swipe on the card must travel before it opens or closes. */
private const val SWIPE_PX = 80f

/** The tear line of every card, standing up: dashes and half-circle notches at the two ends. */
@Composable
private fun CardTearLineVertical(modifier: Modifier) {
    Canvas(modifier.width(2.dp)) {
        val dash = 6.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color.White.copy(alpha = 0.22f), Offset(size.width / 2, y), Offset(size.width / 2, minOf(y + dash, size.height)), strokeWidth = 1.dp.toPx())
            y += dash * 2
        }
    }
}

/** The drive as a keepsake, as the mockup cards set it, and dark in either theme like the cards. */
@Composable
private fun RecordPoster(
    routeMaps: RouteMapCache, record: RecordV?, modifier: Modifier, fillHeight: Boolean, replay: Int = 0, onReplay: (() -> Unit)? = null,
    /** False when it is the face of a card that already draws the frame around it. */
    framed: Boolean = true,
    eyebrow: String = "나의 드라이브",
    /** A stamp earned by this drive just now: pressed onto the face once. */
    pressStamp: Boolean = false,
    tearNotch: Float = 1f,
) {
    val d = LocalCarDimens.current
    val shape = RoundedCornerShape(d.u(CARD_RADIUS))
    val outer = if (framed) modifier.clip(shape).background(DriveCardBackground).border(1.dp, line(), shape) else modifier
    // The same order as the cards and the phone card: title, route, the distance, then a footer line.
    // As on a Nike Run Club share card, the distance is the one big number and everything under it is one quiet tone.
    Column(outer.padding(d.u(CARD_PAD)).testTag("record-poster")) {
        CardHeading(record, eyebrow, 30, pressStamp = pressStamp)
        Spacer(Modifier.height(d.u(16)))
        // The map's own shape. A north–south drive's tall map takes the height the pane has, centred.
        val aspect = routeImageAspect(record)
        val tall = aspect < 1f && fillHeight
        Box(
            if (tall) Modifier.align(Alignment.CenterHorizontally).weight(1f).aspectRatio(aspect, matchHeightConstraintsFirst = true)
            else Modifier.align(Alignment.CenterHorizontally).aspectRatio(aspect),
        ) {
            RouteVisual(routeMaps, record, record?.placeName, Modifier.matchParentSize(), drawRoute = true, replay = replay)
            if (onReplay != null) ReplayButton(onReplay, Modifier.align(Alignment.TopEnd).padding(d.u(8)))
        }
        if (tall) Spacer(Modifier.height(d.u(16))) else if (fillHeight) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(d.u(16)))
        if (record != null) {
            CardTearLine(d.u(CARD_PAD), Modifier.padding(bottom = d.u(8)), endNotch = tearNotch)
            PosterDistance(record, 58, DriveCardMuted)
            T(
                listOfNotNull(distanceLabel(record.distanceSource), efficiencyText(record), "시뮬레이션".takeIf { record.simulated }).joinToString(" · "),
                d.t(16), Modifier.testTag("record-card-label"), color = DriveCardMuted, maxLines = 2,
            )
            Spacer(Modifier.height(d.u(8)))
            PosterFooter(record)
        }
    }
}

/** Over the map, where the replay happens. Dark and translucent, so the map stays the subject. */
@Composable
private fun ReplayButton(onClick: () -> Unit, modifier: Modifier) {
    val d = LocalCarDimens.current
    Row(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(12))).background(DriveCardBackground.copy(alpha = 0.78f))
            .pressClick(onClick = onClick).padding(horizontal = d.u(16)).testTag("replay-route"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarIcon(CarGlyph.REFRESH, d.u(22), DriveCardText)
        Spacer(Modifier.width(d.u(8)))
        T("다시 보기", d.t(16), color = DriveCardText, bold = true, maxLines = 1)
    }
}

fun RecordV.destinationPoint(): MapPoint? {
    val latitude = destinationLatitude ?: return null
    val longitude = destinationLongitude ?: return null
    return MapPoint(latitude, longitude)
}

fun splitTime(seconds: Long): String = when {
    seconds < 60 -> "${seconds}초"
    seconds < 3600 -> "${seconds / 60}분 ${(seconds % 60).toString().padStart(2, '0')}초"
    else -> "${seconds / 3600}시간 ${seconds % 3600 / 60}분"
}

private fun splitRange(split: KmSplit): String {
    fun km(value: Double) = if (value == Math.floor(value)) value.toInt().toString() else distanceText(value)
    return "${km(split.fromKm)}–${km(split.toKm)} km"
}

/**
 * Time per stretch of the drive, from its own recorded points. Breaks sit below the driving time so they
 * remain readable in a narrow card. These are the drive's own stretches, with no ranking.
 */
@Composable
private fun SplitList(splits: List<KmSplit>) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxWidth().testTag("splits")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("구간 기록", d.t(CarTextSize.BODY), Modifier.weight(1f), bold = true)
            T("운전 시간", d.t(16), color = muted())
        }
        Spacer(Modifier.height(d.u(8)))
        // A table, so a hairline under each row keeps the two columns aligned; the times read in equal-width digits.
        splits.forEachIndexed { index, split ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(line()))
            Row(Modifier.revealOnce(index + 2).fillMaxWidth().padding(vertical = d.u(12)), verticalAlignment = Alignment.CenterVertically) {
                T(splitRange(split), d.t(18), Modifier.weight(1f), color = muted(), maxLines = 2, tabular = true)
                Spacer(Modifier.width(d.u(16)))
                Column(horizontalAlignment = Alignment.End) {
                    T(splitTime(split.seconds), d.t(18), bold = true, maxLines = 1, tabular = true)
                    if (split.restSeconds >= 60) T("휴게 ${elapsedText(split.restSeconds)}", d.t(16), color = muted(), maxLines = 1)
                }
            }
        }
    }
}

private fun clockTime(millis: Long) = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA).format(java.util.Date(millis))

/** "오전 4:44–4:48": the period is said once when both ends share it. */
fun clockRange(start: String, end: String): String {
    val startPeriod = start.substringBefore(' ')
    return if (end.startsWith("$startPeriod ")) "$start–${end.substringAfter(' ')}" else "$start–$end"
}

/** The distance is the headline, as on the cards. Below it, the other figures share one cell form: value over label. */
@Composable
private fun RecordStats(record: RecordV, headline: Boolean = true) {
    val d = LocalCarDimens.current
    val shown = rememberCountUp(record.distanceKm ?: 0.0)
    Column {
        // Inside the card the distance is already its headline, on the face above.
        if (headline) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(if (record.distanceKm != null) distanceText(shown) else distanceText(null), d.t(84), maxLines = 1, display = true)
                if (record.distanceKm != null) T("km", d.t(34), Modifier.padding(start = d.u(8), bottom = d.u(12)), bold = true)
            }
            T(distanceLabel(record.distanceSource), d.t(16), color = muted())
            Spacer(Modifier.height(d.u(16)))
        }
        // Value-over-label cells on one baseline, apart by space rather than a rule (Apple Fitness' summary grid).
        Row(horizontalArrangement = Arrangement.spacedBy(d.u(40))) {
            StatCell(elapsedText(record.elapsedSeconds), "기록 시간")
            StatCell(clockRange(clockTime(record.startedAtMillis), clockTime(record.endedAtMillis)), "출발–도착 시각")
        }
        // Its own row: beside the times it is cut short on a half-width panel.
        efficiencyCell(record)?.let { (value, label) ->
            Spacer(Modifier.height(d.u(16)))
            Box(Modifier.testTag("record-efficiency")) { StatCell(value, label) }
        }
        val rest = record.restSeconds()
        if (rest >= 60) {
            Spacer(Modifier.height(d.u(16)))
            T("휴게 ${record.rests.orEmpty().size}회 · ${elapsedText(rest)} 포함", d.t(16), Modifier.testTag("record-rest"), color = muted(), maxLines = 2)
        }
        // The navigation app's own figures, as it reported them. Beside the drive, not against it.
        val naviMeters = record.naviDistanceMeters
        val naviSeconds = record.naviDurationSeconds
        if (naviMeters != null && naviSeconds != null && naviMeters > 0) {
            Spacer(Modifier.height(d.u(8)))
            T(routeSummaryText(RouteSummary(naviMeters, naviSeconds)), d.t(16), Modifier.testTag("record-navi-route"), color = muted(), maxLines = 2)
        }
    }
}

/**
 * "6.8 km/kWh" over "전비 · 0.50 kWh 사용". Measured from the vehicle's energy left at start and end, so a
 * simulated drive never has one.
 */
fun efficiencyName(unit: String) = if (unit == "km/kWh") "전비" else "연비"

/** "전비 6.8 km/kWh" for a card's label line. Null without a measurement, and always for a simulated drive. */
fun efficiencyText(record: RecordV): String? {
    val value = record.efficiency ?: return null
    val unit = record.efficiencyUnit ?: return null
    if (record.simulated) return null
    return "${efficiencyName(unit)} ${String.format(Locale.US, "%.1f", value)} $unit"
}

/**
 * Distance over energy across every measured real drive, never an average of averages: a long drive counts for
 * more than a short one. Uses the unit most drives were measured in, so km/kWh and km/L never mix.
 * Returns the number and its unit, or null when no real drive has been measured.
 */
fun averageEfficiency(records: List<RecordV>): Pair<String, String>? {
    val measured = records.filter {
        !it.simulated && it.efficiency != null && it.efficiencyUnit != null && (it.energyUsed ?: 0.0) > 0.0 && it.distanceKm != null
    }
    val unit = measured.groupingBy { it.efficiencyUnit!! }.eachCount().maxByOrNull { it.value }?.key ?: return null
    val same = measured.filter { it.efficiencyUnit == unit }
    val value = same.sumOf { it.distanceKm!! } / same.sumOf { it.energyUsed!! }
    return String.format(Locale.US, "%.1f", value) to unit
}

fun efficiencyCell(record: RecordV): Pair<String, String>? {
    val value = record.efficiency ?: return null
    val unit = record.efficiencyUnit ?: return null
    if (record.simulated) return null
    val name = efficiencyName(unit)
    val usedUnit = if (unit == "km/kWh") "kWh" else "L"
    val used = record.energyUsed?.let { " · ${String.format(Locale.US, "%.2f", it)} $usedUnit 사용" }.orEmpty()
    return "${String.format(Locale.US, "%.1f", value)} $unit" to "$name$used"
}

@Composable
private fun StatCell(value: String, label: String) {
    val d = LocalCarDimens.current
    Column {
        T(value, d.t(28), bold = true, maxLines = 1, tabular = true)
        Spacer(Modifier.height(d.u(4)))
        T(label, d.t(16), color = muted(), maxLines = 2)
    }
}

// --- 09 오프라인 임시 저장 --------------------------------------------------------------------------

@Composable
fun OfflineSavedScreen(record: RecordV?, destination: MapPoint?, linked: Boolean, syncing: Boolean, onRetry: () -> Unit, onBackToGoal: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onBackToGoal, simulated = record?.simulated == true) {
        Panes(0.5f, left = {
            // Left-aligned and text-led like the other summaries: a small status glyph, the headline, the reason, then the drive.
            Column {
                CarIcon(CarGlyph.CLOUD_OFF, d.u(48), muted())
                Spacer(Modifier.height(d.u(16)))
                Heading("기록은 안전하게 보관했어요", CarTextSize.TITLE.baseSp, Modifier.fillMaxWidth())
                Spacer(Modifier.height(d.u(8)))
                T(if (linked) "연결이 끊겨 차량에 임시 저장했어요" else "휴대폰을 연결하지 않아 차량에 저장했어요", d.t(CarTextSize.BODY), Modifier.testTag("offline-saved"), bold = true)
                Spacer(Modifier.height(d.u(24)))
                if (record != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(TILE_RADIUS))).background(soft()).padding(horizontal = d.u(24), vertical = d.u(16)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CarIcon(CarGlyph.PIN, d.u(24), fg())
                        Spacer(Modifier.width(d.u(16)))
                        Column {
                            T("${record.placeName} · ${elapsedText(record.elapsedSeconds)}", d.t(CarTextSize.BODY), bold = true, maxLines = 1)
                            Spacer(Modifier.height(d.u(4)))
                            T(if (syncing) "다시 전송 중" else "동기화 대기", d.t(16), color = muted())
                        }
                    }
                    Spacer(Modifier.height(d.u(16)))
                }
                T(if (linked) "네트워크가 연결되면 다시 전송해요" else "연결하면 이어서 전송해요", d.t(16), color = muted())
                Spacer(Modifier.height(d.u(32)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(16))) {
                    PrimaryButton(if (linked) "다시 시도" else "휴대폰 연결", "retry-sync", Modifier.weight(1f), loading = syncing, icon = if (linked) CarGlyph.REFRESH else null, onClick = onRetry)
                    SecondaryButton("돌아가기", "offline-back", Modifier.weight(1f), onClick = onBackToGoal)
                }
            }
        }, right = {
            RouteMap(record?.route?.toMapPoints().orEmpty(), destination, record?.placeName, paneFill(280).padding(bottom = d.u(8)))
        })
    }
}

// --- 10 중단된 기록 복구 ---------------------------------------------------------------------------

@Composable
fun RecoveryScreen(routeMaps: RouteMapCache, record: RecordV, destination: MapPoint?, onKeep: () -> Unit, onDelete: () -> Unit) {
    val d = LocalCarDimens.current
    var confirmDelete by rememberConfirmState()
    Frame(null, simulated = record.simulated) {
        Panes(0.5f, left = {
            Column {
                // The badge sits above the headline as a status chip, outlined like the simulation badge (not pressable).
                Row(
                    Modifier.border(1.dp, muted(), RoundedCornerShape(d.u(TILE_RADIUS))).padding(horizontal = d.u(12), vertical = d.u(8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CarIcon(CarGlyph.DOCUMENT, d.u(20), fg())
                    Spacer(Modifier.width(d.u(8)))
                    T("불완전한 기록", d.t(16), Modifier.testTag("partial-badge"), bold = true)
                }
                Spacer(Modifier.height(d.u(16)))
                Heading("저장된 기록을 찾았어요", CarTextSize.HEADING.baseSp)
                Spacer(Modifier.height(d.u(8)))
                T("앱이 종료되기 전까지의 기록이에요", d.t(CarTextSize.BODY), color = muted())
                Spacer(Modifier.height(d.u(32)))
                Row(horizontalArrangement = Arrangement.spacedBy(d.u(48))) {
                    Stat(distanceText(record.distanceKm), if (record.distanceKm != null) "km" else "", "확인된 구간의 추정 거리")
                    Stat(elapsedText(record.elapsedSeconds), "", "기록 시간")
                }
                Spacer(Modifier.height(d.u(32)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(16))) {
                    // Keeping and deleting are both the driver's call, so they stay equal in width; only keeping is filled.
                    PrimaryButton("부분 기록 보관", "keep-partial", Modifier.weight(1f), onClick = onKeep)
                    SecondaryButton(if (confirmDelete) "한 번 더 누르면 삭제" else "기록 삭제", "delete-partial", Modifier.weight(1f), danger = confirmDelete) {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }
                }
            }
        }, right = {
            LiveRouteMap(routeMaps, record.route.toMapPoints(), destination, record.placeName, paneFill(280).padding(bottom = d.u(8)), unfinished = true)
        })
    }
}

@Composable
fun rememberRecordPhoto(store: JourneyStore, record: RecordV?): ImageBitmap? {
    val photo by produceState<ImageBitmap?>(null, record?.id, record?.hasPhoto) {
        if (record?.hasPhoto != true) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                store.api.photo(record.id).use { body ->
                    val bytes = body.bytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1600) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return photo
}
