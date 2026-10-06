package app.proview.pipeline.enhance

import app.proview.pipeline.finish.Finisher
import app.proview.pipeline.look.FilmRender
import app.proview.pipeline.look.Look
import app.proview.pipeline.look.Lut3d
import app.proview.pipeline.look.Srgb
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** User controls in the Lab. Strengths are 0..1. */
data class EnhanceParams(
    val denoise: Float = 0.6f,
    val tone: Float = 0.5f,
    val detail: Float = 0.4f,
    val look: Look = Look.DEFAULT,
)

/** What the enhancement did, measured on the image itself. */
data class EnhanceMetrics(
    val noiseBefore: Float,
    val noiseAfter: Float,
    /** Fraction of pixels in deep shadow (luma < 0.08). */
    val shadowsBefore: Float,
    val shadowsAfter: Float,
    /** Fraction of pixels clipped (any channel >= 254). */
    val clippedBefore: Float,
    val clippedAfter: Float,
    /** Mean absolute local contrast (luma minus its local mean). */
    val localContrastBefore: Float,
    val localContrastAfter: Float,
    val millis: Long,
) {
    val noiseReduction: Float get() = if (noiseBefore > 0f) 1f - noiseAfter / noiseBefore else 0f
}

/**
 * Enhancement for a single finished photo (no burst available), e.g. any image from the gallery.
 * See docs/ALGORITHM.md, "Single-image path":
 *
 * 1. Noise estimate (Immerkær): sigma of the luma from a Laplacian-like operator.
 * 2. Luma denoise: self-guided filter with eps tied to sigma, so flat areas smooth and edges stay.
 * 3. Chroma denoise: guided by the clean luma, wider, so colour blotches go and colour edges stay.
 * 4. Local tone: base/detail split of log luminance with a guided filter; the base's contrast is
 *    compressed (shadows lift, highlights come down) while the detail layer is kept, then the
 *    gain is applied to RGB as a ratio so colours stay true.
 * 5. Detail: unsharp mask on luma with a noise-aware threshold (only above ~2 sigma).
 * 6. Look: shared 3D LUT, halation and grain (FilmRender).
 */
object SingleImageEnhancer {
    fun enhance(argb: IntArray, width: Int, height: Int, p: EnhanceParams, lut: Lut3d = Lut3d.bake(p.look)): Pair<IntArray, EnhanceMetrics> {
        val start = System.currentTimeMillis()
        val n = width * height
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        unpack(argb, r, g, b)

        val y = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        toYcc(r, g, b, y, cb, cr)

        val sigma = estimateNoise(y, width, height)
        val shadowsBefore = fractionBelow(y, 0.08f)
        val clippedBefore = clippedFraction(argb)
        val contrastBefore = localContrast(y, width, height)

        // 2. Luma denoise: guided filter on itself; eps ~ (2.5 sigma)^2 separates noise from edges.
        val yd = if (p.denoise > 0f && sigma > 1e-4f) {
            val eps = (2.5f * sigma).let { it * it } * (0.5f + 1.5f * p.denoise)
            val smooth = guidedFilter(y, y, width, height, radius = 2, eps = eps)
            FloatArray(n) { i -> y[i] + (smooth[i] - y[i]) * p.denoise.coerceIn(0f, 1f) }
        } else {
            y
        }

        // 3. Chroma denoise: guided by clean luma, wider radius, stronger.
        if (p.denoise > 0f) {
            val ceps = (4f * sigma).let { it * it } + 1e-5f
            val cbd = guidedFilter(yd, cb, width, height, radius = 6, eps = ceps)
            val crd = guidedFilter(yd, cr, width, height, radius = 6, eps = ceps)
            val k = p.denoise.coerceIn(0f, 1f)
            for (i in 0 until n) {
                cb[i] += (cbd[i] - cb[i]) * k
                cr[i] += (crd[i] - cr[i]) * k
            }
        }
        fromYcc(yd, cb, cr, r, g, b)

        // 4. Local tone mapping on log luminance.
        if (p.tone > 0f) localTone(r, g, b, width, height, p.tone)

        // 5. Noise-aware detail enhancement on luma.
        if (p.detail > 0f) sharpen(r, g, b, width, height, p.detail, sigma)

        val out = pack(r, g, b, width, height)
        val yAfter = FloatArray(n).also { yy -> toYcc(r, g, b, yy, FloatArray(n), FloatArray(n)) }
        val metrics = EnhanceMetrics(
            noiseBefore = sigma,
            noiseAfter = estimateNoise(yAfter, width, height),
            shadowsBefore = shadowsBefore,
            shadowsAfter = fractionBelow(yAfter, 0.08f),
            clippedBefore = clippedBefore,
            clippedAfter = clippedFraction(out),
            localContrastBefore = contrastBefore,
            localContrastAfter = localContrast(yAfter, width, height),
            millis = 0,
        )
        // 6. Look last: colour, halation, grain (grain is excluded from the noise metric above).
        FilmRender.apply(out, width, height, p.look, lut)
        return out to metrics.copy(millis = System.currentTimeMillis() - start)
    }

