package app.proview.pipeline.finish

import app.proview.pipeline.enhance.SingleImageEnhancer
import app.proview.pipeline.look.Oklab
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The Proview rendering: how scene light becomes a photo. Inspired by Hasselblad's natural
 * colour: restraint everywhere (see docs/ALGORITHM.md, "Rendition").
 *
 * - Exposure from the scene's own key, never pushed so far that real highlights clip.
 * - Local lift capped at [maxLiftStops], so the scene keeps its light instead of the flat HDR look.
 * - A gentle global contrast in log space, then a long highlight shoulder that reaches white
 *   only at the sensor's clip point.
 * - Colour stays calibrated: only a small saturation factor, chroma eased off in the brightest
 *   highlights (as film does), and out-of-gamut colours compressed toward grey at constant
 *   lightness and hue instead of clipped, so skies and skin never shift hue.
 * - Light sharpening, cored so noise isn't sharpened.
 */
// Defaults: tone placement first calibrated on 15 RAW files (14 cameras) against each maker's
// own JPEG of the same frame, then moved toward Hasselblad samples (deeper blacks, wider range,
// richer greens/blues/yellows with skin untouched, cleaner highlights): see docs/ALGORITHM.md.
data class RenderStyle(
    /** Scene mid-grey after exposure (linear). */
    val key: Float = 0.15f,
    /** Slope of the tone curve around mid-grey in log-log; 1 = no added contrast. */
    val contrast: Float = 1.3f,
    /** Log-log slope above mid-grey, eased in over 1.5 stops: below 1 = soft, compressed highlights. */
    val highContrast: Float = 1.0f,
    /** Extra log-log slope in the deep shadows (the toe): blacks get deeper, detail stays. */
    val toe: Float = 0.8f,
    /** Fraction of the measured veiling flare (lens haze) taken off the black point. */
    val flare: Float = 0.8f,
    /** Linear display level where the highlight shoulder starts. */
    val shoulderStart: Float = 0.5f,
    /** Most a shadow region is lifted, in stops. */
    val maxLiftStops: Float = 1f,
    /** How far toward the lift target dark regions move (0..1). */
    val liftStrength: Float = 0.5f,
    /** Chroma factor on calibrated colour; 1 = colorimetric. */
    val saturation: Float = 1.0f,
    /**
     * Hue-selective chroma lift for muted-to-medium colours (Oklab hue): foliage greens, sky
     * blues and yellows get richer while reds and skin stay calibrated, and colours that are
     * already vivid aren't pushed further. Measured on Hasselblad samples.
     */
    val greenLift: Float = 0.3f,
    val blueLift: Float = 0.3f,
    val yellowLift: Float = 0.15f,
    /** Chroma removed at full white (0..1), eased in over the top of the tone range. */
    val highlightDesat: Float = 0.6f,
    /** Unsharp amount on display luma, radius ~1 px. */
    val sharpen: Float = 0.35f,
    /** Exposure: true = set from the scene; false = use the gain passed in. */
    val autoExposure: Boolean = true,
) {
    companion object {
        val DAY = RenderStyle()
        /** Night keeps its own low key and gain (set by NightProcessor). */
        val NIGHT = RenderStyle(key = 0.12f, autoExposure = false, liftStrength = 0.35f, sharpen = 0.25f)
    }
}

