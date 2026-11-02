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
import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.GoalV
import com.drivelog.app.core.journey.PlaceV
import com.drivelog.app.core.journey.RecordStatus
import com.drivelog.app.core.journey.RecordV
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

private fun shortName(name: String) = name.removeSuffix("해수욕장").removeSuffix("해변").trim().ifEmpty { name }
/** 으로 after a final consonant except ㄹ, otherwise 로: 송정으로, 광안리로, 다대포로. */
fun withDirectionParticle(name: String): String {
    val last = name.lastOrNull { it in '\uAC00'..'\uD7A3' } ?: return "$name(으)로"
    val finalConsonant = (last - '\uAC00') % 28
    return name + if (finalConsonant == 0 || finalConsonant == 8) "로" else "으로"
}
private fun pad(value: Long) = value.toString().padStart(2, '0')
fun clock(seconds: Long) = "${pad(seconds / 3600)}:${pad(seconds % 3600 / 60)}:${pad(seconds % 60)}"
private fun minutes(seconds: Long) = (seconds / 60).toString()
private fun distanceText(km: Double?) = km?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"
private fun distanceLabel(source: String) = if (source == DistanceSource.VEHICLE) "차량 주행거리" else "추정 거리"

@Composable
private fun Heading(text: String, size: Int = 40, modifier: Modifier = Modifier, align: TextAlign? = null) {
    val d = LocalCarDimens.current
    T(text, d.t(size), modifier.semantics { heading() }, bold = true, align = align)
}

@Composable
private fun Frame(
    onBack: (() -> Unit)?, onTitleHold: (() -> Unit)? = null, simulated: Boolean = false,
    trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit,
) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        TopBar(onBack, onTitleHold, trailing ?: if (simulated) ({ SimulationBadge() }) else null)
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

// --- 01 목표 홈 ------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkControl(linked: Boolean, onLink: () -> Unit, onUnlink: () -> Unit) {
    val d = LocalCarDimens.current
    T(if (linked) "휴대폰 연결 해제" else "휴대폰 연결", d.t(16),
        Modifier.heightIn(min = 64.dp).combinedClickable(role = Role.Button, onClick = if (linked) onUnlink else onLink)
            .padding(horizontal = d.u(10), vertical = d.u(14)).testTag(if (linked) "unlink" else "open-link"), bold = true)
}

@Composable
fun GoalHomeScreen(goal: GoalV, linked: Boolean, pendingSync: Boolean, onSelect: () -> Unit, onLink: () -> Unit, onUnlink: () -> Unit, onHold: () -> Unit) {
    val d = LocalCarDimens.current
    val next = goal.nextPlace
    Frame(null, onHold, trailing = { LinkControl(linked, onLink, onUnlink) }) {
        Panes(0.40f, left = {
            Column(Modifier.padding(start = d.u(42), end = d.u(8))) {
                Heading(goal.name, 40)
                Spacer(Modifier.height(d.u(10)))
                T(if (next != null) "다음 기억은 ${shortName(next.name)}에서" else "모든 장소를 만났어요", d.t(22), color = muted())
                Spacer(Modifier.height(d.u(26)))
                T("${goal.visitedCount} / ${goal.places.size}곳 방문", d.t(19))
                Spacer(Modifier.height(d.u(6)))
                ProgressDots(goal.places.size, goal.visitedCount, Modifier.padding(end = d.u(84)))
                Spacer(Modifier.height(d.u(26)))
                PrimaryButton(if (goal.achieved) "목표 달성 보기" else "장소 선택", "select-place", Modifier.fillMaxWidth(), chevron = true, onClick = onSelect)
                if (pendingSync) {
                    Spacer(Modifier.height(d.u(10)))
                    T("연결되면 기록을 휴대폰으로 보내요", d.t(15), color = muted())
                }
            }
        }, right = {
            PlacePhoto((next ?: goal.places.first()).name, paneFill(240))
            Spacer(Modifier.height(d.u(16)))
            PlaceStatusRow(goal.places)
        })
    }
}

