package com.drivelog.app.feature.car

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import com.drivelog.app.core.journey.DistanceSource
import com.drivelog.app.core.journey.RecordV
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder
import kotlin.math.roundToLong

private const val SHARE_POINTS = 120
private const val MAX_SHARE_URL = 1500

/** Google's encoded polyline at five decimals: about a metre, and four to six characters a point. */
fun encodePolyline(points: List<MapPoint>): String = buildString {
    var lastLat = 0L
    var lastLon = 0L
    fun put(delta: Long) {
        var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (value >= 0x20) {
            append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        append((value + 63).toInt().toChar())
    }
    points.forEach { point ->
        val lat = (point.latitude * 1e5).roundToLong()
        val lon = (point.longitude * 1e5).roundToLong()
        put(lat - lastLat)
        put(lon - lastLon)
        lastLat = lat
        lastLon = lon
    }
}

/**
 * A link to the drive card web page carrying the card's own data after the #. Browsers never send that part to
 * the server, so the drive goes from the car to the phone that scans it and nowhere else.
 */
fun cardShareUrl(baseUrl: String, record: RecordV): String {
    fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8.name())
    val points = record.route.toMapPoints()
    var limit = SHARE_POINTS
    while (true) {
        val pieces = styledRoute(points, limit).pieces.filter { it.size >= 2 }
        val fields = listOfNotNull(
            "v=1",
            "n=" + encode(record.placeName),
            "s=" + record.startedAtMillis / 1000,
            record.distanceKm?.let { "k=" + String.format(java.util.Locale.US, "%.1f", it) },
            "e=" + record.elapsedSeconds,
            if (record.distanceSource == DistanceSource.VEHICLE) "d=v" else "d=g",
            if (record.simulated) "m=1" else null,
            "p=" + encode(pieces.joinToString(".") { encodePolyline(it) }),
        )
        val url = baseUrl + "#" + fields.joinToString("&")
        if (url.length <= MAX_SHARE_URL || limit <= 20) return url
        limit /= 2
    }
}

/** Black modules on white with the standard quiet zone, which phone cameras need to find the code. */
fun qrImage(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Parked only, like every screen but the recording view. Nothing is sent: the phone reads the code itself. */
@Composable
fun CardQrScreen(record: RecordV, baseUrl: String, onBack: () -> Unit) {
    val d = LocalCarDimens.current
    val url = remember(record.id) { cardShareUrl(baseUrl, record) }
    val qr = remember(url) { qrImage(url, 720) }
    Frame(onBack, simulated = record.simulated) {
        val code = @Composable { modifier: Modifier ->
            Image(
                qr, "드라이브 카드 QR 코드", modifier.aspectRatio(1f).clip(RoundedCornerShape(d.u(12))).background(Color.White).testTag("card-qr"),
                filterQuality = FilterQuality.None,
            )
        }
        val words = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(d.u(10))) {
                Heading("휴대폰으로 카드 가져가기", 36)
                T("휴대폰 카메라로 QR 코드를 비추면 이번 드라이브 카드가 열려요", d.t(20))
                T("${record.placeName} · ${distanceText(record.distanceKm)} km · ${elapsedText(record.elapsedSeconds)}", d.t(20), bold = true, maxLines = 1)
                Spacer(Modifier.height(d.u(6)))
                // The code carries the whole route, the start included, so whoever scans it sees where the drive began.
                T("출발지를 포함한 경로가 QR 코드에 담겨, 찍은 휴대폰으로만 전달돼요. 서버에는 저장되지 않아요", d.t(16), Modifier.testTag("qr-privacy"), color = muted(), maxLines = 2)
                Spacer(Modifier.height(d.u(10)))
                SecondaryButton("돌아가기", "qr-back", Modifier.width(d.u(280)), size = ButtonSize.MEDIUM, onClick = onBack)
            }
        }
        if (d.stacked) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.u(20))) {
            code(Modifier.align(Alignment.CenterHorizontally).width(d.u(360)))
            words()
        } else Row(Modifier.fillMaxSize().padding(bottom = d.u(10)), horizontalArrangement = Arrangement.spacedBy(d.u(40)), verticalAlignment = Alignment.CenterVertically) {
            code(Modifier.fillMaxHeight(0.9f))
            Column(Modifier.weight(1f)) { words() }
        }
    }
}
