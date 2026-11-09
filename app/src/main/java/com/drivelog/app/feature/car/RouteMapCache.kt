package com.drivelog.app.feature.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.drivelog.app.core.journey.RecordV
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
/**
 * Required by the data's licences (ODbL for OpenStreetMap, OpenMapTiles' terms); never removed, only kept small.
 * Shown in the map's own corner, as map services do (user decision, 2026-11-09), so cards need no caption line.
 */
const val ATTRIBUTION = "© OpenMapTiles © OpenStreetMap"
private const val IMAGE_WIDTH = 1200
private const val IMAGE_HEIGHT = 800
private const val RENDER_TIMEOUT_MS = 30_000L
private const val RENDER_RETRY_MS = 1_000L
const val ROUTE_IMAGE_ASPECT = IMAGE_WIDTH.toFloat() / IMAGE_HEIGHT
private const val TALL_IMAGE_WIDTH = 900
private const val TALL_IMAGE_HEIGHT = 1125
const val TALL_ROUTE_IMAGE_ASPECT = TALL_IMAGE_WIDTH.toFloat() / TALL_IMAGE_HEIGHT

/**
 * True when a drive is taller than it is wide on the map (Web Mercator, as the tiles are drawn). Such a drive gets a
 * tall map, as on the phone card, so a coast road north is not a thin strip down the middle of a wide one.
 */
fun runsNorthSouth(points: List<MapPoint>): Boolean {
    if (points.size < 2) return false
    val ys = points.map { ln(tan(PI / 4 + Math.toRadians(it.latitude) / 2)) }
    val xs = points.map { Math.toRadians(it.longitude) }
    return ys.max() - ys.min() > (xs.max() - xs.min()) * 0.95
}

/** What the stored map covers: the drive, and its destination when that is close enough to share the frame. */
fun framedPoints(record: RecordV): List<MapPoint> {
    val points = record.route.toMapPoints()
    return points + listOfNotNull(record.destinationPoint().takeIf { destinationFitsFrame(points, it) })
}

/** The shape of a drive's stored map, and so of the frame that shows it whole. */
fun routeImageAspect(record: RecordV?): Float =
    if (record != null && runsNorthSouth(framedPoints(record))) TALL_ROUTE_IMAGE_ASPECT else ROUTE_IMAGE_ASPECT

private fun roadLayer(id: String, classes: String, color: String, narrow: Double, wide: Double) = """
    {"id":"$id","type":"line","source":"openmaptiles","source-layer":"transportation",
     "filter":["in","class",$classes],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"$color","line-width":["interpolate",["exponential",1.5],["zoom"],10,$narrow,16,$wide],
              "line-opacity":["interpolate",["linear"],["zoom"],6,0.35,10,0.75,12,1]}}
"""

private fun mapLayers(land: String, green: String, water: String, minor: String, mid: String, major: String, motorway: String) = """[
    {"id":"land","type":"background","paint":{"background-color":"$land"}},
    {"id":"green","type":"fill","source":"openmaptiles","source-layer":"landcover","filter":["in","class","wood","grass"],"paint":{"fill-color":"$green"}},
    {"id":"water","type":"fill","source":"openmaptiles","source-layer":"water","filter":["!=","brunnel","tunnel"],"paint":{"fill-color":"$water"}},
    ${roadLayer("road-minor", "\"minor\",\"service\"", minor, 0.35, 2.0)},
    ${roadLayer("road-mid", "\"secondary\",\"tertiary\"", mid, 0.7, 4.5)},
    ${roadLayer("road-major", "\"primary\",\"trunk\"", major, 1.1, 7.0)},
    ${roadLayer("road-motorway", "\"motorway\"", motorway, 1.6, 8.0)}
]"""