@Composable
private fun PlaceStatusRow(places: List<PlaceV>) {
    val d = LocalCarDimens.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = d.u(8)), verticalAlignment = Alignment.CenterVertically) {
        places.forEachIndexed { index, place ->
            if (index > 0) VerticalRule(Modifier.fillMaxHeight().padding(vertical = d.u(2)))
            Row(Modifier.weight(1f).padding(horizontal = d.u(14)), verticalAlignment = Alignment.CenterVertically) {
                val visited = place.visitRecordId != null
                Box(
                    Modifier.size(d.u(26)).clip(CircleShape).background(if (visited) fg() else Color.Transparent)
                        .border(d.u(1.5f), fg(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (visited) CarIcon(CarGlyph.CHECK, d.u(18), MaterialTheme.colorScheme.background) }
                Spacer(Modifier.width(d.u(12)))
                Column(Modifier.weight(1f)) {
                    T(place.name, d.t(16), bold = true, maxLines = 1)
                    T(if (visited) "방문 완료" else "방문 전", d.t(14), color = muted())
                }
            }
        }
    }
}

// --- Goal-less states ------------------------------------------------------------------------------

@Composable
fun WelcomeScreen(linked: Boolean, onNewGoal: () -> Unit, onLink: () -> Unit, onUnlink: () -> Unit, onHold: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(null, onHold, trailing = { LinkControl(linked, onLink, onUnlink) }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Heading("가보고 싶은 곳을 정해볼까요?", 36, align = TextAlign.Center)
            Spacer(Modifier.height(d.u(12)))
            T("내비의 즐겨찾기와 최근 목적지에서 세 곳을 고르면 돼요.", d.t(20), color = muted(), align = TextAlign.Center)
            Spacer(Modifier.height(d.u(26)))
            PrimaryButton("목표 만들기", "new-goal", Modifier.width(d.u(320)), chevron = true, onClick = onNewGoal)
        }
    }
}

@Composable
fun DrivingLockScreen(goal: GoalV?) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize().padding(d.u(24)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Heading("주행 중이에요", 36, align = TextAlign.Center)
        Spacer(Modifier.height(d.u(10)))
        T("정차 후 이어서 사용할 수 있어요", d.t(20), color = muted(), align = TextAlign.Center)
        if (goal != null) {
            Spacer(Modifier.height(d.u(18)))
            T("${goal.visitedCount} / ${goal.places.size}곳 방문", d.t(20))
        }
    }
}

