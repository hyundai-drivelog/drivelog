package com.drivelog.app.feature.car

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivelog.app.core.journey.RecordV
import com.drivelog.app.core.recording.haversineKm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A place a stamp is given for. Coordinates are one representative point read off a public map; check them against
 * public tourism data (e.g. TourAPI) before release.
 */
data class StampPlace(
    val id: String, val name: String, val area: String, val latitude: Double, val longitude: Double,
    /** What the place is known for, in a few words. It is what the stamp's picture shows. */
    val motto: String,
    /** A rubber stamp never lands straight. Fixed per place, so a stamp looks the same every time. */
    val tilt: Float,
)

/** Bundled, so matching happens on the device and no position leaves it. Busan first, where the demo drive runs. */
val StampPlaces = listOf(
    StampPlace("gwangalli", "광안리 해수욕장", "수영구", 35.1532, 129.1186, "광안대교가 보이는 바다", -6f),
    StampPlace("haeundae", "해운대 해수욕장", "해운대구", 35.1587, 129.1604, "파라솔이 늘어선 백사장", 4f),
    StampPlace("songjeong", "송정 해수욕장", "해운대구", 35.1786, 129.1996, "파도 타는 서핑 바다", -3f),
    StampPlace("yonggungsa", "해동용궁사", "기장군", 35.1884, 129.2233, "바닷가 바위 위의 절", 7f),
    StampPlace("oryukdo", "오륙도 스카이워크", "남구", 35.1006, 129.1245, "바다 위 유리 다리와 섬들", -8f),
    StampPlace("huinnyeoul", "흰여울문화마을", "영도구", 35.0786, 129.0447, "절벽 위 계단 골목", 5f),
    StampPlace("taejongdae", "태종대", "영도구", 35.0532, 129.0870, "해안 절벽의 등대", -5f),
    StampPlace("dadaepo", "다대포 해수욕장", "사하구", 35.0466, 128.9662, "낙조가 지는 바다", 3f),
)

/**
 * A region's stamp pack. Busan is the one that exists; the others are the next regions in the plan and are shown as
 * such, with no places, coordinates or dates until a pack for them is actually made.
 */
data class StampRegion(val id: String, val name: String, val places: List<StampPlace>) {
    val ready get() = places.isNotEmpty()
}

val StampRegions = listOf(
    StampRegion("busan", "부산", StampPlaces),
    StampRegion("jeju", "제주", emptyList()),
    StampRegion("gangwon", "강원 해안", emptyList()),
)

/** Arriving is the only condition: within this of the place where the drive ended. Beaches run long, so not tighter. */
const val STAMP_RADIUS_KM = 1.0

/** One stamp and the drive that first earned it. */
data class Stamp(val place: StampPlace, val record: RecordV)

/** Where the driver stopped, which says where they went better than the destination they set out for. */
private fun RecordV.endPoint(): Pair<Double, Double>? = route.lastOrNull()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] }

/** Real drives only: a simulated one is not a visit, and a start pressed by mistake went nowhere. */
private fun RecordV.counts() = !simulated && !barelyMoved(distanceKm, route.size >= 2)

/** The stamp place this drive ended at, if any. The nearest wins where two lie close together. */
fun stampPlaceFor(record: RecordV, places: List<StampPlace> = StampPlaces): StampPlace? {
    if (!record.counts()) return null
    val (lat, lon) = record.endPoint() ?: return null
    return places.map { it to haversineKm(lat, lon, it.latitude, it.longitude) }
        .filter { it.second <= STAMP_RADIUS_KM }.minByOrNull { it.second }?.first
}

/** Worked out from the records each time, so deleting a drive takes its stamp with it and nothing else is stored. */
fun collectedStamps(records: List<RecordV>, places: List<StampPlace> = StampPlaces): Map<String, Stamp> =
    records.sortedBy { it.startedAtMillis }.mapNotNull { record -> stampPlaceFor(record, places)?.let { Stamp(it, record) } }
        .distinctBy { it.place.id }.associateBy { it.place.id }