    /**
     * He, Sun and Tang, "Guided Image Filtering" (2010). Edge-preserving smoothing of [p] guided by
     * [guide]: within each window the output is a linear function of the guide, so edges in the
     * guide survive while variations below ~sqrt(eps) are smoothed. O(N) with box filters.
     */
    fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = guide.copyOf().also { Finisher.boxBlur(it, w, h, radius) }
        val meanP = p.copyOf().also { Finisher.boxBlur(it, w, h, radius) }
        val corrIP = FloatArray(n) { guide[it] * p[it] }.also { Finisher.boxBlur(it, w, h, radius) }
        val varI = FloatArray(n) { guide[it] * guide[it] }.also { Finisher.boxBlur(it, w, h, radius) }
        val a = FloatArray(n)
        val bb = FloatArray(n)
        for (i in 0 until n) {
            val v = varI[i] - meanI[i] * meanI[i]
            val cov = corrIP[i] - meanI[i] * meanP[i]
            a[i] = cov / (v + eps)
            bb[i] = meanP[i] - a[i] * meanI[i]
        }
        Finisher.boxBlur(a, w, h, radius)
        Finisher.boxBlur(bb, w, h, radius)
        return FloatArray(n) { a[it] * guide[it] + bb[it] }
    }

    /**
     * Immerkær, "Fast Noise Variance Estimation" (1996): sigma of white noise from the response to
     * a mask that cancels smooth structure, with strong edges excluded.
     */
    fun estimateNoise(y: FloatArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f
        var sum = 0.0
        var count = 0L
        val step = if (w * h > 4_000_000) 2 else 1
        for (yy in 1 until h - 1 step step) {
            for (x in 1 until w - 1 step step) {
                val i = yy * w + x
                val gx = abs(y[i + 1] - y[i - 1])
                val gy = abs(y[i + w] - y[i - w])
                if (gx + gy > 0.12f) continue // skip edges and texture
                val v = 4 * y[i] - 2 * (y[i - 1] + y[i + 1] + y[i - w] + y[i + w]) +
                    (y[i - w - 1] + y[i - w + 1] + y[i + w - 1] + y[i + w + 1])
                sum += abs(v)
                count++
            }
        }
        if (count == 0L) return 0f
        return (sqrt(Math.PI / 2.0) / 6.0 * sum / count).toFloat()
    }

    /** Shadows up, highlights down, local detail kept: base/detail split of log luminance. */
    private fun localTone(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, strength: Float) {
        val n = w * h
        val lin = FloatArray(n)
        val logL = FloatArray(n)
        for (i in 0 until n) {
            val l = 0.2126f * Srgb.toLinear(r[i]) + 0.7152f * Srgb.toLinear(g[i]) + 0.0722f * Srgb.toLinear(b[i])
            lin[i] = l
            logL[i] = ln(l + 1e-3f)
        }
        val radius = maxOf(4, (minOf(w, h) * 0.02f).toInt())
        // Base layer: edge-aware, so halos don't form at strong edges.
        val base = guidedFilter(logL, logL, w, h, radius, eps = 0.15f)
        var mean = 0.0
        for (v in base) mean += v
        val key = (mean / n).toFloat()
        val target = ln(0.16f)
        val compress = 0.45f * strength
        IntStream.range(0, h).parallel().forEach { yy ->
            for (x in 0 until w) {
                val i = yy * w + x
                // Compress the base around the image key, nudging the key toward mid-grey.
                val newBase = key + (base[i] - key) * (1f - compress) + (target - key) * 0.35f * strength
                val gain = exp(newBase - base[i]).coerceIn(0.4f, 6f)
                val lr = Srgb.toLinear(r[i]) * gain
                val lg = Srgb.toLinear(g[i]) * gain
                val lb = Srgb.toLinear(b[i]) * gain
                // Soft shoulder so lifted highlights never clip hard.
                val mx = maxOf(lr, lg, lb)
                val k = if (mx > 0.8f) Finisher.tone(mx, 0.8f, 3f) / mx else 1f
                r[i] = Srgb.fromLinear(lr * k)
                g[i] = Srgb.fromLinear(lg * k)
                b[i] = Srgb.fromLinear(lb * k)
            }
        }
    }

    /** Unsharp mask on luma; only differences above ~2 sigma are boosted, so noise isn't sharpened. */
    private fun sharpen(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, amount: Float, sigma: Float) {
        val n = w * h
        val y = FloatArray(n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
        val blur = y.copyOf()
        repeat(2) { Finisher.boxBlur(blur, w, h, 1) }
        val threshold = 2f * sigma
        val k = 1.2f * amount
        for (i in 0 until n) {
            val d = y[i] - blur[i]
            val ad = abs(d)
            val cored = if (ad <= threshold) 0f else (ad - threshold) * (if (d > 0) 1f else -1f)
            val add = cored * k
            r[i] = (r[i] + add).coerceIn(0f, 1f)
            g[i] = (g[i] + add).coerceIn(0f, 1f)
            b[i] = (b[i] + add).coerceIn(0f, 1f)
        }
    }

    fun localContrast(y: FloatArray, w: Int, h: Int): Float {
        val m = y.copyOf()
        Finisher.boxBlur(m, w, h, 4)
        var s = 0.0
        for (i in y.indices) s += abs(y[i] - m[i])
        return (s / y.size).toFloat()
    }

    private fun fractionBelow(y: FloatArray, t: Float): Float = y.count { it < t }.toFloat() / y.size

    private fun clippedFraction(argb: IntArray): Float = argb.count {
        ((it shr 16) and 0xFF) >= 254 || ((it shr 8) and 0xFF) >= 254 || (it and 0xFF) >= 254
    }.toFloat() / argb.size

    fun unpack(argb: IntArray, r: FloatArray, g: FloatArray, b: FloatArray) {
        for (i in argb.indices) {
            val c = argb[i]
            r[i] = ((c shr 16) and 0xFF) / 255f
            g[i] = ((c shr 8) and 0xFF) / 255f
            b[i] = (c and 0xFF) / 255f
        }
    }

    private fun pack(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        IntStream.range(0, h).parallel().forEach { yy ->
            for (x in 0 until w) {
                val i = yy * w + x
                val rr = (r[i] * 255f + 0.5f + Finisher.dither(x, yy, 0) * 0.5f).toInt().coerceIn(0, 255)
                val gg = (g[i] * 255f + 0.5f + Finisher.dither(x, yy, 1) * 0.5f).toInt().coerceIn(0, 255)
                val bb = (b[i] * 255f + 0.5f + Finisher.dither(x, yy, 2) * 0.5f).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return out
    }

    /** BT.601 YCbCr on display values (what JPEG uses). */
    fun toYcc(r: FloatArray, g: FloatArray, b: FloatArray, y: FloatArray, cb: FloatArray, cr: FloatArray) {
        for (i in r.indices) {
            val yy = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
            y[i] = yy
            cb[i] = (b[i] - yy) * 0.564f
            cr[i] = (r[i] - yy) * 0.713f
        }
    }

    fun fromYcc(y: FloatArray, cb: FloatArray, cr: FloatArray, r: FloatArray, g: FloatArray, b: FloatArray) {
        for (i in y.indices) {
            val rr = y[i] + 1.402f * cr[i]
            val bb = y[i] + 1.772f * cb[i]
            val gg = (y[i] - 0.299f * rr - 0.114f * bb) / 0.587f
            r[i] = rr.coerceIn(0f, 1f)
            g[i] = gg.coerceIn(0f, 1f)
            b[i] = bb.coerceIn(0f, 1f)
        }
    }
}
