package app.proview.pipeline.finish

import app.proview.pipeline.raw.G1
import app.proview.pipeline.raw.G2
import app.proview.pipeline.raw.RawFrame
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.pow

/** Lens shading gain map (Camera2 STATISTICS_LENS_SHADING_CORRECTION_MAP), R/G1/G2/B per grid point. */
class ShadingMap(val columns: Int, val rows: Int, val gains: FloatArray) {
    init {
        require(gains.size == columns * rows * 4)
    }

    /** Bilinear gain for [channel] at normalised position (u, v) in [0, 1]. */
    fun gain(channel: Int, u: Float, v: Float): Float {
        val fx = (u * (columns - 1)).coerceIn(0f, (columns - 1).toFloat())
        val fy = (v * (rows - 1)).coerceIn(0f, (rows - 1).toFloat())
        val x0 = fx.toInt().coerceAtMost(columns - 2).coerceAtLeast(0)
        val y0 = fy.toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        if (columns == 1 || rows == 1) return gains[channel]
        val tx = fx - x0
        val ty = fy - y0
        fun g(x: Int, y: Int) = gains[(y * columns + x) * 4 + channel]
        val top = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * tx
        val bottom = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * tx
        return top + (bottom - top) * ty
    }
}

data class FinishParams(
    /** White-balance gains, R/G1/G2/B (Camera2 COLOR_CORRECTION_GAINS). */
    val wbGains: FloatArray,
    /** White-balanced camera RGB -> linear sRGB (see [ColorCalibration.cameraToSrgb]). */
    val cameraToSrgb: DoubleArray,
    /** Linear gain applied before the tone curve; sets the brightness of the result. */
    val exposureGain: Float,
    val shading: ShadingMap? = null,
    /** Clockwise rotation of the output, degrees (0/90/180/270). */
    val orientation: Int = 0,
    /** Linear level where the highlight shoulder starts; below it tones are untouched. */
    val shoulderStart: Float = 0.55f,
    /** Chroma noise blur radius at half resolution, pixels. 0 disables. */
    val chromaRadius: Int = 3,
    /**
     * When set, tone and colour come from [Rendition] with this style (exposure, local lift,
     * filmic curve, Hasselblad-inspired colour) instead of the plain shoulder curve. Without
     * auto exposure in the style, [exposureGain] is used.
     */
    val style: RenderStyle? = null,
)

/** Final image: ARGB pixels, already rotated. */
class Output(val width: Int, val height: Int, val argb: IntArray)

/**
 * Merged RAW -> finished sRGB (docs/NIGHT_MODE.md, steps 6-10, N2 version).
 *
 * Shading -> white balance -> neutral highlight clip -> demosaic -> calibrated colour ->
 * tone (luminance only, so hue and saturation stay true) -> chroma noise reduction (luma grain is
 * left alone) -> sRGB encode with dither -> rotate.
 */
object Finisher {
    fun finish(frame: RawFrame, p: FinishParams): Output {
        if (p.style != null) return finishRendition(frame, p, p.style)
        val balanced = balance(frame, p)
        val rgb = Demosaic.mhc(balanced)
        colorAndTone(rgb, p)
        if (p.chromaRadius > 0) chromaDenoise(rgb, p.chromaRadius)
        return encode(rgb, p.orientation)
    }

    /**
     * The Rendition path: balance at unit gain (1.0 = sensor clip), demosaic, calibrated matrix,
     * chroma NR in linear light, then [Rendition] decides exposure, tone and colour.
     */
    private fun finishRendition(frame: RawFrame, p: FinishParams, style: RenderStyle): Output {
        val balanced = balance(frame, p.copy(exposureGain = 1f))
        val rgb = Demosaic.mhc(balanced)
        applyMatrix(rgb, p.cameraToSrgb)
        if (p.chromaRadius > 0) chromaDenoise(rgb, p.chromaRadius)
        Rendition.render(rgb.r, rgb.g, rgb.b, rgb.width, rgb.height, style, p.exposureGain)
        return encode(rgb, p.orientation, displayEncoded = true)
    }

    /** White-balanced camera RGB -> linear sRGB, negatives clamped. */
    fun applyMatrix(rgb: Rgb, cameraToSrgb: DoubleArray) {
        val m = FloatArray(9) { cameraToSrgb[it].toFloat() }
        IntStream.range(0, rgb.height).parallel().forEach { y ->
            for (x in 0 until rgb.width) {
                val i = y * rgb.width + x
                val cr = rgb.r[i]
                val cg = rgb.g[i]
                val cb = rgb.b[i]
                rgb.r[i] = (m[0] * cr + m[1] * cg + m[2] * cb).coerceAtLeast(0f)
                rgb.g[i] = (m[3] * cr + m[4] * cg + m[5] * cb).coerceAtLeast(0f)
                rgb.b[i] = (m[6] * cr + m[7] * cg + m[8] * cb).coerceAtLeast(0f)
            }
        }
    }