// --- 02 장소 선택 ----------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlacePickerScreen(
    goal: GoalV, selectedId: String?, simulated: Boolean, message: String?, onBack: () -> Unit, onSelect: (PlaceV) -> Unit,
    onStart: () -> Unit, onNavigate: () -> Unit, showNavigate: Boolean, onEditPlace: (String) -> Unit,
) {
    val d = LocalCarDimens.current
    val selected = goal.places.firstOrNull { it.id == selectedId }
    Frame(onBack, simulated = simulated) {
        Panes(0.58f, left = {
            Column(Modifier.padding(start = d.u(42)), verticalArrangement = Arrangement.spacedBy(d.u(7))) {
                Heading("이번에는 어디로 갈까요?", 32)
                Spacer(Modifier.height(d.u(1)))
                goal.places.forEach { place ->
                    val isSelected = place.id == selectedId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = maxOf(d.u(62), 64.dp)).clip(RoundedCornerShape(d.u(10)))
                            .background(if (isSelected) MaterialTheme.colorScheme.background else soft())
                            .then(if (isSelected) Modifier.border(d.u(2), fg(), RoundedCornerShape(d.u(10))) else Modifier)
                            .combinedClickable(role = Role.RadioButton, onClick = { onSelect(place) })
                            .padding(horizontal = d.u(20)).testTag("place-${place.position}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(d.u(28))) {
                            drawCircle(if (isSelected) Color.Unspecified.takeOrElseBlack() else Color.Gray, size.width / 2, style = Stroke(2.dp.toPx() * d.scale))
                            if (isSelected) drawCircle(Color.Black, size.width * 0.3f)
                        }
                        Spacer(Modifier.width(d.u(18)))
                        Column(Modifier.weight(1f)) {
                            T(place.name, d.t(20), bold = true, maxLines = 1)
                            T(place.address, d.t(15), color = muted(), maxLines = 1)
                        }
                        T(if (place.visitRecordId != null) "방문 완료" else "방문 전", d.t(16), color = muted())
                    }
                }
                Spacer(Modifier.height(d.u(1)))
                PrimaryButton(
                    if (selected != null) "${shortName(selected.name)} 기록 시작" else "장소를 선택해 주세요", "start-recording",
                    Modifier.fillMaxWidth(), chevron = selected != null, enabled = selected != null, onClick = onStart,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T(message ?: "기록 시작은 내비게이션 안내를 시작하지 않아요", d.t(15), Modifier.weight(1f).testTag("picker-note"),
                        color = muted(), align = TextAlign.Center, maxLines = 2)
                    if (selected != null && selected.visitRecordId == null) {
                        T("장소 변경", d.t(16), Modifier.heightIn(min = 64.dp).combinedClickable(role = Role.Button, onClick = { onEditPlace(selected.id) })
                            .padding(horizontal = d.u(10), vertical = d.u(14)).testTag("edit-place"), bold = true)
                    }
                    if (showNavigate && selected != null) {
                        T("내비 안내 받기", d.t(16), Modifier.heightIn(min = 64.dp).combinedClickable(role = Role.Button, onClick = onNavigate)
                            .padding(horizontal = d.u(10), vertical = d.u(14)).testTag("request-route"), bold = true)
                    }
                }
            }
        }, right = {
            Box(paneFill(260).padding(bottom = d.u(10))) {
                PlacePhoto(selected?.name ?: goal.places.first().name, Modifier.fillMaxSize())
                Canvas(Modifier.align(Alignment.Center).size(d.u(46))) {
                    drawPin(Offset(size.width / 2, size.height), size.height, Color.Black, Color.White)
                }
            }
        })
    }
}

private fun Color.takeOrElseBlack() = if (this == Color.Unspecified) Color.Black else this

// --- 03 / 04 기록 중, 12 분할 화면 ------------------------------------------------------------------