/** Minor roads come first, so major ones cross on top. Night: near-black sea, roads brighten with importance. Day: gray sea, white roads. */
private val DARK_MAP_LAYERS = mapLayers("#1B1C1F", "#17181B", "#08090A", "#3A3C41", "#5E6168", "#8F9198", "#B4B6BC")
private val LIGHT_MAP_LAYERS = mapLayers("#E9EAEC", "#E1E3E4", "#C8CACE", "#F6F6F7", "#FBFBFC", "#FFFFFF", "#FFFFFF")

/**
 * The provider's tiles under our own layers, as the mockups draw the map: no buildings or labels. Without
 * labels the map needs no sprite or glyphs either. The screen names the place itself.
 */
fun routeMapStyle(providerStyleJson: String, dark: Boolean = true): String {
    val provider = JsonParser.parseString(providerStyleJson).asJsonObject
    val style = JsonObject()
    style.addProperty("version", 8)
    style.add("sources", provider.get("sources"))
    style.add("layers", JsonParser.parseString(if (dark) DARK_MAP_LAYERS else LIGHT_MAP_LAYERS))
    return style.toString()
}

/** A map image and where a coordinate falls on it, in the image's own pixels. */
class MapFrame(val image: ImageBitmap, val project: (MapPoint) -> Offset)

/** Web Mercator through two known corners of the image, so drawing needs no call into the map library. */
fun mercatorProjector(box: DoubleArray, southWestX: Float, southWestY: Float, northEastX: Float, northEastY: Float): (MapPoint) -> Offset {
    fun mercator(latitude: Double) = ln(tan(PI / 4 + Math.toRadians(latitude) / 2))
    val south = mercator(box[0])
    val north = mercator(box[2])
    return { point ->
        Offset(
            (southWestX + (point.longitude - box[1]) / (box[3] - box[1]) * (northEastX - southWestX)).toFloat(),
            (southWestY + (mercator(point.latitude) - south) / (north - south) * (northEastY - southWestY)).toFloat(),
        )
    }
}

/** Bounds with margin around the route; a short drive still shows its surroundings, such as the coast or main roads. */
fun routeBounds(points: List<MapPoint>, margin: Double = 0.22, minSpanDegrees: Double = 0.035): DoubleArray {
    val south = points.minOf { it.latitude }; val north = points.maxOf { it.latitude }
    val west = points.minOf { it.longitude }; val east = points.maxOf { it.longitude }
    val latPad = maxOf((north - south) * margin, (minSpanDegrees - (north - south)) / 2)
    val lonPad = maxOf((east - west) * margin, (minSpanDegrees - (east - west)) / 2)
    return doubleArrayOf(south - latPad, west - lonPad, north + latPad, east + lonPad)
}

/**
 * One map image per finished drive, fetched once while parked and kept on disk, so cards need no network
 * afterwards and nothing is requested while moving. Requests reveal the area of the drive to the map service.
 */
class RouteMapCache(context: Context) {
    private val context = context.applicationContext
    // The folder name carries the map style's version, so older images are drawn again in the current style.
    private val directory = File(this.context.filesDir, "route-maps-v8").apply {
        listOf("route-maps", "route-maps-v2", "route-maps-v3", "route-maps-v4", "route-maps-v5", "route-maps-v6", "route-maps-v7").forEach { File(this@RouteMapCache.context.filesDir, it).deleteRecursively() }
        mkdirs()
    }
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private var providerStyle: String? = null
    // Only one, ever: each new one holds graphics memory until collected, and five in a row aborted the process.
    private var snapshotter: MapSnapshotter? = null
    private var snapshotterStyle: String? = null
    private var snapshotterSize: Pair<Int, Int>? = null
    private val mutableReady = MutableStateFlow(directory.list().orEmpty().map { it.removeSuffix(".jpg") }.toSet())
    val ready: StateFlow<Set<String>> = mutableReady

    fun file(id: String) = File(directory, id.replace(Regex("[^A-Za-z0-9-]"), "_") + ".jpg")

