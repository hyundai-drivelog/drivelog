package com.drivelog.app.feature.car

import androidx.compose.foundation.Canvas
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

fun shortName(name: String) = name.removeSuffix("해수욕장").removeSuffix("해변").trim().ifEmpty { name }
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
        Heading("주행 중이에요", 36, align = TextAlign.Center)
        Spacer(Modifier.height(d.u(10)))
        T("정차 후 이어서 사용할 수 있어요", d.t(20), color = muted(), align = TextAlign.Center)
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
    Box(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        if (!d.stacked && parked) RoadLines(Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            // No back arrow: Back hides the app everywhere else, and ending a drive has its own labelled button.
            TopBar(null, null, if (recording.simulated) ({ SimulationBadge() }) else null)
        }
        Column(
            Modifier.align(Alignment.Center).padding(top = d.u(20)).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (d.stacked) {
                T("드라이브 기록 중", d.t(30), bold = true)
                Spacer(Modifier.height(d.u(18)))
                T(elapsedText(recording.elapsedSeconds), d.t(82), Modifier.testTag("elapsed"), bold = true, maxLines = 1)
                Spacer(Modifier.height(d.u(18)))
                T(recording.place.name, d.t(30), bold = true, align = TextAlign.Center)
                Spacer(Modifier.height(d.u(6)))
                T("$distance km · 추정 거리", d.t(24), color = muted())
            } else {
                T("${withDirectionParticle(shortName(recording.place.name))} 가는 길", d.t(38), align = TextAlign.Center, display = true)
                Spacer(Modifier.height(d.u(10)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(d.u(14), fg())
                    Spacer(Modifier.width(d.u(10)))
                    T("기록 중", d.t(18))
                }
                T(elapsedText(recording.elapsedSeconds), d.t(84), Modifier.testTag("elapsed"), bold = true, maxLines = 1)
                Spacer(Modifier.height(d.u(14)))
                // The label is quieter than the number, so it never competes with the time above.
                Row(verticalAlignment = Alignment.Bottom) {
                    T("$distance km", d.t(36), Modifier.testTag("distance"), bold = true, maxLines = 1)
                    Spacer(Modifier.width(d.u(10)))
                    T("추정 거리", d.t(18), Modifier.padding(bottom = d.u(6)), color = muted(), maxLines = 1)
                }
            }
            if (parked || status != null) {
                Spacer(Modifier.height(d.u(10)))
                Box(Modifier.width(d.u(if (d.stacked) 420 else 190)).height(1.dp).background(line()))
                Spacer(Modifier.height(d.u(8)))
            }
            if (parked) {
                if (recording.restingSinceMillis != null) {
                    T("쉬는 중이에요 · 출발하면 기록이 이어져요", d.t(17), Modifier.testTag("resting"), color = muted(), align = TextAlign.Center)
                    Spacer(Modifier.height(d.u(8)))
                }
                // Reaching P is what makes these usable, so they arrive with it (motion is on only from that moment).
                PrimaryButton("기록 종료", "end-recording", Modifier.width(d.u(280)).appear(parked), onClick = onEnd)
                if (naviAvailable) {
                    Spacer(Modifier.height(d.u(8)))
                    SecondaryButton("내비 화면 보기", "open-navigation", Modifier.width(d.u(280)).appear(parked, 60), onClick = onOpenNavigation)
                }
                if (routeMessage != null) {
                    Spacer(Modifier.height(d.u(6)))
                    T(routeMessage, d.t(15), color = muted(), align = TextAlign.Center)
                }
            } else if (status != null) {
                // Arriving is the one change on this screen the drive has been heading for.
                T(status, d.t(if (arrivalShown) 30 else if (d.stacked) 22 else 17), color = if (arrivalShown) fg() else muted(), bold = arrivalShown, align = TextAlign.Center)
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

@Composable
private fun RoadLines(modifier: Modifier) {
    val color = line()
    val strong = muted().copy(alpha = 0.45f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.58f
        fun side(left: Boolean) {
            fun x(v: Float) = if (left) w * v else w * (1 - v)
            // The road's edge bends toward the horizon, as a real one does in perspective.
            drawPath(Path().apply {
                moveTo(x(0f), h * 0.84f)
                cubicTo(x(0.1f), h * 0.74f, x(0.22f), h * 0.62f, x(0.335f), horizon)
            }, strong, style = Stroke(2.5f * density, cap = StrokeCap.Round))
            drawPath(Path().apply {
                moveTo(x(0f), h * (if (left) 0.42f else 0.50f))
                cubicTo(x(0.05f), h * (if (left) 0.46f else 0.47f), x(0.10f), h * 0.57f, x(0.335f), horizon)
            }, color, style = Stroke(1.6f))
            drawPath(Path().apply {
                moveTo(x(0.10f), h * 0.51f)
                cubicTo(x(0.16f), h * 0.51f, x(0.17f), h * 0.45f, x(0.22f), h * 0.51f)
                cubicTo(x(0.25f), h * 0.54f, x(0.27f), h * 0.57f, x(0.33f), horizon)
            }, color, style = Stroke(1.3f))
            drawPath(Path().apply {
                moveTo(x(0.20f), horizon)
                cubicTo(x(0.23f), h * 0.56f, x(0.25f), h * 0.53f, x(0.28f), h * 0.56f)
                lineTo(x(0.31f), horizon)
            }, color, style = Stroke(1.1f))
        }
        side(true)
        side(false)
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
                        Heading(if (barelyMoved) "기록할 만큼 움직이지 않았어요" else "드라이브를 마칠까요?", 40)
                        Spacer(Modifier.height(d.u(22)))
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Stat(if (hasRoute) distanceText(recording.distanceKm) else "—", if (hasRoute) "km" else "", "추정 거리")
                            Spacer(Modifier.width(d.u(32)))
                            VerticalRule(Modifier.fillMaxHeight())
                            Spacer(Modifier.width(d.u(32)))
                            Stat(elapsedText(recording.elapsedSeconds), "", "기록 시간")
                        }
                        Spacer(Modifier.height(d.u(22)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CarIcon(CarGlyph.PIN, d.u(24), fg())
                            Spacer(Modifier.width(d.u(10)))
                            T(
                                when {
                                    away == null -> "위치 정보를 확인할 수 없어요"
                                    away <= VISIT_CANDIDATE_RADIUS_KM -> "${recording.place.name} 주변"
                                    else -> "${recording.place.name}까지 ${distanceText(away)} km"
                                },
                                d.t(20), bold = true,
                            )
                        }
                        if (barelyMoved) {
                            Spacer(Modifier.height(d.u(14)))
                            T("거의 움직이지 않았어요. 저장하지 않고 끝낼 수도 있어요", d.t(18), Modifier.testTag("barely-moved"), color = muted())
                        }
                        // Told only here, parked: the driving screen shows place, minutes and distance and nothing else.
                        if (recording.storageFailed) {
                            Spacer(Modifier.height(d.u(14)))
                            T("저장 공간이 부족해 경로 일부를 기기에 남기지 못했어요. 앱이 꺼지면 그 구간은 복구되지 않아요", d.t(18), Modifier.testTag("storage-failed"), color = muted(), maxLines = 2)
                        }
                    }
                }, right = {
                    // Without a route there is nothing to place on a map, so none is requested.
                    if (hasRoute) LiveRouteMap(routeMaps, routePoints, null, null, paneFill(280))
                    else Box(paneFill(280).clip(RoundedCornerShape(d.u(14))).background(soft()), contentAlignment = Alignment.Center) {
                        T("기록된 경로가 없어요", d.t(18), Modifier.testTag("no-route"), color = muted())
                    }
                })
            }
            Spacer(Modifier.height(d.u(14)))
            // The irreversible choice sits last, away from the main one.
            Row(Modifier.fillMaxWidth().padding(bottom = d.u(10)), horizontalArrangement = Arrangement.spacedBy(d.u(14))) {
                // While the drive is written out the guide's Loading state holds every choice, so none can race the save.
                PrimaryButton("저장하고 마치기", "confirm-end", Modifier.weight(1f), loading = saving, onClick = onEnd)
                SecondaryButton("계속 기록", "keep-recording", Modifier.weight(1f), enabled = !saving, onClick = onContinue)
                // A stop on the way, such as a rest area: recording goes on and the time is marked as a break.
                if (!barelyMoved && onRest != null) SecondaryButton("잠깐 쉬어요", "rest-recording", Modifier.weight(1f), enabled = !saving, onClick = onRest)
                if (barelyMoved) SecondaryButton(if (confirmDiscard) "한 번 더 누르면 삭제" else "저장 안 함", "discard-recording", Modifier.weight(1f), enabled = !saving, danger = confirmDiscard) {
                    if (confirmDiscard) onDiscard() else confirmDiscard = true
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
            Heading("휴대폰과 여정을 연결하세요", if (preview) 40 else 36, align = TextAlign.Center)
            Spacer(Modifier.height(d.u(4)))
            T(if (preview) "차량에서 기록한 여정을 휴대폰으로 이어볼 수 있어요" else "차량에서 기록한 여정을 휴대폰으로 이어볼 수 있어요", d.t(18), color = muted(), align = TextAlign.Center)
            Spacer(Modifier.height(d.u(16)))
            val steps = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(d.u(16))) {
                    LinkStep(1, "휴대폰에서 여정 열기", when {
                        preview -> "휴대폰에 설치된 여정 앱을 실행하세요"
                        !webUrl.isNullOrBlank() -> "휴대폰 브라우저로 $webUrl 에 접속하세요."
                        else -> "휴대폰 브라우저로 여정 웹 주소에 접속하세요."
                    })
                    if (preview) LinkStep(2, "연결 코드 입력", "여정 앱의 \"차량과 연결\"에서\n${if (d.stacked) "아래" else "오른쪽"} 코드를 입력하세요")
                    else LinkStep(2, "아래 연결 코드 입력", "\"차량과 연결\" 화면에\n코드를 입력하세요.")
                }
            }
            val codeCard = @Composable { modifier: Modifier ->
                Column(
                    modifier.clip(RoundedCornerShape(d.u(14))).background(soft()).padding(d.u(18)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        failed -> T("서버에 연결할 수 없어요", d.t(20), bold = true, align = TextAlign.Center)
                        code == null -> {
                            LoadingIndicator(LoadingStyle.TENSION)
                            Spacer(Modifier.height(d.u(8)))
                            T("코드를 받는 중이에요", d.t(20), color = muted())
                        }
                        expired -> T("코드가 만료되었어요", d.t(20), bold = true)
                        else -> {
                            T("연결 코드 · 남은 시간  ${pad(left / 60)}:${pad(left % 60)}", d.t(17), bold = false)
                            Spacer(Modifier.height(d.u(6)))
                            T("${code.take(3)} ${code.drop(3)}", d.t(if (preview) 64 else 56), Modifier.testTag("link-code"), bold = !preview, maxLines = 1, display = preview)
                            if (preview) T("예시 코드예요. 실제로 연결되지 않아요", d.t(15), Modifier.testTag("preview-note"), color = muted(), align = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(d.u(12)))
                    SecondaryButton("새 코드 받기", "new-code", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, icon = CarGlyph.REFRESH, onClick = onNewCode)
                    if (onPreviewNext != null) {
                        Spacer(Modifier.height(d.u(8)))
                        PrimaryButton("연결 후 화면 보기", "preview-next", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onPreviewNext)
                    }
                }
            }
            if (d.stacked) {
                steps(); Spacer(Modifier.height(d.u(18))); codeCard(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(32))) {
                    PhoneFrame(if (preview) Modifier.width(d.u(136)).height(d.u(256)) else Modifier.width(d.u(112)).height(d.u(210))) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            AppMark(d.u(46).value)
                            Spacer(Modifier.height(d.u(6)))
                            T("여정", d.t(13))
                        }
                    }
                    Box(Modifier.weight(1f)) { steps() }
                    VerticalRule(Modifier.fillMaxHeight())
                    codeCard(Modifier.width(d.u(390)))
                }
            }
        }
    }
}