@Composable
fun RecordingScreen(
    recording: Recording, parked: Boolean, gear: CarGear, showSimControls: Boolean, naviAvailable: Boolean, routeMessage: String?,
    onEnd: () -> Unit, onSimGear: (CarGear) -> Unit, onOpenNavigation: () -> Unit,
) {
    val d = LocalCarDimens.current
    val distance = if (recording.points.size >= 2) distanceText(recording.distanceKm) else "—"
    Box(Modifier.fillMaxSize().padding(horizontal = d.u(22), vertical = d.u(8))) {
        if (!d.stacked) RoadLines(Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            TopBar(if (parked) onEnd else null, null, if (recording.simulated) ({ SimulationBadge() }) else null)
        }
        Column(
            Modifier.align(Alignment.Center).padding(top = d.u(20)).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (d.stacked) {
                T("드라이브 기록 중", d.t(30), bold = true)
                Spacer(Modifier.height(d.u(18)))
                T(clock(recording.elapsedSeconds), d.t(82), Modifier.testTag("elapsed"), bold = true, maxLines = 1)
                Spacer(Modifier.height(d.u(18)))
                T(recording.place.name, d.t(30), bold = true, align = TextAlign.Center)
                Spacer(Modifier.height(d.u(6)))
                T("$distance km · 추정 거리", d.t(24), color = muted())
            } else {
                T("${withDirectionParticle(shortName(recording.place.name))} 가는 길", d.t(34), bold = true, align = TextAlign.Center)
                Spacer(Modifier.height(d.u(10)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(d.u(14)).clip(CircleShape).background(fg()))
                    Spacer(Modifier.width(d.u(10)))
                    T("기록 중", d.t(18))
                }
                T(clock(recording.elapsedSeconds), d.t(72), Modifier.testTag("elapsed"), bold = true, maxLines = 1)
                T("기록 경과 시간", d.t(17), color = muted())
                Spacer(Modifier.height(d.u(14)))
                T("$distance km", d.t(36), Modifier.testTag("distance"), bold = true)
                T("추정 거리", d.t(17), color = muted())
            }
            Spacer(Modifier.height(d.u(10)))
            Box(Modifier.width(d.u(if (d.stacked) 420 else 190)).height(1.dp).background(line()))
            Spacer(Modifier.height(d.u(8)))
            when {
                parked -> {
                    PrimaryButton("기록 종료", "end-recording", Modifier.width(d.u(280)), onClick = onEnd)
                    if (naviAvailable) {
                        Spacer(Modifier.height(d.u(8)))
                        SecondaryButton("내비 화면 보기", "open-navigation", Modifier.width(d.u(280)), onClick = onOpenNavigation)
                    }
                    if (routeMessage != null) {
                        Spacer(Modifier.height(d.u(6)))
                        T(routeMessage, d.t(15), color = muted(), align = TextAlign.Center)
                    }
                }
                recording.locationStale -> T("위치 신호를 기다리는 중이에요", d.t(17), color = muted())
                d.stacked -> T("정차 후 종료 가능", d.t(22), color = muted())
                recording.arrived -> T("목적지 근처예요 · 정차 후 기록을 종료할 수 있어요", d.t(17), color = muted(), align = TextAlign.Center)
                else -> T("정차 후 기록을 종료할 수 있어요", d.t(17), color = muted())
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
            drawLine(strong, Offset(x(0f), h * 0.84f), Offset(x(0.335f), horizon), 2.5f, StrokeCap.Round)
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
fun StopConfirmScreen(recording: Recording, onEnd: () -> Unit, onContinue: () -> Unit) {
    val d = LocalCarDimens.current
    val away = recording.kmToPlace()
    val hasRoute = recording.points.size >= 2
    Frame(onContinue, simulated = recording.simulated) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Panes(0.5f, left = {
                    Column(Modifier.padding(start = d.u(42))) {
                        Heading("드라이브를 마칠까요?", 40)
                        Spacer(Modifier.height(d.u(22)))
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Stat(if (hasRoute) distanceText(recording.distanceKm) else "—", if (hasRoute) "km" else "", "추정 거리")
                            VerticalRule(Modifier.fillMaxHeight().padding(horizontal = d.u(2)).padding(start = d.u(40)))
                            Spacer(Modifier.width(d.u(40)))
                            Stat(minutes(recording.elapsedSeconds), "분", "기록 경과 시간")
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
                    }
                }, right = {
                    RouteMap(recording.points.map { MapPoint(it.latitude, it.longitude, it.atMillis) }, null, null, paneFill(280))
                })
            }
            Spacer(Modifier.height(d.u(14)))
            Row(Modifier.fillMaxWidth().padding(bottom = d.u(10)), horizontalArrangement = Arrangement.spacedBy(d.u(14))) {
                PrimaryButton("기록 종료", "confirm-end", Modifier.weight(1f), onClick = onEnd)
                SecondaryButton("계속 기록", "keep-recording", Modifier.weight(1f), onClick = onContinue)
            }
        }
    }
}

// --- 06 방문 확인 ----------------------------------------------------------------------------------

@Composable
fun VisitConfirmScreen(goal: GoalV?, record: RecordV, onConfirm: () -> Unit, onLater: () -> Unit) {
    val d = LocalCarDimens.current
    val place = goal?.places?.firstOrNull { it.id == record.placeId }
    val last = record.route.lastOrNull()
    val away = if (last != null && place != null) haversineKm(last[0], last[1], place.latitude, place.longitude) else null
    val count = goal?.visitedCount ?: 0
    val total = goal?.places?.size ?: 0
    val already = place?.visitRecordId != null
    Frame(onLater, simulated = record.simulated) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.u(42)), verticalArrangement = Arrangement.Center) {
                Heading("${shortName(record.placeName)}에 다녀오셨나요?", 40)
                Spacer(Modifier.height(d.u(4)))
                T(
                    when {
                        away == null -> "위치 정보가 부족해요. 직접 확인해 주세요"
                        away <= VISIT_CANDIDATE_RADIUS_KM -> "목적지 근처에서 기록이 종료됐어요"
                        else -> "목적지에서 ${distanceText(away)} km 떨어진 곳에서 종료됐어요"
                    },
                    d.t(22), color = muted(),
                )
                Spacer(Modifier.height(d.u(18)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlacePhoto(record.placeName, Modifier.width(d.u(290)).height(d.u(120)))
                    Spacer(Modifier.width(d.u(30)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(8))) {
                        T(record.placeName, d.t(25), bold = true)
                        if (!place?.address.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                            CarIcon(CarGlyph.PIN, d.u(20), fg())
                            Spacer(Modifier.width(d.u(8)))
                            T(place.address, d.t(18), color = muted())
                        }
                        T(
                            when {
                                goal == null || place == null -> "확인하면 목표에 반영돼요"
                                already -> "이미 확인한 장소예요. 진행률은 그대로예요"
                                else -> "확인하면 $count / $total  →  ${count + 1} / $total"
                            },
                            d.t(19),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = d.u(10)), horizontalArrangement = Arrangement.spacedBy(d.u(14))) {
                PrimaryButton("방문 확인", "confirm-visit", Modifier.weight(1f), onClick = onConfirm)
                SecondaryButton("나중에", "visit-later", Modifier.weight(1f), onClick = onLater)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = d.u(8)), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CarIcon(CarGlyph.INFO, d.u(20), muted())
                Spacer(Modifier.width(d.u(8)))
                T("방문 여부는 직접 확인한 후 반영돼요", d.t(16), color = muted())
            }
        }
    }
}

