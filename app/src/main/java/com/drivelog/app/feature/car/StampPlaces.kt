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

/** Open a region from the national overview. Every tile has the same two lines, so the grid reads as one table. */
@Composable
private fun RegionTile(region: StampRegion, stamps: Map<String, Stamp>, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val shape = RoundedCornerShape(d.u(CarRadius.TILE))
    val collected = region.places.count { it.id in stamps }
    // Match the stamp tiles: visited regions have a soft fill, others have an outline.
    Row(
        modifier.heightIn(min = 88.dp).clip(shape)
            .then(if (collected > 0) Modifier.background(soft()) else Modifier.border(1.dp, line(), shape))
            .pressClick(Role.Button, onClick = onClick)
            .padding(horizontal = d.u(20), vertical = d.u(16)).testTag("region-${region.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(region.name, d.t(24), bold = true, maxLines = 1)
            Spacer(Modifier.height(d.u(2)))
            T(
                if (collected > 0) "${collected}곳 방문 · ${region.places.size}곳" else "${region.places.size}곳", d.t(16),
                color = if (collected > 0) fg() else muted(), maxLines = 1, tabular = true,
            )
        }
        CarIcon(CarGlyph.CHEVRON_RIGHT, d.u(22), muted())
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
            BoardHeader("전국 스탬프", "${StampRegions.size}개 지역 · ${StampPlaces.size}곳 · ${stamps.size}곳 방문")
            LazyVerticalGrid(
                GridCells.Adaptive(maxOf(d.u(220), 144.dp)), Modifier.fillMaxSize().testTag("stamp-regions"),
                horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)), verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)),
            ) {
                items(StampRegions, key = { it.id }) { each ->
                    RegionTile(each, stamps, Modifier.fillMaxWidth()) { regionId = each.id }
                }
            }
        } else {
            val collected = region.places.count { it.id in stamps }
            BoardHeader(region.name, "${collected}곳 방문 · 전체 ${region.places.size}곳", Modifier.testTag("stamp-count")) {
                SecondaryButton("전체 지역", "all-stamp-regions", size = ButtonSize.SMALL, icon = CarGlyph.LIST) { regionId = null }
            }
            RegionStamps(region, stamps)
        }
    }
}

/** The same header on both levels: a title, one line of counts, and the rule in small type. */
@Composable
private fun BoardHeader(title: String, counts: String, countModifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val d = LocalCarDimens.current
    Row(Modifier.fillMaxWidth().padding(top = d.u(8), bottom = d.u(16)), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, d.t(30), display = true, maxLines = 1)
            Spacer(Modifier.height(d.u(4)))
            T(counts, d.t(18), countModifier, color = muted(), maxLines = 1, tabular = true)
            T("도착해 기록을 마치면 찍혀요 · 시뮬레이션 기록은 세지 않아요", d.t(15), Modifier.padding(top = d.u(2)), color = muted(), maxLines = 1)
        }
        if (action != null) {
            Spacer(Modifier.width(d.u(12)))
            action()
        }
    }
}

@Composable
private fun RegionStamps(region: StampRegion, stamps: Map<String, Stamp>) {
    val d = LocalCarDimens.current
    LazyVerticalGrid(
        GridCells.Adaptive(maxOf(d.u(220), 144.dp)), Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)), verticalArrangement = Arrangement.spacedBy(d.u(CarSpace.M)),
    ) {
        itemsIndexed(region.places, key = { _, it -> it.id }) { index, place ->
            val stamp = stamps[place.id]
            val shape = RoundedCornerShape(d.u(CarRadius.TILE))
            // Earned: the soft tile. Not yet: an outline only, so the page divides into two clear states.
            Column(
                Modifier.revealOnce(index).fillMaxWidth().clip(shape)
                    .then(if (stamp != null) Modifier.background(soft()) else Modifier.border(1.dp, line(), shape))
                    .padding(horizontal = d.u(16), vertical = d.u(20)).testTag("stamp-${place.id}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StampArt(place, stamp != null, d.u(if (d.stacked) 96 else 112))
                Spacer(Modifier.height(d.u(14)))
                T(place.name, d.t(19), color = fg(), bold = true, align = TextAlign.Center, maxLines = 1)
                Spacer(Modifier.height(d.u(2)))
                T(
                    stamp?.let { "${stampDate(it.record.startedAtMillis)} 방문" } ?: place.area, d.t(15),
                    color = if (stamp != null) fg() else muted(), align = TextAlign.Center, maxLines = 1, tabular = stamp != null,
                )
                Spacer(Modifier.height(d.u(8)))
                T(place.motto, d.t(15), Modifier.fillMaxWidth().heightIn(min = d.u(40)), color = muted(), align = TextAlign.Center, maxLines = 2)
            }
        }
    }
}
