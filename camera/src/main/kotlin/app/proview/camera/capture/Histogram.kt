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

    /**
     * Average scene colour (opaque ARGB) from a YUV_420_888 frame, sampling every 8th chroma
     * sample. Used to tint the UI's glow with what the camera is looking at.
     */
    fun averageColor(image: androidx.camera.core.ImageProxy): Int {
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val cw = image.width / 2
        val ch = image.height / 2
        var sy = 0L
        var su = 0L
        var sv = 0L
        var n = 0
        var y = 0
        while (y < ch) {
            var x = 0
            while (x < cw) {
                val yi = (2 * y) * yP.rowStride + 2 * x
                val ui = y * uP.rowStride + x * uP.pixelStride
                val vi = y * vP.rowStride + x * vP.pixelStride
                if (yi < yP.buffer.limit() && ui < uP.buffer.limit() && vi < vP.buffer.limit()) {
                    sy += yP.buffer.get(yi).toInt() and 0xFF
                    su += uP.buffer.get(ui).toInt() and 0xFF
                    sv += vP.buffer.get(vi).toInt() and 0xFF
                    n++
                }
                x += 8
            }
            y += 8
        }
        if (n == 0) return 0xFF808080.toInt()
        val yy = sy.toFloat() / n
        val u = su.toFloat() / n - 128f
        val v = sv.toFloat() / n - 128f
        val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
        val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
        val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
