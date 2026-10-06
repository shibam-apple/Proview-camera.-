package app.proview.pipeline.look

import app.proview.pipeline.finish.Finisher
import java.util.stream.IntStream

/**
 * Applies a [Look] to a finished photo (ARGB, display sRGB): colour through the shared LUT, then
 * film texture:
 *
 * - **Halation**: on film, bright light scatters back through the base and reddens the halo
 *   around highlights. Here: highlights above a threshold are blurred wide at quarter resolution
 *   and added back, mostly in red and orange. This is the soft "lens blur" glow around lamps.
 * - **Grain**: fine luminance-only grain, strongest in the midtones, in clumps ~1.6 px across,
 *   like film rather than per-pixel digital noise.
 */
object FilmRender {
    fun apply(argb: IntArray, width: Int, height: Int, look: Look, lut: Lut3d = Lut3d.bake(look), seed: Int = 1) {
        val glow = if (look.halation > 0f) halationMap(argb, width, height) else null
        val gw = (width + 3) / 4
        val gh = (height + 3) / 4
        IntStream.range(0, height).parallel().forEach { y ->
            val px = FloatArray(3)
            for (x in 0 until width) {
                val i = y * width + x
                val c = argb[i]
                var r = ((c shr 16) and 0xFF) / 255f
                var g = ((c shr 8) and 0xFF) / 255f
                var b = (c and 0xFF) / 255f
                lut.apply(r, g, b, px)
                r = px[0]; g = px[1]; b = px[2]

                if (glow != null) {
                    val h = sampleQuarter(glow, gw, gh, x, y) * look.halation
                    // Screen blend, red-orange tint: halation lives in the red layer of film.
                    r = 1f - (1f - r) * (1f - h)
                    g = 1f - (1f - g) * (1f - h * 0.42f)
                    b = 1f - (1f - b) * (1f - h * 0.12f)
                }
                if (look.grain > 0f) {
                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val amp = look.grain * 4f * lum * (1f - lum) + look.grain * 0.15f
                    val n = grainNoise(x, y, seed) * amp
                    r += n; g += n; b += n
                }
                argb[i] = (0xFF shl 24) or
                    (((r * 255f + 0.5f + Finisher.dither(x, y, 0) * 0.5f).toInt().coerceIn(0, 255)) shl 16) or
                    (((g * 255f + 0.5f + Finisher.dither(x, y, 1) * 0.5f).toInt().coerceIn(0, 255)) shl 8) or
                    ((b * 255f + 0.5f + Finisher.dither(x, y, 2) * 0.5f).toInt().coerceIn(0, 255))
            }
        }
    }

    /** Quarter-resolution, blurred map of how far each area is above the highlight threshold. */
    fun halationMap(argb: IntArray, width: Int, height: Int, threshold: Float = 0.78f): FloatArray {
        val gw = (width + 3) / 4
        val gh = (height + 3) / 4
        val map = FloatArray(gw * gh)
        IntStream.range(0, gh).parallel().forEach { gy ->
            for (gx in 0 until gw) {
                var s = 0f
                var n = 0
                for (dy in 0..3) for (dx in 0..3) {
                    val x = gx * 4 + dx
                    val y = gy * 4 + dy
                    if (x >= width || y >= height) continue
                    val c = argb[y * width + x]
                    val l = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
                    s += ((l - threshold) / (1f - threshold)).coerceIn(0f, 1f)
                    n++
                }
                map[gy * gw + gx] = if (n > 0) s / n else 0f
            }
        }
        // Radius scales with the image: ~0.6% of the width at quarter resolution, three passes ~ Gaussian.
        val radius = maxOf(2, (gw * 0.012f).toInt())
        repeat(3) { Finisher.boxBlur(map, gw, gh, radius) }
        // Saturating response: a small lamp still gets a visible halo, a blown sky doesn't over-glow.
        for (i in map.indices) map[i] = 1f - kotlin.math.exp(-4f * map[i])
        return map
    }

    private fun sampleQuarter(map: FloatArray, gw: Int, gh: Int, x: Int, y: Int): Float {
        val fx = ((x - 1.5f) / 4f).coerceIn(0f, (gw - 1).toFloat())
        val fy = ((y - 1.5f) / 4f).coerceIn(0f, (gh - 1).toFloat())
        val x0 = fx.toInt().coerceAtMost(maxOf(0, gw - 2))
        val y0 = fy.toInt().coerceAtMost(maxOf(0, gh - 2))
        if (gw < 2 || gh < 2) return map[0]
        val tx = fx - x0
        val ty = fy - y0
        val i = y0 * gw + x0
        val top = map[i] + (map[i + 1] - map[i]) * tx
        val bottom = map[i + gw] + (map[i + gw + 1] - map[i + gw]) * tx
        return top + (bottom - top) * ty
    }

    /** Zero-mean grain in about [-1, 1]: smooth noise on a 1.6 px lattice, so it clumps like film. */
    fun grainNoise(x: Int, y: Int, seed: Int): Float {
        val fx = x / 1.6f
        val fy = y / 1.6f
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun h(ix: Int, iy: Int): Float = Finisher.dither(ix, iy, seed)
        val top = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * tx
        val bottom = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * tx
        return (top + (bottom - top) * ty) * 1.6f
    }
}