    /**
     * Removes a drive's image and the library's cached map tiles, which show where it went. Tiles are not
     * tracked per drive, so all are cleared; other cards keep their saved images. Packing then shrinks the
     * database file, so the deleted pages do not linger on disk.
     */
    fun delete(id: String) {
        file(id).delete()
        projectionFile(id).delete()
        mutableReady.update { it - file(id).name.removeSuffix(".jpg") }
        clearTiles()
    }

    fun clearTiles() {
        MapLibre.getInstance(context)
        val offline = OfflineManager.getInstance(context)
        offline.clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = offline.packDatabase(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) { Log.w(TAG, "map cache pack failed: $message") }
            })
            override fun onError(message: String) { Log.w(TAG, "map cache clear failed: $message") }
        })
    }

    fun has(id: String) = file(id).name.removeSuffix(".jpg") in mutableReady.value

    /** Newest first. Stops at the first failure, as the rest would fail the same way while offline. */
    suspend fun fill(records: List<RecordV>) = lock.withLock {
        records.filter { it.route.size >= 2 && !has(it.id) }.sortedByDescending { it.startedAtMillis }.forEach { record ->
            if (!render(record)) return@withLock
        }
    }

    /** A map for a screen that is open now, sized to it and kept only in memory. The caller shows it only while parked. */
    suspend fun liveFrame(points: List<MapPoint>, width: Int, height: Int, dark: Boolean): MapFrame? = lock.withLock {
        val style = loadStyle(dark) ?: return@withLock null
        val box = routeBounds(points)
        val snapshot = capture(box, width, height, style) ?: return@withLock null
        runCatching {
            val southWest = snapshot.pixelForLatLng(LatLng(box[0], box[1]))
            val northEast = snapshot.pixelForLatLng(LatLng(box[2], box[3]))
            MapFrame(snapshot.bitmap.asImageBitmap(), mercatorProjector(box, southWest.x, southWest.y, northEast.x, northEast.y))
        }.onFailure { Log.w(TAG, "live map failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /**
     * The map alone, with where coordinates fall on it. The route is drawn over it on screen, so it can be traced
     * again on request, and a change to how routes look needs no new map.
     */
    private suspend fun render(record: RecordV): Boolean {
        val style = loadStyle(dark = true) ?: return false
        val framed = framedPoints(record)
        val box = routeBounds(framed)
        val tall = runsNorthSouth(framed)
        val snapshot = capture(box, if (tall) TALL_IMAGE_WIDTH else IMAGE_WIDTH, if (tall) TALL_IMAGE_HEIGHT else IMAGE_HEIGHT, style) ?: return false
        return runCatching {
            val southWest = snapshot.pixelForLatLng(LatLng(box[0], box[1]))
            val northEast = snapshot.pixelForLatLng(LatLng(box[2], box[3]))
            // The credit is drawn on screen, not into the image, so cropping the map to any frame cannot cut it.
            val image = snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val projection = JsonObject().apply {
                add("box", JsonParser.parseString(box.joinToString(",", "[", "]")))
                add("sw", JsonParser.parseString("[${southWest.x},${southWest.y}]"))
                add("ne", JsonParser.parseString("[${northEast.x},${northEast.y}]"))
            }
            withContext(Dispatchers.IO) {
                projectionFile(record.id).writeText(projection.toString())
                val target = file(record.id)
                val temp = File(directory, target.name + ".tmp")
                temp.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                check(temp.renameTo(target))
            }
            image.recycle()
            mutableReady.update { it + file(record.id).name.removeSuffix(".jpg") }
            true
        }.getOrElse {
            Log.w(TAG, "map image failed: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun projectionFile(id: String) = File(directory, file(id).name.removeSuffix(".jpg") + ".json")

    /** The stored map and its projection, or null when either is missing or unreadable. Reads from disk. */
    fun loadFrame(id: String): MapFrame? = runCatching {
        val json = JsonParser.parseString(projectionFile(id).readText()).asJsonObject
        fun numbers(key: String) = json.getAsJsonArray(key).map { it.asDouble }
        val box = numbers("box").toDoubleArray()
        val sw = numbers("sw")
        val ne = numbers("ne")
        val image = BitmapFactory.decodeFile(file(id).path)?.asImageBitmap() ?: return null
        MapFrame(image, mercatorProjector(box, sw[0].toFloat(), sw[1].toFloat(), ne[0].toFloat(), ne[1].toFloat()))
    }.getOrNull()

    private suspend fun loadStyle(dark: Boolean): String? {
        val provider = providerStyle ?: withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(STYLE_URL).build()).execute().use { response ->
                    check(response.isSuccessful)
                    response.body!!.string().also { routeMapStyle(it) }
                }
            }.onFailure { Log.w(TAG, "map style failed: ${it.javaClass.simpleName}") }.getOrNull()
        }?.also { providerStyle = it }
        return provider?.let { routeMapStyle(it, dark) }
    }

    /** Tries twice: a cancelled render keeps the renderer busy for a moment, and a request in that moment is refused. */
    private suspend fun capture(box: DoubleArray, width: Int, height: Int, style: String): MapSnapshot? = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            snapshot(box, width, height, style) ?: run {
                delay(RENDER_RETRY_MS)
                snapshot(box, width, height, style)
            }
        }
    }

    private suspend fun snapshot(box: DoubleArray, width: Int, height: Int, style: String): MapSnapshot? {
        MapLibre.getInstance(context)
        val bounds = LatLngBounds.Builder().include(LatLng(box[0], box[1])).include(LatLng(box[2], box[3])).build()
        val snapshotter = snapshotter?.also {
            if (snapshotterSize != width to height) it.setSize(width, height)
            if (snapshotterStyle != style) it.setStyleJson(style)
            it.setRegion(bounds)
        } ?: MapSnapshotter(
            context,
            MapSnapshotter.Options(width, height).withPixelRatio(1f).withRegion(bounds).withLogo(false).withAttribution(false).withStyleJson(style),
        ).also { snapshotter = it }
        snapshotterSize = width to height
        snapshotterStyle = style
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { snapshotter.cancel() }
            snapshotter.start({ result -> if (continuation.isActive) continuation.resume(result) }, { error ->
                Log.w(TAG, "map snapshot failed: $error")
                if (continuation.isActive) continuation.resume(null)
            })
        }
    }

    private companion object { const val TAG = "DriveLogMap" }
}

