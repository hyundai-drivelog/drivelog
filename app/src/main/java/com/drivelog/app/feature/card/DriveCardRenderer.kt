package com.drivelog.app.feature.card

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.drivelog.app.R
import com.drivelog.app.data.drive.DriveSession
import com.drivelog.app.feature.drive.distanceLabel
import com.drivelog.app.feature.drive.routeShape
import java.io.File
import java.util.UUID

/** The same bitmap is used for preview and PNG output. No screenshot-based export. */
object DriveCardRenderer {
    private fun loadPhoto(context: Context, uri: String?): Bitmap {
        if (uri == null) return BitmapFactory.decodeResource(context.resources, R.drawable.demo_coastal_road)
        val source = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
        }
        return context.contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("이미지를 읽을 수 없습니다.")
    }

    fun render(context: Context, session: DriveSession, template: Int, uri: String?): Bitmap {
        val photo = loadPhoto(context, uri)
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val photoRect = if (template == 0) RectF(40f, 180f, 1160f, 1040f) else RectF(0f, 0f, 1200f, 1000f)
        val scale = maxOf(photoRect.width() / photo.width, photoRect.height() / photo.height)
        val cropWidth = photoRect.width() / scale
        val cropHeight = photoRect.height() / scale
        val crop = Rect(((photo.width - cropWidth) / 2).toInt(), ((photo.height - cropHeight) / 2).toInt(),
            ((photo.width + cropWidth) / 2).toInt(), ((photo.height + cropHeight) / 2).toInt())
        canvas.drawBitmap(photo, crop, photoRect, paint)
        photo.recycle()
        fun text(value: String, x: Float, y: Float, size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
            paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size
            paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(value, x, y, paint)
        }
        if (template == 0) {
            text("DRIVELOG", 50f, 90f, 46f, true)
            text("A JOURNEY TO REMEMBER", 50f, 140f, 24f)
        }
        text(session.startedAt.take(10), 55f, 1110f, 28f)
        text(distanceLabel(session.distanceKm), 55f, 1220f, 88f, true)
        text("${session.durationSeconds / 60}m ${session.durationSeconds % 60}s  /  ${session.mode}", 60f, 1290f, 32f)
        text(session.destination.name.take(30), 60f, 1350f, 30f)
        val shape = routeShape(session.points, 340f, 250f)
        if (shape.isNotEmpty()) {
            val path = Path().apply {
                moveTo(shape.first().first + 790f, shape.first().second + 1070f)
                shape.drop(1).forEach { lineTo(it.first + 790f, it.second + 1070f) }
            }
            paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 9f; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(path, paint)
        } else text("NO ROUTE", 860f, 1190f, 24f)
        text(if (session.mode.name == "MOCK") "MOCK DATA / DEMO JOURNEY" else "PLEOS GPS / APP SESSION", 60f, 1470f, 27f, true)
        text(if (uri == null) "AI-GENERATED DEMO PHOTO" else "LOCAL PHOTO", 60f, 1520f, 22f, color = Color.DKGRAY)
        return bitmap
    }

    fun save(context: Context, bitmap: Bitmap): String {
        val name = "DRIVELOG_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
        val directory = File(context.filesDir, "cards").apply { mkdirs() }
        val file = File(directory, name)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DRIVELOG")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val image = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("갤러리 저장 위치를 만들 수 없습니다. 내부 PNG: ${file.name}")
            context.contentResolver.openOutputStream(image).use { output -> checkNotNull(output); file.inputStream().use { it.copyTo(output) } }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(image, values, null, null)
        }
        return file.absolutePath
    }
}