/** What this drive did for the collection: a first visit gets a new stamp, a return visit names the stamp it already has. */
data class StampResult(val place: StampPlace, val isNew: Boolean)

fun stampResultFor(record: RecordV, records: List<RecordV>, places: List<StampPlace> = StampPlaces): StampResult? {
    val place = stampPlaceFor(record, places) ?: return null
    val first = collectedStamps(records + record, places)[place.id]?.record
    return StampResult(place, first == null || first.id == record.id)
}

private fun stampDate(millis: Long) = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(millis))

/** One line on the saved-drive screen. Parked only, like that whole screen. */
@Composable
fun StampLine(result: StampResult) {
    val d = LocalCarDimens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(10))).background(soft())
            .padding(horizontal = d.u(14), vertical = d.u(10)).testTag("stamp-line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A stamp just earned is pressed down; one already held is simply there.
        StampArt(result.place, true, d.u(64), Modifier.stampPress(result.place.id, enabled = result.isNew))
        Spacer(Modifier.width(d.u(12)))
        Column(Modifier.weight(1f)) {
            T(if (result.isNew) "새 스탬프" else "다시 찾은 곳", d.t(14), color = muted(), maxLines = 1)
            T(result.place.name, d.t(20), bold = true, maxLines = 1)
            T(result.place.motto, d.t(14), color = muted(), maxLines = 1)
        }
    }
}

/** One region to pick: its count when it has a pack, otherwise that it is the next one being prepared. */
@Composable
private fun RegionChip(region: StampRegion, collected: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val fill by androidx.compose.animation.animateColorAsState(if (selected) colors.primary else soft(), motionTween(), label = "region fill")
    val ink by androidx.compose.animation.animateColorAsState(if (selected) colors.onPrimary else if (region.ready) fg() else muted(), motionTween(), label = "region ink")
    Column(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(12))).background(fill).pressClick(Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }.padding(horizontal = d.u(16), vertical = d.u(8)).testTag("region-${region.id}"),
        verticalArrangement = Arrangement.Center,
    ) {
        T(region.name, d.t(18), color = ink, bold = true, maxLines = 1)
        T(if (region.ready) "$collected/${region.places.size}" else "준비 중", d.t(14), color = ink.copy(alpha = 0.72f), maxLines = 1)
    }
}

/** The 스탬프 tab of 내 기록: the region packs, then every place of the chosen one with the day of the first visit. */
@Composable
fun StampBoard(records: List<RecordV>) {
    val d = LocalCarDimens.current
    val stamps = remember(records) { collectedStamps(records) }
    var regionId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(StampRegions.first().id) }
    val region = StampRegions.first { it.id == regionId }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.u(10))) {
            StampRegions.forEach { each ->
                RegionChip(each, each.places.count { it.id in stamps }, each.id == regionId, Modifier.weight(1f)) { regionId = each.id }
            }
        }
        Spacer(Modifier.height(d.u(12)))
        androidx.compose.animation.Crossfade(region, label = "stamp region", animationSpec = motionTween<Float>(MOTION_ENTER_MS)) { shown ->
            if (shown.ready) RegionStamps(shown, stamps) else RegionPlanned(shown)
        }
    }
}