@Composable
private fun AppMark(sizeDp: Float) {
    val background = fg(); val mark = MaterialTheme.colorScheme.background
    Canvas(Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp * 0.22f).dp)).background(background)) {
        val w = size.width
        drawPath(Path().apply {
            moveTo(w * .14f, w * .72f); lineTo(w * .4f, w * .3f); lineTo(w * .56f, w * .56f)
            lineTo(w * .66f, w * .42f); lineTo(w * .86f, w * .72f); close()
        }, mark)
    }
}

@Composable
private fun LinkStep(number: Int, title: String, body: String) {
    val d = LocalCarDimens.current
    Row {
        Box(Modifier.size(d.u(32)).clip(CircleShape).background(fg()), contentAlignment = Alignment.Center) {
            T("$number", d.t(17), color = MaterialTheme.colorScheme.background, bold = true)
        }
        Spacer(Modifier.width(d.u(14)))
        Column {
            T(title, d.t(20), bold = true)
            Spacer(Modifier.height(d.u(4)))
            T(body, d.t(16), color = muted())
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
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.u(5))) {
                        PlacePhoto(record?.placeName ?: "", Modifier.fillMaxWidth().weight(1f))
                        T(record?.placeName ?: "", d.t(11), bold = true, maxLines = 1)
                        listOf(1f, 0.8f, 0.9f).forEach { width ->
                            Box(Modifier.fillMaxWidth(width).height(d.u(7)).clip(CircleShape).background(soft()))
                        }
                    }
                }
            }
        }
        val status = @Composable {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Heading("기록이 휴대폰에 도착했어요", 32, align = TextAlign.Center)
                Spacer(Modifier.height(d.u(4)))
                T(if (preview) "이제 휴대폰에서 추억으로 완성해 보세요" else "이제 휴대폰에서 더 특별한 추억으로 완성해보세요.", d.t(17), color = muted(), align = TextAlign.Center)
                Spacer(Modifier.height(d.u(12)))
                AnimatedCheck(d.u(62))
                Spacer(Modifier.height(d.u(6)))
                T("동기화 완료", d.t(19), Modifier.testTag("sync-done"), bold = true)
                if (record != null) {
                    Spacer(Modifier.height(d.u(10)))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(10))).background(soft()).padding(vertical = d.u(12)).height(IntrinsicSize.Min)) {
                        listOf(record.placeName, "${distanceText(record.distanceKm)} km", elapsedText(record.elapsedSeconds)).forEachIndexed { i, value ->
                            if (i > 0) VerticalRule(Modifier.fillMaxHeight())
                            T(value, d.t(17), Modifier.weight(1f), bold = true, align = TextAlign.Center, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(d.u(8)))
                    T("${distanceLabel(record.distanceSource)} · 기록 경과 시간", d.t(14), color = muted())
                }
                if (preview) {
                    Spacer(Modifier.height(d.u(8)))
                    T("예시 화면이에요. 실제로 전송되지 않았어요", d.t(15), Modifier.testTag("preview-note"), bold = true, align = TextAlign.Center)
                }
            }
        }
        val next = @Composable {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhoneFrame(Modifier.width(d.u(56)).height(d.u(92))) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CarIcon(CarGlyph.PHOTO, d.u(24), fg()) }
                    }
                    Spacer(Modifier.width(d.u(18)))
                    T("사진 선택과 카드 편집은\n휴대폰에서 이어가세요.", d.t(18))
                }
                Spacer(Modifier.height(d.u(24)))
                PrimaryButton("돌아가기", "back-to-goal", Modifier.fillMaxWidth(), onClick = onBackToGoal)
            }
        }
        if (d.stacked) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
                visual(Modifier.fillMaxWidth().height(d.u(220))); status(); next()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(bottom = d.u(10)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(22))) {
                // A fixed shape: in a tall window, filling the height stretches the phone and blurs the small photo.
                Box(Modifier.weight(0.31f), contentAlignment = Alignment.Center) { visual(Modifier.fillMaxWidth().aspectRatio(0.95f)) }
                Box(Modifier.weight(0.40f)) { status() }
                VerticalRule(Modifier.fillMaxHeight(0.7f))
                Box(Modifier.weight(0.27f)) { next() }
            }
        }
    }
}

