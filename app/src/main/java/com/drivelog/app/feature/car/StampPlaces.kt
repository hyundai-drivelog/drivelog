package com.drivelog.app.feature.car

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
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
 * A place a stamp is given for. WGS84 representative coordinates and per-place source URLs are in app/stamps/catalog.json.
 */
data class StampPlace(
    val id: String, val name: String, val area: String, val latitude: Double, val longitude: Double,
    /** What the place is known for, in a few words. It is what the stamp's picture shows. */
    val motto: String,
    /** A rubber stamp never lands straight. Fixed per place, so a stamp looks the same every time. */
    val tilt: Float,
    /** Drawing motif, independent of the stable ID used for visits. */
    val art: String = id,
)

/** A travel region whose collection is bundled for offline arrival matching. */
data class StampRegion(val id: String, val name: String, val places: List<StampPlace>) {
    val ready get() = places.isNotEmpty()
}

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
fun StampLine(result: StampResult, press: Boolean = result.isNew) {
    val d = LocalCarDimens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(d.u(CarRadius.TILE))).background(soft())
            .padding(horizontal = d.u(CarSpace.M), vertical = d.u(CarSpace.S)).testTag("stamp-line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A stamp just earned is pressed down; one already held is simply there.
        StampArt(result.place, true, d.u(64), Modifier.stampPress(result.place.id, enabled = press))
        Spacer(Modifier.width(d.u(CarSpace.M)))
        Column(Modifier.weight(1f)) {
            T(if (result.isNew) "새 스탬프" else "다시 찾은 곳", d.t(CarTextSize.CAPTION), color = muted(), bold = result.isNew, maxLines = 1)
            T(result.place.name, d.t(CarTextSize.BODY), bold = true, maxLines = 2)
            T(result.place.motto, d.t(CarTextSize.CAPTION), color = muted(), maxLines = 2)
        }
    }
}

/** Open a region from the national overview. Visit counts never suggest a goal or competition. */
@Composable
private fun RegionChip(region: StampRegion, collected: Int, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val shape = RoundedCornerShape(d.u(CarRadius.TILE))
    // One surface treatment, the soft tint: a fill and an outline together read as heavier than a list tile needs.
    Column(
        modifier.heightIn(min = 64.dp).clip(shape).background(soft()).pressClick(Role.Button, onClick = onClick)
            .padding(d.u(CarSpace.M)).testTag("region-${region.id}"),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(region.name, d.t(22), Modifier.weight(1f), bold = true, maxLines = 1)
            CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(24), muted())
        }
        T("${collected}곳 방문 · ${region.places.size}곳", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.XS)), color = if (collected > 0) fg() else muted(), maxLines = 2)
        // Two lines kept for every region: long names (광주's) wrapped and made that one tile taller than its row.
        T(region.places.take(2).joinToString(" · ") { it.name }, d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.S)), color = muted(), maxLines = 2, minLines = 2)
    }
}

/** National overview first, then a region's collection. Never squeeze seventeen tabs into one row. */
@Composable
fun StampBoard(records: List<RecordV>) {
    val d = LocalCarDimens.current
    val stamps = remember(records) { collectedStamps(records) }
    var regionId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val region = StampRegions.firstOrNull { it.id == regionId }
    androidx.activity.compose.BackHandler(enabled = region != null) { regionId = null }
    Column(Modifier.fillMaxSize()) {
        if (region == null) {
            LazyVerticalGrid(
                GridCells.Adaptive(maxOf(d.u(220), 144.dp)), Modifier.fillMaxSize().testTag("stamp-regions"),
                horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)), verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)),
            ) {
                item(key = "national-introduction", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(vertical = d.u(CarSpace.S))) {
                        T("전국에 남기는 드라이브", d.t(CarTextSize.TITLE), display = true)
                        T("${StampRegions.size}개 지역 · ${StampPlaces.size}곳의 관광명소", d.t(18), Modifier.padding(top = d.u(CarSpace.S)), color = muted())
                        T("지역을 골라 다녀온 곳과 다음 여행지를 살펴보세요", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.XS)), color = muted())
                    }
                }
                items(StampRegions, key = { it.id }) { each ->
                    RegionChip(each, each.places.count { it.id in stamps }, Modifier.fillMaxWidth()) { regionId = each.id }
                }
            }
        } else {
            // The region's one title, as an awards page heads its section; the collection line follows below.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.M))) {
                Column(Modifier.weight(1f)) {
                    T(region.name, d.t(CarTextSize.TITLE), display = true, maxLines = 1)
                    T("${region.places.count { it.id in stamps }}곳 방문 · 전체 ${region.places.size}곳", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.XS)), color = muted())
                }
                SecondaryButton("전체 지역", "all-stamp-regions", size = ButtonSize.SMALL, icon = CarGlyph.LIST) { regionId = null }
            }
            Spacer(Modifier.height(d.u(CarSpace.S)))
            RegionStamps(region, stamps)
        }
    }
}

@Composable
private fun RegionStamps(region: StampRegion, stamps: Map<String, Stamp>) {
    val d = LocalCarDimens.current
    val collected = region.places.count { it.id in stamps }
    // The introduction scrolls with the stamps, so a short split-screen window still has room for the collection.
    LazyVerticalGrid(
        GridCells.Adaptive(maxOf(d.u(220), 144.dp)), Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)), verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)),
    ) {
        item(key = "region-introduction", span = { GridItemSpan(maxLineSpan) }) {
            // The header above already names the region, so this is a small section label, the line and the rule.
            Column(Modifier.padding(vertical = d.u(CarSpace.S))) {
                T("${region.name}에서 남긴 스탬프", d.t(CarTextSize.CAPTION), color = muted(), bold = true)
                T(if (collected == 0) "아직 방문한 곳이 없어요" else "${collected}곳의 방문을 간직하고 있어요", d.t(CarTextSize.BODY), Modifier.padding(top = d.u(CarSpace.XS)).testTag("stamp-count"), bold = true)
                T("아래 ${region.places.size}곳에 도착해 기록을 마치면 찍혀요. 시뮬레이션 기록은 세지 않아요", d.t(CarTextSize.CAPTION), Modifier.padding(top = d.u(CarSpace.XS)), color = muted(), maxLines = 3)
            }
        }
        itemsIndexed(region.places, key = { _, it -> it.id }) { index, place ->
            val stamp = stamps[place.id]
            Column(
                // Earned: the soft tile. Not yet: an outline only, so the page divides into two clear states.
                Modifier.revealOnce(index).fillMaxWidth().clip(RoundedCornerShape(d.u(CarRadius.TILE)))
                    .then(if (stamp != null) Modifier.background(soft()) else Modifier.border(1.dp, line(), RoundedCornerShape(d.u(CarRadius.TILE))))
                    .padding(d.u(CarSpace.M)).testTag("stamp-${place.id}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StampArt(place, stamp != null, d.u(if (d.stacked) 96 else 112))
                Spacer(Modifier.height(d.u(CarSpace.S)))
                T(place.area, d.t(CarTextSize.CAPTION), color = muted(), maxLines = 1)
                // Name and line below each keep two lines, so every tile in a region is the same height.
                T(place.name, d.t(18), bold = stamp != null, color = fg(), align = TextAlign.Center, maxLines = 2, minLines = 2)
                T(stamp?.let { "${stampDate(it.record.startedAtMillis)} 첫 방문" } ?: place.motto, d.t(16), color = muted(), align = TextAlign.Center, maxLines = 2, minLines = 2)
            }
        }
    }
}