/** A region without a pack yet: what it will be and how packs are made, and nothing that looks like a stamp to earn. */
@Composable
private fun RegionPlanned(region: StampRegion) {
    val d = LocalCarDimens.current
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(d.u(14))).background(soft()).padding(d.u(28)).testTag("region-planned"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(d.u(112)).border(3.dp, line(), CircleShape).appear(region.id), contentAlignment = Alignment.Center) {
            CarIcon(CarGlyph.PIN, d.u(44), muted())
        }
        Spacer(Modifier.height(d.u(18)))
        T("${region.name} 스탬프는 다음 지역으로 준비하고 있어요", d.t(24), bold = true, align = TextAlign.Center)
        Spacer(Modifier.height(d.u(8)))
        T("지역 스탬프는 그 지역의 관광 기관과 함께 장소를 고르고 그림을 그려 만들 계획이에요", d.t(16), color = muted(), align = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun RegionStamps(region: StampRegion, stamps: Map<String, Stamp>) {
    val d = LocalCarDimens.current
    Column(Modifier.fillMaxSize()) {
        T("${region.name} 스탬프 ${region.places.count { it.id in stamps }}/${region.places.size}", d.t(22), Modifier.testTag("stamp-count"), bold = true)
        Spacer(Modifier.height(d.u(4)))
        T("관광지에 도착해 기록을 마치면 스탬프가 찍혀요. 시뮬레이션 기록은 세지 않아요", d.t(16), color = muted(), maxLines = 2)
        Spacer(Modifier.height(d.u(12)))
        LazyVerticalGrid(
            GridCells.Fixed(if (d.stacked) 2 else 4), Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(d.u(12)), verticalArrangement = Arrangement.spacedBy(d.u(12)),
        ) {
            itemsIndexed(region.places, key = { _, it -> it.id }) { index, place ->
                val stamp = stamps[place.id]
                Column(
                    Modifier.revealOnce(index).fillMaxWidth().clip(RoundedCornerShape(d.u(12))).background(soft()).padding(d.u(14)).testTag("stamp-${place.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StampArt(place, stamp != null, d.u(if (d.stacked) 96 else 112))
                    Spacer(Modifier.height(d.u(8)))
                    T(place.name, d.t(18), bold = stamp != null, color = if (stamp != null) fg() else muted(), align = TextAlign.Center, maxLines = 1)
                    T(stamp?.let { "${stampDate(it.record.startedAtMillis)} 방문" } ?: place.motto, d.t(14), color = muted(), align = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Debug builds only, like every mockup: what a partner's arrival benefit would look like, shown beside a stamp. No
 * partner exists, nothing is sent and no record changes; the card says it is an example in two places.
 */
@Composable
fun BenefitPreview(place: StampPlace) {
    val d = LocalCarDimens.current
    var open by remember(place.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.u(10))).border(2.dp, line(), RoundedCornerShape(d.u(10)))
            .padding(horizontal = d.u(14), vertical = d.u(12)).testTag("benefit-preview"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("도착 혜택", d.t(14), Modifier.weight(1f), color = muted(), bold = true, maxLines = 1)
            T("예시 화면", d.t(13), Modifier.clip(RoundedCornerShape(d.u(6))).border(1.dp, line(), RoundedCornerShape(d.u(6))).padding(horizontal = d.u(8), vertical = d.u(2)), color = muted(), maxLines = 1)
        }
        Spacer(Modifier.height(d.u(4)))
        T("${shortName(place.name)} 근처 제휴 카페 · 음료 10% 할인", d.t(18), bold = true, maxLines = 1)
        T("실제 제휴가 없는 예시예요. 도착이 확인된 운전자만 받는 혜택을 보여 줘요", d.t(14), color = muted(), maxLines = 2)
        Spacer(Modifier.height(d.u(8)))
        SecondaryButton(if (open) "접기" else "혜택 보기", "benefit-open", Modifier.fillMaxWidth(), size = ButtonSize.SMALL) { open = !open }
        androidx.compose.animation.AnimatedVisibility(
            open,
            enter = androidx.compose.animation.expandVertically(motionTween()) + androidx.compose.animation.fadeIn(motionTween(MOTION_ENTER_MS)),
            exit = androidx.compose.animation.shrinkVertically(motionTween()) + androidx.compose.animation.fadeOut(motionTween()),
        ) {
            Column(
                Modifier.padding(top = d.u(10)).fillMaxWidth().clip(RoundedCornerShape(d.u(10))).background(soft()).padding(d.u(14)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T("매장에서 이 화면을 보여 주세요", d.t(16), bold = true, align = TextAlign.Center)
                T("예시 코드 · 실제로 쓸 수 없어요", d.t(14), color = muted(), align = TextAlign.Center)
            }
        }
    }
}