// --- 기록 저장 완료, 기록 상세 ----------------------------------------------------------------------

/** Shown after a drive and again from 내 기록; only the latter offers deletion, as a two-step action. */
@Composable
fun RecordSavedScreen(routeMaps: RouteMapCache, record: RecordV?, title: String, onBack: () -> Unit, onHistory: (() -> Unit)?, onDelete: (() -> Unit)?, onPhonePreview: (() -> Unit)? = null, onRecommend: (() -> Unit)? = null, stamp: StampResult? = null) {
    val d = LocalCarDimens.current
    var confirmDelete by rememberConfirmState(record?.id)
    var replay by remember(record?.id) { mutableIntStateOf(0) }
    val splits = remember(record?.id) { record?.let { kmSplits(it.route.toMapPoints(), it.rests.orEmpty()) }.orEmpty() }
    Frame(onBack, simulated = record?.simulated == true) {
        Panes(0.38f, left = {
            Column {
                // The place is the poster's title, so it is not repeated here.
                Heading(title, 28, Modifier.testTag("record-saved"))
                if (record != null) {
                    Spacer(Modifier.height(d.u(18)))
                    RecordStats(record)
                    if (stamp != null) {
                        Spacer(Modifier.height(d.u(14)))
                        StampLine(stamp)
                        // A mockup of the partner model: debug builds only, and it changes nothing.
                        if (com.drivelog.app.BuildConfig.DEBUG) {
                            Spacer(Modifier.height(d.u(8)))
                            BenefitPreview(stamp.place)
                        }
                    }
                    if (splits.size >= 2) {
                        Spacer(Modifier.height(d.u(18)))
                        SplitList(splits)
                    }
                }
                Spacer(Modifier.height(d.u(22)))
                // One size and the main action first: the poster is what this screen is for, not the list of what to do next.
                if (onHistory != null) {
                    PrimaryButton("내 기록 보기", "open-history-after", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onHistory)
                    Spacer(Modifier.height(d.u(8)))
                }
                SecondaryButton(if (onHistory != null) "홈으로" else "돌아가기", "back-from-record", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onBack)
                if (onPhonePreview != null) {
                    Spacer(Modifier.height(d.u(8)))
                    SecondaryButton("휴대폰으로 보내기", "open-phone-preview", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onPhonePreview)
                }
                if (onRecommend != null) {
                    Spacer(Modifier.height(d.u(8)))
                    SecondaryButton("코스 추천하기", "open-course-share", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, onClick = onRecommend)
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(d.u(8)))
                    SecondaryButton(if (confirmDelete) "한 번 더 누르면 삭제" else "기록 삭제", "delete-record", Modifier.fillMaxWidth(), size = ButtonSize.MEDIUM, danger = confirmDelete) {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }
                }
            }
        }, right = {
            // A fixed slice in the scrolling column would be shorter than the image, which then spills over the buttons.
            val frame = if (d.stacked) Modifier.fillMaxWidth() else paneFill(280)
            // Motion is parked-only like every effect, and this screen is never shown while moving.
            val onReplay: (() -> Unit)? = if (record != null && record.route.size >= 2) ({ replay += 1 }) else null
            RecordPoster(routeMaps, record, frame.padding(bottom = d.u(8)), fillHeight = !d.stacked, replay = replay, onReplay = onReplay)
        }, rightFirstWhenStacked = true)
    }
}

