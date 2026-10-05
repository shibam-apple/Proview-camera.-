package app.proview.camera.capture

import java.nio.ByteBuffer

object Histogram {
    /**
     * Normalised (max = 1) luminance histogram of an 8-bit Y plane, sampling every 4th pixel
     * in each direction: plenty for a 16-bar display and cheap enough for every preview frame.
     */
    fun luma(y: ByteBuffer, width: Int, height: Int, rowStride: Int, bins: Int = ProCamera.HISTOGRAM_BINS): FloatArray {
        val counts = IntArray(bins)
        var row = 0
        while (row < height) {
            val base = row * rowStride
            var col = 0
            while (col < width && base + col < y.limit()) {
                val v = y.get(base + col).toInt() and 0xFF
                counts[v * bins / 256]++
                col += 4
            }
            row += 4
        }
        val max = counts.maxOrNull()?.takeIf { it > 0 } ?: return FloatArray(bins)
        return FloatArray(bins) { counts[it].toFloat() / max }
    }

    /** 40 x 30 luma thumbnail (0..255) by point-sampling the Y plane, for scene-motion checks. */
    fun thumbnail(y: ByteBuffer, width: Int, height: Int, rowStride: Int): FloatArray {
        val w = app.proview.camera.night.SceneMotion.THUMB_W
        val h = app.proview.camera.night.SceneMotion.THUMB_H
        val out = FloatArray(w * h)
        for (ty in 0 until h) {
            val row = (ty * height + height / 2) / h
            for (tx in 0 until w) {
                val col = (tx * width + width / 2) / w
                val i = row * rowStride + col
                out[ty * w + tx] = if (i < y.limit()) (y.get(i).toInt() and 0xFF).toFloat() else 0f
            }
        }
        return out
    }
}
