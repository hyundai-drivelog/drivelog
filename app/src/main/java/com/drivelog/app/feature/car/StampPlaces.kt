package com.drivelog.app.feature.car

import androidx.compose.foundation.background
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

/** The 스탬프 tab of 내 기록: every place, collected or not, with the day of the first visit. */
@Composable
fun StampBoard(records: List<RecordV>) {
    val d = LocalCarDimens.current
    val stamps = remember(records) { collectedStamps(records) }
    Column(Modifier.fillMaxSize()) {
        T("부산 스탬프 ${stamps.size}/${StampPlaces.size}", d.t(22), Modifier.testTag("stamp-count"), bold = true)
        Spacer(Modifier.height(d.u(4)))
        T("관광지에 도착해 기록을 마치면 스탬프가 찍혀요. 시뮬레이션 기록은 세지 않아요", d.t(16), color = muted(), maxLines = 2)
        Spacer(Modifier.height(d.u(12)))
        LazyVerticalGrid(
            GridCells.Fixed(if (d.stacked) 2 else 4), Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(d.u(12)), verticalArrangement = Arrangement.spacedBy(d.u(12)),
        ) {
            itemsIndexed(StampPlaces, key = { _, it -> it.id }) { index, place ->
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
