package app.proview.camera.develop

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pixels of a decoded image, upright, as ARGB. */
class Pixels(val width: Int, val height: Int, val argb: IntArray)

object PhotoIo {
    const val ALBUM_PATH = "Pictures/Proview"

    /**
     * Decodes [uri] upright (EXIF orientation applied), scaled down so the long edge is at most
     * [maxDim] pixels.
     */
    fun load(context: Context, uri: Uri, maxDim: Int): Pixels {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                if (long > maxDim) {
                    val s = maxDim.toFloat() / long
                    decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                }
            }
        } else {
            context.contentResolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input) ?: throw IllegalStateException("Couldn't decode $uri")
            }
        }
        val soft = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        val px = IntArray(soft.width * soft.height)
        soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
        val out = Pixels(soft.width, soft.height, px)
        soft.recycle()
        return out
    }

    /** Saves JPEG bytes to Pictures/Proview and returns the MediaStore URI. */
    @SuppressLint("InlinedApi")
    fun saveJpeg(context: Context, bytes: ByteArray, suffix: String = ""): Uri {
        val name = "PRV_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + suffix
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM_PATH)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't open $uri")
        return uri
    }
}