/** The drive as a keepsake, as the mockup cards set it, and dark in either theme like the cards. */
@Composable
private fun RecordPoster(routeMaps: RouteMapCache, record: RecordV?, modifier: Modifier, fillHeight: Boolean, replay: Int = 0, onReplay: (() -> Unit)? = null) {
    val d = LocalCarDimens.current
    val shape = RoundedCornerShape(d.u(14))
    // The same order as the cards and the phone card: title, route, the distance, then a footer line.
    Column(modifier.clip(shape).background(DriveCardBackground).border(1.dp, line(), shape).padding(d.u(20)).testTag("record-poster")) {
        CardHeading(record, "나의 드라이브", 30)
        Spacer(Modifier.height(d.u(14)))
        // The map's own shape. A north–south drive's tall map takes the height the pane has, centred.
        val aspect = routeImageAspect(record)
        val tall = aspect < 1f && fillHeight
        Box(
            if (tall) Modifier.align(Alignment.CenterHorizontally).weight(1f).aspectRatio(aspect, matchHeightConstraintsFirst = true)
            else Modifier.align(Alignment.CenterHorizontally).aspectRatio(aspect),
        ) {
            RouteVisual(routeMaps, record, record?.placeName, Modifier.matchParentSize(), drawRoute = true, replay = replay)
            if (onReplay != null) ReplayButton(onReplay, Modifier.align(Alignment.TopEnd).padding(d.u(10)))
        }
        if (tall) Spacer(Modifier.height(d.u(14))) else if (fillHeight) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(d.u(14)))
        if (record != null) {
            CardTearLine(d.u(20), Modifier.padding(bottom = d.u(4)))
            PosterDistance(record, 58, DriveCardMuted)
            T(distanceLabel(record.distanceSource) + if (record.simulated) " · 시뮬레이션" else "", d.t(15), color = DriveCardMuted, maxLines = 1)
            Spacer(Modifier.height(d.u(6)))
            PosterFooter(record)
            MapCredit(routeMaps, record)
        }
    }
}

