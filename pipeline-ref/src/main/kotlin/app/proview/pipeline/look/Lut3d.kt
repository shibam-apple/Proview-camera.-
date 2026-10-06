package app.proview.pipeline.look

/**
 * A 3D colour lookup table baked from a [Look]: size^3 RGB entries, red fastest, then green,
 * then blue. The same table is uploaded to the viewfinder shader (as a 2D strip of blue slices)
 * and applied to photos, so the preview and the photo match.
 */
class Lut3d(val size: Int, val data: FloatArray) {
    init {
        require(data.size == size * size * size * 3)
    }

    /** Trilinear lookup of display sRGB (0..1) into [out]. */
    fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val fr = r.coerceIn(0f, 1f) * n
        val fg = g.coerceIn(0f, 1f) * n
        val fb = b.coerceIn(0f, 1f) * n
        val r0 = fr.toInt().coerceAtMost(n - 1)
        val g0 = fg.toInt().coerceAtMost(n - 1)
        val b0 = fb.toInt().coerceAtMost(n - 1)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        for (c in 0..2) {
            fun v(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
            val c00 = v(r0, g0, b0) + (v(r0 + 1, g0, b0) - v(r0, g0, b0)) * tr
            val c10 = v(r0, g0 + 1, b0) + (v(r0 + 1, g0 + 1, b0) - v(r0, g0 + 1, b0)) * tr
            val c01 = v(r0, g0, b0 + 1) + (v(r0 + 1, g0, b0 + 1) - v(r0, g0, b0 + 1)) * tr
            val c11 = v(r0, g0 + 1, b0 + 1) + (v(r0 + 1, g0 + 1, b0 + 1) - v(r0, g0 + 1, b0 + 1)) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[c] = c0 + (c1 - c0) * tb
        }
    }

    /**
     * RGBA8 bytes laid out as a 2D texture of width size*size and height size: x = r + b*size,
     * y = g. Ready for glTexImage2D.
     */
    fun toTextureRgba(): ByteArray {
        val out = ByteArray(size * size * size * 4)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val src = ((b * size + g) * size + r) * 3
            val x = r + b * size
            val dst = (g * size * size + x) * 4
            out[dst] = (data[src] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            out[dst + 1] = (data[src + 1] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            out[dst + 2] = (data[src + 2] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            out[dst + 3] = 0xFF.toByte()
        }
        return out
    }

    companion object {
        const val SIZE = 33

        fun bake(look: Look, size: Int = SIZE): Lut3d {
            val data = FloatArray(size * size * size * 3)
            val n = (size - 1).toFloat()
            java.util.stream.IntStream.range(0, size).parallel().forEach { b ->
                val tmp = FloatArray(3)
                for (g in 0 until size) for (r in 0 until size) {
                    look.map(r / n, g / n, b / n, tmp)
                    val i = ((b * size + g) * size + r) * 3
                    data[i] = tmp[0]; data[i + 1] = tmp[1]; data[i + 2] = tmp[2]
                }
            }
            return Lut3d(size, data)
        }
    }
}
