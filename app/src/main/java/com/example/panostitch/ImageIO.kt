package com.example.panostitch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

object ImageIO {

    /** Decodes [uri], applies EXIF rotation and downsizes so the longest edge is <= [maxEdge]. */
    suspend fun load(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@withContext null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@withContext null
        val rotation = cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0

        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
        if (scale == 1f && rotation == 0) return@withContext decoded
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (out !== decoded) decoded.recycle()
        out
    }

    /** 35mm-equivalent focal length from EXIF, or null if the photo has none. */
    suspend fun focal35(ctx: Context, uri: Uri): Double? = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            }
        }.getOrNull()?.takeIf { it > 0 }?.toDouble()
    }

    /** Saves to Pictures/PanoStitch via MediaStore (no storage permission needed on API 29+). */
    suspend fun saveToGallery(ctx: Context, bmp: Bitmap, o: StitchOptions): Uri? =
        withContext(Dispatchers.IO) {
            val name = "Pano_${System.currentTimeMillis()}" + if (o.png) ".png" else ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, if (o.png) "image/png" else "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PanoStitch")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            val ok = resolver.openOutputStream(uri)?.use {
                bmp.compress(
                    if (o.png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    o.jpegQuality, it
                )
            } ?: false
            if (!ok) { resolver.delete(uri, null, null); return@withContext null }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }
}
