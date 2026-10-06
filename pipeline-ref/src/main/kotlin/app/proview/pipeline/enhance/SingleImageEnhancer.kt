package app.proview.pipeline.enhance

import app.proview.pipeline.finish.Finisher
import app.proview.pipeline.look.FilmRender
import app.proview.pipeline.look.Look
import app.proview.pipeline.look.Lut3d
import app.proview.pipeline.look.Oklab
import app.proview.pipeline.look.Srgb
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** User controls in the Lab. Strengths are 0..1. */
data class EnhanceParams(
    val denoise: Float = 0.5f,
    val tone: Float = 0.5f,
    val detail: Float = 0.5f,
    /** Natural by default, so the Lab shows what the enhancement itself does; a look is optional. */
    val look: Look = Look.NATURAL,
)

/** What the enhancement did, measured on the image itself (before the look's grain). */
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
 * Every stage measures the photo first and only does what that photo needs, so a good photo is
 * barely touched. See docs/ALGORITHM.md, "Lab":
 *
 * 1. Measure: noise sigma (Immerkær), luminance percentiles, haze (black-point lift).
 * 2. Denoise: luma by a self-guided filter (eps tied to sigma), chroma guided by the clean luma.
 * 3. Tone (TONE): remove haze (black point), lift dark regions locally (edge-aware base layer,
 *    never darkens), a mid-anchored contrast curve for punch, and vibrance that protects skin and
 *    already-saturated colours.
 * 4. Detail (DETAIL): edge-aware clarity (mid-frequency local contrast) plus fine sharpening, both
 *    cored at the noise level so grain and JPEG blocks are not amplified.
 * 5. Look: shared 3D LUT, halation and grain (FilmRender).
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
        // Noise in edited, re-compressed or upscaled photos is correlated over a few pixels and
        // barely shows at full resolution, so also measure at half resolution (where white noise
        // halves) and take the larger. A small floor lets DENOISE act on JPEG mottling.
        val coarse = estimateNoise(half(y, width, height), width / 2, height / 2)
        val correlated = coarse > sigma * 0.8f
        val sigmaEff = maxOf(sigma, coarse, 0.004f)
        val scale = minOf(width, height) / 1000f

        // 2a. Luma denoise: guided filter on itself; eps ~ (2.5 sigma)^2 separates noise from edges.
        val d = p.denoise.coerceIn(0f, 1f)
        val yd = if (d > 0f) {
            val eps = (2.5f * sigmaEff).let { it * it } * (0.5f + 1.5f * d)
            val radius = if (sigmaEff > 0.02f || correlated) 3 else 2
            var smooth = guidedFilter(y, y, width, height, radius, eps)
            // Heavy noise leaves coarse blotches after the fine pass: a second, wider pass at a
            // lower threshold evens them out.
            if (sigmaEff > 0.015f) smooth = guidedFilter(smooth, smooth, width, height, radius * 2 + 1, (1.2f * sigmaEff).let { it * it } * d)
            val k = sqrt(d)
            FloatArray(n) { i -> y[i] + (smooth[i] - y[i]) * k }
        } else {
            y
        }

        // 2b. Chroma denoise: guided by clean luma, wider radius, stronger.
        if (d > 0f) {
            val ceps = (4f * sigmaEff).let { it * it } + 1e-5f
            val radius = maxOf(4, (6 * scale).toInt())
            val cbd = guidedFilter(yd, cb, width, height, radius, ceps)
            val crd = guidedFilter(yd, cr, width, height, radius, ceps)
            for (i in 0 until n) {
                cb[i] += (cbd[i] - cb[i]) * d
                cr[i] += (crd[i] - cr[i]) * d
            }
        }
        fromYcc(yd, cb, cr, r, g, b)

        // 3. Tone and colour.
        if (p.tone > 0f) {
            val t = p.tone.coerceIn(0f, 1f)
            dehaze(r, g, b, t)
            shadowLift(r, g, b, width, height, t)
            contrastCurve(r, g, b, 0.4f * t)
            vibrance(r, g, b, 0.5f * t)
        }

        // 4. Clarity and sharpening, cored at the noise floor.
        if (p.detail > 0f) detail(r, g, b, width, height, p.detail.coerceIn(0f, 1f), sigmaEff, scale)

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
        // 5. Look last: colour, halation, grain (grain is excluded from the metrics above).
        FilmRender.apply(out, width, height, p.look, lut)
        return out to metrics.copy(millis = System.currentTimeMillis() - start)
    }

    /**
     * Haze lifts the black point: the darkest pixels are grey instead of black. Taking the lift
     * off every channel equally (the dark-channel model with a constant transmission) restores
     * contrast and colour; a clean photo has a near-zero black point and is left alone.
     */
    private fun dehaze(r: FloatArray, g: FloatArray, b: FloatArray, t: Float) {
        val n = r.size
        val minC = FloatArray(n) { minOf(r[it], g[it], b[it]) }
        val black = percentile(minC, 0.005f)
        val remove = ((black - 0.015f).coerceIn(0f, 0.2f) * 1.2f * t).coerceAtMost(black)
        if (remove <= 0.002f) return
        val k = 1f / (1f - remove)
        for (i in 0 until n) {
            r[i] = ((r[i] - remove) * k).coerceIn(0f, 1f)
            g[i] = ((g[i] - remove) * k).coerceIn(0f, 1f)
            b[i] = ((b[i] - remove) * k).coerceIn(0f, 1f)
        }
    }

    /**
     * Lifts dark regions toward a readable level using an edge-aware base layer of log luminance,
     * so a dark subject in front of a bright sky opens up without halos. Gains are >= 1: this step
     * never darkens anything, so bright skies keep their colour instead of turning grey.
     */
    private fun shadowLift(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, t: Float) {
        val n = w * h
        val logL = FloatArray(n)
        for (i in 0 until n) {
            val l = 0.2126f * Srgb.toLinear(r[i]) + 0.7152f * Srgb.toLinear(g[i]) + 0.0722f * Srgb.toLinear(b[i])
            logL[i] = ln(l + 1e-3f)
        }
        val radius = maxOf(6, (minOf(w, h) * 0.035f).toInt())
        val base = guidedFilter(logL, logL, w, h, radius, eps = 0.08f)
        var mean = 0.0
        for (v in base) mean += v
        val key = exp(mean / n).toFloat()
        // Whole image too dark: a global lift toward a low-key mid-grey, up to 2 stops.
        val global = if (key < 0.1f) (ln(0.1f / key) * 0.7f * t).coerceAtMost(ln(4f)) else 0f
        val shadow = ln(0.12f)
        IntStream.range(0, h).parallel().forEach { yy ->
            for (x in 0 until w) {
                val i = yy * w + x
                val bl = base[i] + global
                val lift = if (bl < shadow) (shadow - bl) * 0.8f * t else 0f
                val gain = exp(global + lift).coerceIn(1f, 6f)
                if (gain <= 1.001f) continue
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

    /**
     * Contrast on display luma, anchored at the image's median so brightness doesn't shift: a power
     * curve on each side of the median with slope (1 + c) there, ends fixed at 0 and 1. Applied
     * as a ratio to RGB so hues stay put.
     */
    private fun contrastCurve(r: FloatArray, g: FloatArray, b: FloatArray, c: Float) {
        if (c <= 0f) return
        val n = r.size
        val y = FloatArray(n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
        val m = percentile(y, 0.5f).coerceIn(0.2f, 0.8f)
        // Gentler above the median, so bright clouds keep their detail instead of clipping.
        val lo = 1f + c
        val hi = 1f + 0.3f * c
        for (i in 0 until n) {
            val v = y[i]
            if (v <= 1e-4f) continue
            val f = if (v < m) m * Math.pow((v / m).toDouble(), lo.toDouble()).toFloat()
            else 1f - (1f - m) * Math.pow(((1f - v) / (1f - m)).coerceAtLeast(0f).toDouble(), hi.toDouble()).toFloat()
            val k = f / v
            var rr = r[i] * k
            var gg = g[i] * k
            var bb = b[i] * k
            // Keep the hue when a channel would overflow: pull toward the new luma instead of clipping.
            val mx = maxOf(rr, gg, bb)
            if (mx > 1f) {
                val s = (1f - f) / (mx - f).coerceAtLeast(1e-4f)
                rr = f + (rr - f) * s; gg = f + (gg - f) * s; bb = f + (bb - f) * s
            }
            r[i] = rr.coerceIn(0f, 1f)
            g[i] = gg.coerceIn(0f, 1f)
            b[i] = bb.coerceIn(0f, 1f)
        }
    }

    /**
     * Vibrance in Oklab: muted colours gain chroma, already-vivid ones barely move, skin hues get
     * half the boost, and near-greys are left neutral.
     */
    private fun vibrance(r: FloatArray, g: FloatArray, b: FloatArray, v: Float) {
        if (v <= 0f) return
        IntStream.range(0, r.size).parallel().forEach { i ->
            val lab = Oklab.fromLinearSrgb(Srgb.toLinear(r[i]), Srgb.toLinear(g[i]), Srgb.toLinear(b[i]))
            val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            if (c < 0.004f) return@forEach
            val hue = Math.toDegrees(atan2(lab[2], lab[1]).toDouble()).toFloat()
            val skin = if (hue in 25f..80f) 0.5f else 1f
            val muted = 1f - smoothstep(0.04f, 0.22f, c)
            val greyGuard = smoothstep(0.004f, 0.02f, c)
            val k = 1f + v * muted * greyGuard * skin
            val rgb = Oklab.toLinearSrgb(lab[0], lab[1] * k, lab[2] * k)
            r[i] = Srgb.fromLinear(rgb[0].coerceIn(0f, 1f))
            g[i] = Srgb.fromLinear(rgb[1].coerceIn(0f, 1f))
            b[i] = Srgb.fromLinear(rgb[2].coerceIn(0f, 1f))
        }
    }

    /**
     * Clarity: the mid-frequency layer (luma minus an edge-aware blur) boosted in the midtones,
     * which gives texture and depth without halos at strong edges. Sharpening: the finest layer
     * (radius ~1 px) boosted. Both are cored at the noise floor and the overshoot is limited.
     */
    private fun detail(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, k: Float, sigma: Float, scale: Float) {
        val n = w * h
        val y = FloatArray(n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
        val mid = guidedFilter(y, y, w, h, maxOf(4, (12 * scale).toInt()), eps = 0.004f)
        val fine = y.copyOf()
        repeat(2) { Finisher.boxBlur(fine, w, h, 1) }
        val cMid = 1.5f * sigma
        val cFine = 2f * sigma
        // Texture mask: local standard deviation of luma. Smooth areas (sky, skin, walls) get no
        // boost, so their grain and JPEG mottling stay quiet; textured areas get the full amount.
        val m1 = y.copyOf().also { Finisher.boxBlur(it, w, h, 2) }
        val m2 = FloatArray(n) { y[it] * y[it] }.also { Finisher.boxBlur(it, w, h, 2) }
        val lo = 2.5f * sigma
        val hi = 6f * sigma + 0.01f
        IntStream.range(0, h).parallel().forEach { yy ->
            for (x in 0 until w) {
                val i = yy * w + x
                val v = y[i]
                val midtone = (4f * v * (1f - v)).coerceIn(0f, 1f)
                val sd = sqrt((m2[i] - m1[i] * m1[i]).coerceAtLeast(0f))
                val texture = smoothstep(lo, hi, sd)
                val dm = core(v - mid[i], cMid) * 0.9f * k * midtone * texture
                val df = core(v - fine[i], cFine) * 1.3f * k * texture
                val add = (dm + df).coerceIn(-0.12f, 0.12f)
                r[i] = (r[i] + add).coerceIn(0f, 1f)
                g[i] = (g[i] + add).coerceIn(0f, 1f)
                b[i] = (b[i] + add).coerceIn(0f, 1f)
            }
        }
    }

    /** 2x2 average, for measuring noise at half resolution. */
    private fun half(y: FloatArray, w: Int, h: Int): FloatArray {
        val hw = w / 2
        val hh = h / 2
        return FloatArray(hw * hh) { i ->
            val x = (i % hw) * 2
            val yy = (i / hw) * 2
            0.25f * (y[yy * w + x] + y[yy * w + x + 1] + y[(yy + 1) * w + x] + y[(yy + 1) * w + x + 1])
        }
    }

    private fun core(d: Float, t: Float): Float {
        val a = abs(d)
        return if (a <= t) 0f else (a - t) * (if (d > 0) 1f else -1f)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Approximate percentile of values in 0..1 from a 4096-bin histogram. */
    private fun percentile(v: FloatArray, q: Float): Float {
        val bins = IntArray(4096)
        for (x in v) bins[(x.coerceIn(0f, 1f) * 4095f).toInt()]++
        val target = (q * v.size).toLong()
        var acc = 0L
        for (k in bins.indices) {
            acc += bins[k]
            if (acc > target) return k / 4095f
        }
        return 1f
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
