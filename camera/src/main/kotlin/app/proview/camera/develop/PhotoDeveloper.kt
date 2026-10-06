package app.proview.camera.develop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import app.proview.pipeline.look.FilmRender
import app.proview.pipeline.look.Look
import java.io.ByteArrayOutputStream

/**
 * Develops a normal (day) photo: the camera's full-resolution JPEG, rotated upright, through the
 * selected look (shared LUT + halation + grain) and re-encoded. Night photos get the same look
 * step at the end of NightProcessor.
 */
object PhotoDeveloper {
    fun develop(jpeg: ByteArray, rotationDegrees: Int, look: Look): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }) ?: throw IllegalStateException("Couldn't decode the camera JPEG")
        val upright = if (rotationDegrees % 360 != 0) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
                .also { decoded.recycle() }
        } else {
            decoded
        }
        return developBitmap(upright, look)
    }

    /** Applies [look] to [bitmap] (consumed) and returns JPEG bytes. */
    fun developBitmap(bitmap: Bitmap, look: Look): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        FilmRender.apply(px, w, h, look)
        return encode(px, w, h)
    }

    fun encode(argb: IntArray, w: Int, h: Int): ByteArray {
        val out = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream(8 shl 20).use { bos ->
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            out.recycle()
            bos.toByteArray()
        }
    }
}