object Rendition {
    /**
     * Renders linear, white-balanced, calibrated sRGB (1.0 = the sensor's clip level, before any
     * exposure gain) into display sRGB in place. [gain] is used when the style has no auto
     * exposure. Returns the exposure gain applied.
     */
    fun render(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, style: RenderStyle, gain: Float = 1f): Float {
        val n = w * h
        val lum = FloatArray(n) { 0.2126f * r[it] + 0.7152f * g[it] + 0.0722f * b[it] }
        val exposure = if (style.autoExposure) autoExposure(lum, style.key) else gain
        val white = exposure // the sensor clip level after gain
        // Veiling flare lifts the black point evenly; measure it (darkest 0.1% after exposure) and
        // take most of it off, so the photo isn't milky. Capped so real shadows aren't crushed.
        val flareBlack = if (style.flare > 0f) (percentileOf(lum, 0.001f) * exposure).coerceIn(0f, 0.008f) * style.flare else 0f

        val lift = if (style.liftStrength > 0f && style.maxLiftStops > 0f) liftMap(lum, w, h, exposure, style) else null

        val sat = style.saturation
        val c = style.contrast
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val k = exposure * (lift?.get(i) ?: 1f)
                var rr = r[i] * k
                var gg = g[i] * k
                var bb = b[i] * k
                var l = lum[i] * k
                if (flareBlack > 0f) {
                    val fl = flareBlack * (lift?.get(i) ?: 1f)
                    val s = (l - fl).coerceAtLeast(0f) / l.coerceAtLeast(1e-7f)
                    rr *= s; gg *= s; bb *= s; l *= s
                }
                if (l > 1e-7f) {
                    // Global contrast around mid-grey (log space) with a deeper toe below it,
                    // then the long shoulder.
                    val stops = ln(l / 0.18f) / LN2
                    val p = if (stops < 0f) c + style.toe * (1f - smoothstep(-4f, 0f, stops))
                    else c + (style.highContrast - c) * smoothstep(0f, 1.5f, stops)
                    val contrasted = 0.18f * (l / 0.18f).pow(p)
                    val t = Finisher.tone(contrasted, style.shoulderStart, whiteAfterContrast(white, style.highContrast))
                    val ratio = t / l
                    rr *= ratio; gg *= ratio; bb *= ratio
                }
                // Colour: chroma factor, highlight ease-off, gamut compression, all in Oklab.
                val lab = Oklab.fromLinearSrgb(rr.coerceAtLeast(0f), gg.coerceAtLeast(0f), bb.coerceAtLeast(0f))
                val lt = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                val ease = 1f - style.highlightDesat * smoothstep(0.55f, 1f, lt)
                val f = sat * ease * hueLift(lab[1], lab[2], style)
                val rgb = gamutMap(lab[0], lab[1] * f, lab[2] * f)
                r[i] = Finisher.srgbEncode(rgb[0])
                g[i] = Finisher.srgbEncode(rgb[1])
                b[i] = Finisher.srgbEncode(rgb[2])
            }
        }
        if (style.sharpen > 0f) sharpen(r, g, b, w, h, style.sharpen)
        return exposure
    }

    private val LN2 = ln(2f)

    private fun percentileOf(v: FloatArray, q: Float): Float {
        val step = maxOf(1, v.size / 400_000)
        val sample = FloatArray((v.size + step - 1) / step) { v[it * step] }
        sample.sort()
        return sample[(sample.size * q).toInt().coerceIn(0, sample.size - 1)]
    }

    /** The sensor's clip level carried through the contrast curve. */
    private fun whiteAfterContrast(white: Float, c: Float): Float = 0.18f * (white / 0.18f).pow(c)

    /**
     * Gain that brings the scene's key (log-average luminance of its middle 96%) to [key], so
     * a few lamps or a dark corner don't swing it. Limited so the brightest unclipped detail
     * (99.5th percentile) stays within ~1.5 stops above white, where the shoulder still holds it.
     */
    fun autoExposure(lum: FloatArray, key: Float): Float {
        val step = maxOf(1, lum.size / 400_000)
        val sample = FloatArray((lum.size + step - 1) / step) { lum[it * step] }
        sample.sort()
        val lo = (sample.size * 0.02).toInt()
        val hi = (sample.size * 0.98).toInt().coerceAtLeast(lo + 1)
        var s = 0.0
        for (k in lo until hi) s += ln(maxOf(sample[k], 1e-5f).toDouble())
        val geo = exp(s / (hi - lo)).toFloat()
        var gain = key / geo
        // Dark scenes stay dark: beyond +2 stops only half the remaining push is applied, and never
        // more than +3 stops in all (night mode handles real darkness).
        val push = ln(gain) / LN2
        if (push > 2f) gain = 2f.pow(minOf(2f + 0.5f * (push - 2f), 3f))
        // Highlight guard: real (unclipped) highlight detail must stay below ~2.8x white.
        val p995 = sample[(sample.size * 0.995).toInt().coerceAtMost(sample.size - 1)]
        if (p995 < 0.97f && p995 > 1e-5f) gain = minOf(gain, 2.8f / p995)
        return gain.coerceIn(0.25f, 32f)
    }

    /**
     * Per-pixel lift (>= 1): dark regions of an edge-aware base layer move toward a readable
     * level, capped at [RenderStyle.maxLiftStops]. Computed at quarter resolution for speed.
     */
    private fun liftMap(lum: FloatArray, w: Int, h: Int, exposure: Float, style: RenderStyle): FloatArray {
        val f = 4
        val qw = maxOf(1, w / f)
        val qh = maxOf(1, h / f)
        val q = FloatArray(qw * qh)
        for (y in 0 until qh) for (x in 0 until qw) {
            var s = 0f
            for (dy in 0 until f) for (dx in 0 until f) s += lum[minOf(h - 1, y * f + dy) * w + minOf(w - 1, x * f + dx)]
            q[y * qw + x] = ln(s / (f * f) * exposure + 1e-4f)
        }
        val radius = maxOf(3, (minOf(qw, qh) * 0.04f).toInt())
        val base = SingleImageEnhancer.guidedFilter(q, q, qw, qh, radius, eps = 0.1f)
        val target = ln(style.key * 0.45f)
        val cap = style.maxLiftStops * ln(2f)
        val qLift = FloatArray(qw * qh) { k ->
            val d = target - base[k]
            if (d > 0f) exp(minOf(d * style.liftStrength, cap)) else 1f
        }
        // Bilinear upsample.
        val out = FloatArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (qh - 1).toFloat())
            val y0 = fy.toInt().coerceAtMost(maxOf(0, qh - 2))
            val y1 = minOf(y0 + 1, qh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (qw - 1).toFloat())
                val x0 = fx.toInt().coerceAtMost(maxOf(0, qw - 2))
                val x1 = minOf(x0 + 1, qw - 1)
                val tx = fx - x0
                val top = qLift[y0 * qw + x0] + (qLift[y0 * qw + x1] - qLift[y0 * qw + x0]) * tx
                val bot = qLift[y1 * qw + x0] + (qLift[y1 * qw + x1] - qLift[y1 * qw + x0]) * tx
                out[y * w + x] = top + (bot - top) * ty
            }
        }
        return out
    }

    /**
     * Oklab -> linear sRGB inside [0, 1]: if the colour is out of gamut, chroma is reduced (same
     * lightness and hue) by bisection until it fits; lightness itself is clamped to [0, 1].
     */
    fun gamutMap(l: Float, a: Float, b: Float): FloatArray {
        val ll = l.coerceIn(0f, 1f)
        var rgb = Oklab.toLinearSrgb(ll, a, b)
        if (inGamut(rgb)) return rgb
        var lo = 0f
        var hi = 1f
        repeat(10) {
            val mid = 0.5f * (lo + hi)
            if (inGamut(Oklab.toLinearSrgb(ll, a * mid, b * mid))) lo = mid else hi = mid
        }
        rgb = Oklab.toLinearSrgb(ll, a * lo, b * lo)
        for (k in 0..2) rgb[k] = rgb[k].coerceIn(0f, 1f)
        return rgb
    }

    private fun inGamut(c: FloatArray): Boolean {
        val e = 1e-4f
        return c[0] >= -e && c[0] <= 1f + e && c[1] >= -e && c[1] <= 1f + e && c[2] >= -e && c[2] <= 1f + e
    }

    /** Unsharp mask on display luma, radius ~1 px, cored at 1.5/255 so flat noise isn't boosted. */
    private fun sharpen(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, amount: Float) {
        val n = w * h
        val y = FloatArray(n) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
        val blur = y.copyOf()
        repeat(2) { Finisher.boxBlur(blur, w, h, 1) }
        val core = 1.5f / 255f
        for (i in 0 until n) {
            val d = y[i] - blur[i]
            val ad = abs(d) - core
            if (ad <= 0f) continue
            val add = (ad * (if (d > 0) 1f else -1f) * amount).coerceIn(-0.06f, 0.06f)
            r[i] = (r[i] + add).coerceIn(0f, 1f)
            g[i] = (g[i] + add).coerceIn(0f, 1f)
            b[i] = (b[i] + add).coerceIn(0f, 1f)
        }
    }

    /**
     * Chroma factor from hue: raised-cosine bumps centred on yellow (~100 deg), green (~145 deg)
     * and sky blue (~255 deg) in Oklab, kept clear of skin (~30-70 deg). The lift fades out for
     * near-greys (no tint) and for colours that are already vivid (chroma above ~0.2).
     */
    private fun hueLift(a: Float, b: Float, st: RenderStyle): Float {
        if (st.greenLift == 0f && st.blueLift == 0f && st.yellowLift == 0f) return 1f
        val c = kotlin.math.sqrt(a * a + b * b)
        if (c < 0.005f) return 1f
        val h = Math.toDegrees(kotlin.math.atan2(b, a).toDouble()).toFloat().let { if (it < 0f) it + 360f else it }
        val w = st.yellowLift * bump(h, 100f, 30f) + st.greenLift * bump(h, 145f, 45f) + st.blueLift * bump(h, 255f, 45f)
        val amount = smoothstep(0.005f, 0.025f, c) * (1f - smoothstep(0.1f, 0.22f, c))
        return 1f + w * amount
    }

    private fun bump(h: Float, centre: Float, halfWidth: Float): Float {
        var d = abs(h - centre)
        if (d > 180f) d = 360f - d
        if (d >= halfWidth) return 0f
        return 0.5f + 0.5f * kotlin.math.cos(Math.PI.toFloat() * d / halfWidth)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