    /** Lens shading, white balance and exposure gain; clips highlights to neutral. */
    fun balance(frame: RawFrame, p: FinishParams): RawFrame {
        val w = frame.width
        val h = frame.height
        val out = FloatArray(w * h)
        val minWb = p.wbGains.min()
        IntStream.range(0, h).parallel().forEach { y ->
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val ch = frame.cfa.channelAt(x, y)
                val u = (x + 0.5f) / w
                val shade = p.shading?.gain(ch, u, v) ?: 1f
                val shadeG = p.shading?.let { 0.5f * (it.gain(G1, u, v) + it.gain(G2, u, v)) } ?: 1f
                val raw = frame.data[y * w + x].coerceAtMost(1f)
                val value = raw * shade * p.wbGains[ch] * p.exposureGain
                // Where any channel could have clipped, clip all of them, so blown highlights go
                // white instead of magenta.
                val clip = p.exposureGain * shadeG * minWb
                out[y * w + x] = value.coerceAtMost(clip)
            }
        }
        return RawFrame(w, h, out, frame.cfa, frame.noise)
    }

    /** Calibrated colour matrix, then a luminance tone curve with a soft highlight shoulder. */
    fun colorAndTone(rgb: Rgb, p: FinishParams) {
        val m = FloatArray(9) { p.cameraToSrgb[it].toFloat() }
        val s = p.shoulderStart
        // Neutral clip level after balance(): the brightest white the sensor can record.
        val white = p.exposureGain * p.wbGains.min()
        IntStream.range(0, rgb.height).parallel().forEach { y ->
            for (x in 0 until rgb.width) {
                val i = y * rgb.width + x
                val cr = rgb.r[i]
                val cg = rgb.g[i]
                val cb = rgb.b[i]
                var r = (m[0] * cr + m[1] * cg + m[2] * cb).coerceAtLeast(0f)
                var g = (m[3] * cr + m[4] * cg + m[5] * cb).coerceAtLeast(0f)
                var b = (m[6] * cr + m[7] * cg + m[8] * cb).coerceAtLeast(0f)
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (lum > 1e-6f) {
                    val t = tone(lum, s, white)
                    val k = t / lum
                    r *= k; g *= k; b *= k
                    // Out of range: fold towards the toned grey, keeping the hue.
                    val mx = maxOf(r, g, b)
                    if (mx > 1f) {
                        val f = (1f - t) / (mx - t).coerceAtLeast(1e-6f)
                        r = t + (r - t) * f; g = t + (g - t) * f; b = t + (b - t) * f
                    }
                }
                rgb.r[i] = r; rgb.g[i] = g; rgb.b[i] = b
            }
        }
    }

    /**
     * Identity up to [shoulder], then an exponential roll-off that reaches exactly 1 at [white]
     * (the sensor's clip level): tones stay true, lamps roll off smoothly and blown areas are white.
     */
    fun tone(x: Float, shoulder: Float, white: Float): Float {
        if (x <= shoulder) return x
        val span = 1f - shoulder
        if (white <= shoulder + 1e-3f) return minOf(1f, x)
        val norm = 1f - exp(-(white - shoulder) / span)
        return minOf(1f, shoulder + span * (1f - exp(-(x - shoulder) / span)) / norm)
    }

    /**
     * Removes colour noise only: blurs Cb/Cr at half resolution, keeps luma untouched so fine
     * grain stays organic. Works on linear values here; N3 replaces this with multi-scale NR.
     */
    fun chromaDenoise(rgb: Rgb, radius: Int) {
        val w = rgb.width
        val h = rgb.height
        val hw = w / 2
        val hh = h / 2
        val cb = FloatArray(hw * hh)
        val cr = FloatArray(hw * hh)
        IntStream.range(0, hh).parallel().forEach { y ->
            for (x in 0 until hw) {
                var sb = 0f
                var sr = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val i = (2 * y + dy) * w + 2 * x + dx
                    val l = luma(rgb, i)
                    sb += rgb.b[i] - l
                    sr += rgb.r[i] - l
                }
                cb[y * hw + x] = sb / 4f
                cr[y * hw + x] = sr / 4f
            }
        }
        repeat(2) {
            boxBlur(cb, hw, hh, radius)
            boxBlur(cr, hw, hh, radius)
        }
        IntStream.range(0, h).parallel().forEach { y ->
            val fy = ((y - 0.5f) / 2f).coerceIn(0f, (hh - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(hh - 2).coerceAtLeast(0)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x - 0.5f) / 2f).coerceIn(0f, (hw - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(hw - 2).coerceAtLeast(0)
                val tx = fx - x0
                val i = y * w + x
                val l = luma(rgb, i)
                val b = bilinear(cb, hw, x0, y0, tx, ty)
                val r = bilinear(cr, hw, x0, y0, tx, ty)
                val nr = (l + r).coerceAtLeast(0f)
                val nb = (l + b).coerceAtLeast(0f)
                val ng = ((l - 0.2126f * nr - 0.0722f * nb) / 0.7152f).coerceAtLeast(0f)
                rgb.r[i] = nr; rgb.g[i] = ng; rgb.b[i] = nb
            }
        }
    }

    private fun luma(rgb: Rgb, i: Int) = 0.2126f * rgb.r[i] + 0.7152f * rgb.g[i] + 0.0722f * rgb.b[i]

    private fun bilinear(a: FloatArray, w: Int, x0: Int, y0: Int, tx: Float, ty: Float): Float {
        val i = y0 * w + x0
        val top = a[i] + (a[i + 1] - a[i]) * tx
        val bottom = a[i + w] + (a[i + w + 1] - a[i + w]) * tx
        return top + (bottom - top) * ty
    }

    /** Separable box blur in place. */
    fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val n = (2 * r + 1).toFloat()
        IntStream.range(0, h).parallel().forEach { y ->
            val row = FloatArray(w)
            var s = 0f
            for (k in -r..r) s += a[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                row[x] = s / n
                s += a[y * w + (x + r + 1).coerceAtMost(w - 1)] - a[y * w + (x - r).coerceAtLeast(0)]
            }
            System.arraycopy(row, 0, a, y * w, w)
        }
        IntStream.range(0, w).parallel().forEach { x ->
            val col = FloatArray(h)
            var s = 0f
            for (k in -r..r) s += a[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                col[y] = s / n
                s += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) a[y * w + x] = col[y]
        }
    }

    /** sRGB transfer function, 4096-entry lookup over [0, 1]. */
    private val SRGB_LUT = FloatArray(4097) { i ->
        val x = i / 4096.0
        (if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055).toFloat()
    }

    fun srgbEncode(x: Float): Float {
        val f = x.coerceIn(0f, 1f) * 4096f
        val i = f.toInt().coerceAtMost(4095)
        return SRGB_LUT[i] + (SRGB_LUT[i + 1] - SRGB_LUT[i]) * (f - i)
    }

    /** Deterministic triangular dither in [-1, 1] LSB, so smooth gradients never band. */
    fun dither(x: Int, y: Int, c: Int): Float {
        fun hash(n: Int): Float {
            var v = n * 0x27d4eb2d
            v = v xor (v ushr 15)
            v *= 0x165667b1
            v = v xor (v ushr 13)
            return (v ushr 8) / 16777216f
        }
        val seed = x * 73856093 xor y * 19349663 xor c * 83492791
        return hash(seed) + hash(seed + 1) - 1f
    }

    /** Rotates and quantises to 8 bits with dither; [displayEncoded] input is already sRGB-encoded. */
    fun encode(rgb: Rgb, orientation: Int, displayEncoded: Boolean = false): Output {
        val w = rgb.width
        val h = rgb.height
        val rot = ((orientation % 360) + 360) % 360
        val ow = if (rot == 90 || rot == 270) h else w
        val oh = if (rot == 90 || rot == 270) w else h
        val out = IntArray(ow * oh)
        IntStream.range(0, oh).parallel().forEach { oy ->
            for (ox in 0 until ow) {
                val sx = when (rot) {
                    90 -> oy
                    180 -> w - 1 - ox
                    270 -> w - 1 - oy
                    else -> ox
                }
                val sy = when (rot) {
                    90 -> h - 1 - ox
                    180 -> h - 1 - oy
                    270 -> ox
                    else -> oy
                }
                val i = sy * w + sx
                val vr = if (displayEncoded) rgb.r[i] else srgbEncode(rgb.r[i])
                val vg = if (displayEncoded) rgb.g[i] else srgbEncode(rgb.g[i])
                val vb = if (displayEncoded) rgb.b[i] else srgbEncode(rgb.b[i])
                val r = (vr * 255f + 0.5f + dither(ox, oy, 0)).toInt().coerceIn(0, 255)
                val g = (vg * 255f + 0.5f + dither(ox, oy, 1)).toInt().coerceIn(0, 255)
                val b = (vb * 255f + 0.5f + dither(ox, oy, 2)).toInt().coerceIn(0, 255)
                out[oy * ow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Output(ow, oh, out)
    }
}