/** Over the map, where the replay happens. Dark and translucent, so the map stays the subject. */
@Composable
private fun ReplayButton(onClick: () -> Unit, modifier: Modifier) {
    val d = LocalCarDimens.current
    Row(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(12))).background(DriveCardBackground.copy(alpha = 0.78f))
            .pressClick(onClick = onClick).padding(horizontal = d.u(14)).testTag("replay-route"),
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
 * Time per stretch of the drive, from its own recorded points. Each row carries the same numbered marker as the
 * map, so the list and the route read together. A record of the drive, not a ranking of it.
 */
@Composable
private fun SplitList(splits: List<KmSplit>) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxWidth().testTag("splits")) {
        T("구간 기록", d.t(16), color = muted())
        Spacer(Modifier.height(d.u(6)))
        splits.forEachIndexed { index, split ->
            // Down the list in drive order, as the numbered marks appear along the route on the card.
            Row(Modifier.revealOnce(index + 2).fillMaxWidth().padding(vertical = d.u(3)), verticalAlignment = Alignment.CenterVertically) {
                val whole = split.toKm == Math.floor(split.toKm)
                Box(Modifier.size(d.u(28)).clip(CircleShape).background(fg()), contentAlignment = Alignment.Center) {
                    if (whole) T(split.toKm.toInt().toString(), d.t(13), color = MaterialTheme.colorScheme.background, bold = true, maxLines = 1)
                    else Box(Modifier.size(d.u(10)).clip(CircleShape).background(MaterialTheme.colorScheme.background))
                }
                Spacer(Modifier.width(d.u(12)))
                T(splitRange(split), d.t(18), Modifier.weight(1f), color = muted(), maxLines = 1, tabular = true)
                // A break is said beside the stretch it fell in, apart from the driving time.
                if (split.restSeconds >= 60) T("휴게 ${elapsedText(split.restSeconds)} · ", d.t(16), color = muted(), maxLines = 1)
                T(splitTime(split.seconds), d.t(18), bold = true, maxLines = 1, tabular = true)
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
private fun RecordStats(record: RecordV) {
    val d = LocalCarDimens.current
    val shown = rememberCountUp(record.distanceKm ?: 0.0)
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            T(if (record.distanceKm != null) distanceText(shown) else distanceText(null), d.t(84), maxLines = 1, display = true)
            if (record.distanceKm != null) T("km", d.t(34), Modifier.padding(start = d.u(8), bottom = d.u(12)), bold = true)
        }
        T(distanceLabel(record.distanceSource), d.t(16), color = muted())
        Spacer(Modifier.height(d.u(16)))
        Row(Modifier.height(IntrinsicSize.Min)) {
            StatCell(elapsedText(record.elapsedSeconds), "기록 시간")
            Spacer(Modifier.width(d.u(24)))
            VerticalRule(Modifier.fillMaxHeight())
            Spacer(Modifier.width(d.u(24)))
            StatCell(clockRange(clockTime(record.startedAtMillis), clockTime(record.endedAtMillis)), "출발–도착 시각")
        }
        val rest = record.restSeconds()
        if (rest >= 60) {
            Spacer(Modifier.height(d.u(10)))
            T("휴게 ${record.rests.orEmpty().size}회 · ${elapsedText(rest)} 포함", d.t(16), Modifier.testTag("record-rest"), color = muted(), maxLines = 1)
        }
        // The navigation app's own figures, as it reported them. Beside the drive, not against it.
        val naviMeters = record.naviDistanceMeters
        val naviSeconds = record.naviDurationSeconds
        if (naviMeters != null && naviSeconds != null && naviMeters > 0) {
            Spacer(Modifier.height(d.u(6)))
            T(routeSummaryText(RouteSummary(naviMeters, naviSeconds)), d.t(16), Modifier.testTag("record-navi-route"), color = muted(), maxLines = 1)
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    val d = LocalCarDimens.current
    Column {
        T(value, d.t(26), bold = true, maxLines = 1, tabular = true)
        T(label, d.t(15), color = muted(), maxLines = 1)
    }
}

// --- 09 오프라인 임시 저장 --------------------------------------------------------------------------

@Composable
fun OfflineSavedScreen(record: RecordV?, destination: MapPoint?, linked: Boolean, syncing: Boolean, onRetry: () -> Unit, onBackToGoal: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onBackToGoal, simulated = record?.simulated == true) {
        Panes(0.5f, left = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Heading("기록은 안전하게 보관했어요", 32, Modifier.fillMaxWidth())
                Spacer(Modifier.height(d.u(10)))
                CarIcon(CarGlyph.CLOUD_OFF, d.u(70), fg())
                Spacer(Modifier.height(d.u(6)))
                T(if (linked) "연결이 끊겨 차량에 임시 저장했어요" else "휴대폰을 연결하지 않아 차량에 저장했어요", d.t(19), Modifier.testTag("offline-saved"), bold = true, align = TextAlign.Center)
                Spacer(Modifier.height(d.u(12)))
                if (record != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(10))).background(soft()).padding(horizontal = d.u(22), vertical = d.u(12)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CarIcon(CarGlyph.PIN, d.u(26), fg())
                        Spacer(Modifier.width(d.u(18)))
                        Column {
                            T("${record.placeName} · ${elapsedText(record.elapsedSeconds)}", d.t(19), bold = true, maxLines = 1)
                            T(if (syncing) "다시 전송 중" else "동기화 대기", d.t(16), color = muted())
                        }
                    }
                    Spacer(Modifier.height(d.u(10)))
                }
                T(if (linked) "네트워크가 연결되면 다시 전송해요" else "연결하면 이어서 전송해요", d.t(16), color = muted(), align = TextAlign.Center)
                Spacer(Modifier.height(d.u(12)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(12))) {
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
                Heading("저장된 기록을 찾았어요", 32)
                Spacer(Modifier.height(d.u(12)))
                Row(
                    Modifier.border(1.dp, muted(), RoundedCornerShape(d.u(12))).padding(horizontal = d.u(14), vertical = d.u(8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CarIcon(CarGlyph.DOCUMENT, d.u(24), fg())
                    Spacer(Modifier.width(d.u(8)))
                    T("불완전한 기록", d.t(18), Modifier.testTag("partial-badge"), bold = true)
                }
                Spacer(Modifier.height(d.u(12)))
                T("앱이 종료되기 전까지의 기록이에요", d.t(19))
                Spacer(Modifier.height(d.u(22)))
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Stat(distanceText(record.distanceKm), if (record.distanceKm != null) "km" else "", "확인된 구간의 추정 거리")
                    Spacer(Modifier.width(d.u(32)))
                    VerticalRule(Modifier.fillMaxHeight())
                    Spacer(Modifier.width(d.u(32)))
                    Stat(elapsedText(record.elapsedSeconds), "", "기록 시간")
                }
                Spacer(Modifier.height(d.u(24)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(12))) {
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