// --- 07 휴대폰 연결 --------------------------------------------------------------------------------

@Composable
fun PhoneLinkScreen(code: String?, expiresAt: Long, webUrl: String?, failed: Boolean, onBack: () -> Unit, onNewCode: () -> Unit) {
    val d = LocalCarDimens.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = ((expiresAt - now) / 1000).coerceAtLeast(0)
    val expired = code != null && left == 0L
    Frame(onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Heading("휴대폰과 여정을 연결하세요", 36, align = TextAlign.Center)
            Spacer(Modifier.height(d.u(4)))
            T("차량에서 기록한 여정을 휴대폰으로 이어볼 수 있습니다.", d.t(18), color = muted(), align = TextAlign.Center)
            Spacer(Modifier.height(d.u(16)))
            val steps = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(d.u(16))) {
                    LinkStep(1, "휴대폰에서 여정 열기", if (!webUrl.isNullOrBlank()) "휴대폰 브라우저로 $webUrl 에 접속하세요." else "휴대폰 브라우저로 여정 웹 주소에 접속하세요.")
                    LinkStep(2, "아래 연결 코드 입력", "\"차량과 연결\" 화면에\n코드를 입력하세요.")
                }
            }
            val codeCard = @Composable { modifier: Modifier ->
                Column(
                    modifier.clip(RoundedCornerShape(d.u(14))).background(soft()).padding(d.u(18)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        failed -> T("서버에 연결할 수 없어요", d.t(20), bold = true, align = TextAlign.Center)
                        code == null -> T("코드를 받는 중이에요", d.t(20), color = muted())
                        expired -> T("코드가 만료되었어요", d.t(20), bold = true)
                        else -> {
                            T("연결 코드 · 남은 시간  ${pad(left / 60)}:${pad(left % 60)}", d.t(17), bold = false)
                            Spacer(Modifier.height(d.u(6)))
                            T("${code.take(3)} ${code.drop(3)}", d.t(56), Modifier.testTag("link-code"), bold = true, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(d.u(12)))
                    SecondaryButton("새 코드 받기", "new-code", Modifier.fillMaxWidth(), onClick = onNewCode)
                }
            }
            if (d.stacked) {
                steps(); Spacer(Modifier.height(d.u(18))); codeCard(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(32))) {
                    PhoneFrame(Modifier.width(d.u(112)).height(d.u(210))) {
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
fun ContinueOnPhoneScreen(record: RecordV?, onBackToGoal: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onBackToGoal, simulated = record?.simulated == true) {
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
                T("이제 휴대폰에서 더 특별한 추억으로 완성해보세요.", d.t(17), color = muted(), align = TextAlign.Center)
                Spacer(Modifier.height(d.u(12)))
                Box(Modifier.size(d.u(62)).border(d.u(3), fg(), CircleShape), contentAlignment = Alignment.Center) { CarIcon(CarGlyph.CHECK, d.u(34), fg()) }
                Spacer(Modifier.height(d.u(6)))
                T("동기화 완료", d.t(19), Modifier.testTag("sync-done"), bold = true)
                if (record != null) {
                    Spacer(Modifier.height(d.u(10)))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(10))).background(soft()).padding(vertical = d.u(12)).height(IntrinsicSize.Min)) {
                        listOf(record.placeName, "${distanceText(record.distanceKm)} km", "${minutes(record.elapsedSeconds)}분").forEachIndexed { i, value ->
                            if (i > 0) VerticalRule(Modifier.fillMaxHeight())
                            T(value, d.t(17), Modifier.weight(1f), bold = true, align = TextAlign.Center, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(d.u(8)))
                    T("${distanceLabel(record.distanceSource)} · 기록 경과 시간", d.t(14), color = muted())
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
                PrimaryButton("목표로 돌아가기", "back-to-goal", Modifier.fillMaxWidth(), onClick = onBackToGoal)
            }
        }
        if (d.stacked) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
                visual(Modifier.fillMaxWidth().height(d.u(220))); status(); next()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(bottom = d.u(10)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(22))) {
                visual(Modifier.weight(0.31f).fillMaxHeight(0.9f))
                Box(Modifier.weight(0.40f)) { status() }
                VerticalRule(Modifier.fillMaxHeight(0.7f))
                Box(Modifier.weight(0.27f)) { next() }
            }
        }
    }
}

