package com.drivelog.app.feature.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.Dispatchers
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
private const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles  Data from OpenStreetMap"
private const val IMAGE_WIDTH = 1200
private const val IMAGE_HEIGHT = 800
private const val RENDER_TIMEOUT_MS = 30_000L
private const val MAP_GAIN = 2.3f
private const val MAP_LIFT = 6f
const val ROUTE_IMAGE_ASPECT = IMAGE_WIDTH.toFloat() / IMAGE_HEIGHT

/** Text labels are removed: the card names the place itself, and this also avoids fetching glyphs. */
fun withoutSymbolLayers(styleJson: String): String {
    val style = JsonParser.parseString(styleJson).asJsonObject
    val layers = style.getAsJsonArray("layers")
    val kept = com.google.gson.JsonArray()
    layers.forEach { layer -> if ((layer as JsonObject).get("type")?.asString != "symbol") kept.add(layer) }
    style.add("layers", kept)
    return style.toString()
}

/** Bounds with margin around the route; a very short drive still shows some surrounding streets. */
fun routeBounds(points: List<MapPoint>, margin: Double = 0.22, minSpanDegrees: Double = 0.012): DoubleArray {
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
    private val directory = File(this.context.filesDir, "route-maps").apply { mkdirs() }
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private var cleanedStyle: String? = null
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
        mutableReady.update { it - file(id).name.removeSuffix(".jpg") }
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

    private suspend fun render(record: RecordV): Boolean {
        val style = loadStyle() ?: return false
        val snapshot = withTimeoutOrNull(RENDER_TIMEOUT_MS) { snapshot(record, style) } ?: return false
        return runCatching {
            val image = compose(snapshot, record)
            withContext(Dispatchers.IO) {
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

    private suspend fun loadStyle(): String? {
        cleanedStyle?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(STYLE_URL).build()).execute().use { response ->
                    check(response.isSuccessful)
                    withoutSymbolLayers(response.body!!.string())
                }
            }.onFailure { Log.w(TAG, "map style failed: ${it.javaClass.simpleName}") }.getOrNull()
        }?.also { cleanedStyle = it }
    }

    private suspend fun snapshot(record: RecordV, style: String): MapSnapshot? = withContext(Dispatchers.Main) {
        MapLibre.getInstance(context)
        val points = record.route.toMapPoints()
        val pin = record.destinationPoint().takeIf { destinationFitsFrame(points, it) }
        val box = routeBounds(points + listOfNotNull(pin))
        val bounds = LatLngBounds.Builder().include(LatLng(box[0], box[1])).include(LatLng(box[2], box[3])).build()
        val options = MapSnapshotter.Options(IMAGE_WIDTH, IMAGE_HEIGHT).withPixelRatio(1f).withRegion(bounds).withLogo(false).withAttribution(false)
            .withStyleJson(style)
        val snapshotter = MapSnapshotter(context, options)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { snapshotter.cancel() }
            snapshotter.start({ result -> if (continuation.isActive) continuation.resume(result) }, { error ->
                Log.w(TAG, "map snapshot failed: $error")
                if (continuation.isActive) continuation.resume(null)
            })
        }
    }

    private fun compose(snapshot: MapSnapshot, record: RecordV): Bitmap {
        val image = Bitmap.createBitmap(snapshot.bitmap.width, snapshot.bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        // The dark style is made for a full map; at card size its roads need lifting to stay readable.
        val lift = ColorMatrix(floatArrayOf(
            MAP_GAIN, 0f, 0f, 0f, MAP_LIFT,
            0f, MAP_GAIN, 0f, 0f, MAP_LIFT,
            0f, 0f, MAP_GAIN, 0f, MAP_LIFT,
            0f, 0f, 0f, 1f, 0f,
        ))
        canvas.drawBitmap(snapshot.bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(lift) })
        val points = record.route.toMapPoints()
        val project = { point: MapPoint -> snapshot.pixelForLatLng(LatLng(point.latitude, point.longitude)).let { Offset(it.x, it.y) } }
        val lines = splitAtGaps(points.thinnedForDrawing()).map { piece -> piece.map(project) }
        val width = 7f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = android.graphics.Color.WHITE; strokeWidth = width
        }
        val glow = Paint(stroke).apply { alpha = 60; strokeWidth = width * 3.2f }
        val dashed = Paint(stroke).apply { alpha = 170; strokeWidth = width * 0.6f; pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f) }
        lines.zipWithNext().forEach { (before, after) -> canvas.drawLine(before.last().x, before.last().y, after.first().x, after.first().y, dashed) }
        lines.forEach { line ->
            val path = Path()
            smooth(line).forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
            canvas.drawPath(path, glow)
            canvas.drawPath(path, stroke)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val hole = Paint(fill).apply { color = 0xFF111214.toInt() }
        lines.firstOrNull()?.firstOrNull()?.let { start ->
            canvas.drawCircle(start.x, start.y, width * 2.4f, fill)
            canvas.drawCircle(start.x, start.y, width * 1.6f, hole)
            canvas.drawCircle(start.x, start.y, width * 0.9f, fill)
        }
        val pin = record.destinationPoint().takeIf { destinationFitsFrame(points, it) }
        if (pin != null) drawPin(canvas, project(pin), record.placeName, fill, hole)
        else lines.lastOrNull()?.lastOrNull()?.let { end -> canvas.drawCircle(end.x, end.y, width * 1.8f, fill) }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 28f }
        canvas.drawText(ATTRIBUTION, 16f, image.height - 16f, note)
        return image
    }

    private fun drawPin(canvas: Canvas, tip: Offset, name: String, fill: Paint, hole: Paint) {
        val height = 58f
        val radius = height * 0.36f
        val centerY = tip.y - height + radius
        canvas.drawPath(Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - radius * 0.9f, centerY + radius * 0.42f)
            lineTo(tip.x + radius * 0.9f, centerY + radius * 0.42f)
            close()
        }, fill)
        canvas.drawCircle(tip.x, centerY, radius, fill)
        canvas.drawCircle(tip.x, centerY, radius * 0.42f, hole)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
        val textWidth = label.measureText(name)
        val right = tip.x + radius + 12f
        val x = if (right + textWidth <= canvas.width - 8f) right else (tip.x - radius - 12f - textWidth).coerceAtLeast(8f)
        canvas.drawText(name, x, centerY + 10f, label)
    }

    private companion object { const val TAG = "DriveLogMap" }
}

/** The stored map image when there is one, otherwise the map-free art, which never needs a network. */
@Composable
fun RouteVisual(cache: RouteMapCache, record: RecordV?, label: String?, modifier: Modifier = Modifier) {
    val d = LocalCarDimens.current
    val ready by cache.ready.collectAsState()
    val hasImage = record != null && cache.has(record.id)
    val image by produceState<ImageBitmap?>(null, record?.id, hasImage, ready) {
        value = if (record == null || !hasImage) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(cache.file(record.id).path)?.asImageBitmap() }.getOrNull()
        }
    }
    val loaded = image
    if (loaded != null) {
        Box(modifier.clip(RoundedCornerShape(d.u(10))).background(DriveCardBackground)) {
            // The route sits well inside its margin, so filling the frame never cuts it off.
            Image(loaded, "주행 경로 지도", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    } else DriveRouteArt(record?.route?.toMapPoints().orEmpty(), record?.destinationPoint(), label, modifier)
}