/** The map's credit in its bottom corner: small and faint, on a dark map only. A drawing with no map credits nobody. */
@Composable
fun MapCornerCredit(modifier: Modifier = Modifier) {
    val d = LocalCarDimens.current
    T(
        ATTRIBUTION, d.t(11),
        modifier.padding(d.u(6)).clip(RoundedCornerShape(d.u(4))).background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = d.u(6), vertical = d.u(1)).testTag("map-credit"),
        color = Color.White.copy(alpha = 0.7f), maxLines = 1,
    )
}

/**
 * The route over its stored map when there is one, otherwise over the map-free art, which never needs a network.
 * [replay] draws the route again from the start each time it changes.
 */
@Composable
fun RouteVisual(cache: RouteMapCache, record: RecordV?, label: String?, modifier: Modifier = Modifier, drawRoute: Boolean = false, replay: Int = 0) {
    val ready by cache.ready.collectAsState()
    val hasImage = record != null && cache.has(record.id)
    val frame by produceState<MapFrame?>(null, record?.id, hasImage, ready) {
        value = if (record == null || !hasImage) null else withContext(Dispatchers.IO) { cache.loadFrame(record.id) }
    }
    val points = remember(record?.id) { record?.route?.toMapPoints().orEmpty() }
    Crossfade(frame, modifier, tween(320), label = "route image") { loaded ->
        DriveRouteArt(points, record?.destinationPoint(), label, Modifier.fillMaxSize(), drawRoute || replay > 0, loaded, replay)
    }
}