// --- 09 오프라인 임시 저장 --------------------------------------------------------------------------

@Composable
fun OfflineSavedScreen(record: RecordV?, destination: MapPoint?, linked: Boolean, syncing: Boolean, onRetry: () -> Unit, onBackToGoal: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onBackToGoal, simulated = record?.simulated == true) {
        Panes(0.5f, left = {
            Column(Modifier.padding(start = d.u(22)), horizontalAlignment = Alignment.CenterHorizontally) {
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
                            T("${record.placeName}  ·  ${minutes(record.elapsedSeconds)}분", d.t(19), bold = true, maxLines = 1)
                            T(if (syncing) "다시 전송 중" else "동기화 대기", d.t(16), color = muted())
                        }
                    }
                    Spacer(Modifier.height(d.u(10)))
                }
                T(if (linked) "네트워크가 연결되면 다시 전송해요" else "연결하면 이어서 전송해요", d.t(16), color = muted(), align = TextAlign.Center)
                Spacer(Modifier.height(d.u(12)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(12))) {
                    PrimaryButton(if (linked) "다시 시도" else "휴대폰 연결", "retry-sync", Modifier.weight(1f), enabled = !syncing, onClick = onRetry)
                    SecondaryButton("목표로 돌아가기", "offline-back", Modifier.weight(1f), onClick = onBackToGoal)
                }
            }
        }, right = {
            RouteMap(record?.route?.toMapPoints().orEmpty(), destination, record?.placeName, paneFill(280).padding(bottom = d.u(8)))
        })
    }
}

// --- 10 중단된 기록 복구 ---------------------------------------------------------------------------

@Composable
fun RecoveryScreen(record: RecordV, destination: MapPoint?, onKeep: () -> Unit, onDelete: () -> Unit) {
    val d = LocalCarDimens.current
    var confirmDelete by remember { mutableStateOf(false) }
    Frame(null, simulated = record.simulated) {
        Panes(0.5f, left = {
            Column(Modifier.padding(start = d.u(22))) {
                Heading("저장된 기록을 찾았어요", 32)
                Spacer(Modifier.height(d.u(12)))
                Row(
                    Modifier.clip(RoundedCornerShape(d.u(12))).background(soft()).padding(horizontal = d.u(14), vertical = d.u(8)),
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
                    Stat(minutes(record.elapsedSeconds), "분", "저장된 경과 시간")
                }
                Spacer(Modifier.height(d.u(24)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(12))) {
                    PrimaryButton("부분 기록 보관", "keep-partial", Modifier.weight(1f), onClick = onKeep)
                    SecondaryButton(if (confirmDelete) "한 번 더 누르면 삭제" else "기록 삭제", "delete-partial", Modifier.weight(1f)) {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }
                }
            }
        }, right = {
            RouteMap(record.route.toMapPoints(), destination, record.placeName, paneFill(280).padding(bottom = d.u(8)), unfinished = true)
        })
    }
}

// --- 11 목표 달성 ----------------------------------------------------------------------------------

@Composable
private fun rememberRecordPhoto(store: JourneyStore, record: RecordV?): ImageBitmap? {
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

@Composable
fun GoalCompleteScreen(store: JourneyStore, goal: GoalV, records: List<RecordV>, syncing: Boolean, onContinue: () -> Unit, onViewGoal: () -> Unit) {
    val d = LocalCarDimens.current
    Frame(onViewGoal) {
        Column(Modifier.fillMaxSize().padding(horizontal = d.u(22))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Heading("세 곳의 바다를 모두 만났어요".takeIf { goal.name.contains("바다") } ?: "세 곳을 모두 만났어요", 38, Modifier.testTag("goal-complete"))
                    T(goal.name, d.t(21), color = muted())
                }
                Column(Modifier.width(d.u(230)), horizontalAlignment = Alignment.End) {
                    T("${goal.visitedCount} / ${goal.places.size}곳 방문 완료", d.t(20), bold = true, maxLines = 1)
                    Spacer(Modifier.height(d.u(10)))
                    Box(Modifier.fillMaxWidth().height(d.u(11)).clip(CircleShape).background(fg()))
                }
            }
            Spacer(Modifier.height(d.u(14)))
            val cards = @Composable { modifier: @Composable (Int) -> Modifier ->
                goal.places.forEachIndexed { index, place ->
                    Box(modifier(index)) {
                        val record = records.firstOrNull { it.id == place.visitRecordId }
                        PlacePhoto(place.name, Modifier.fillMaxSize(), rememberRecordPhoto(store, record))
                        Box(Modifier.align(Alignment.TopEnd).padding(d.u(10)).size(d.u(38)).clip(CircleShape).background(Color(0xFF0E0E0F)), contentAlignment = Alignment.Center) {
                            CarIcon(CarGlyph.CHECK, d.u(22), Color.White)
                        }
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(d.u(84)).clip(RoundedCornerShape(bottomStart = d.u(10), bottomEnd = d.u(10)))
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                        T(shortName(place.name), d.t(22), Modifier.align(Alignment.BottomStart).padding(d.u(16)), color = Color.White, bold = true)
                    }
                }
            }
            if (d.stacked) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(d.u(10))) {
                    cards { Modifier.fillMaxWidth().weight(1f) }
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(d.u(14))) {
                    cards { Modifier.weight(1f).fillMaxHeight() }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = d.u(12)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(14))) {
                if (!d.stacked) T("사진을 더해 완성 카드를 만들어 보세요", d.t(18), Modifier.weight(1f), color = muted())
                PrimaryButton("휴대폰에서 이어가기", "continue-phone", if (d.stacked) Modifier.weight(1f) else Modifier.width(d.u(260)), enabled = !syncing, onClick = onContinue)
                SecondaryButton("목표 보기", "view-goal", Modifier.width(d.u(180)), onClick = onViewGoal)
            }
        }
    }
}
