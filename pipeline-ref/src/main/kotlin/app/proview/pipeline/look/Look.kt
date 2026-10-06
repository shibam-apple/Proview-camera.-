package app.proview.pipeline.look

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The design's five looks (UI_SPEC §2). Each is a colour transform on display sRGB, baked into
 * a 3D LUT that the photo pipeline and the live viewfinder shader share, plus film texture
 * (halation glow and grain) applied to photos.
 *
 * - NATURAL: the sensor's calibrated "true colour" (Hasselblad philosophy), untouched.
 * - FILM: our own film-style rendition in the spirit of Fujifilm colour: gentle S-curve, muted
 *   greens leaning teal, deep blues, reds leaning warm, protected skin, cool shadows and warm
 *   highlights, with halation and grain. Not a copy of any proprietary film simulation.
 * - WARM / COOL: Natural with a white-balance shift.
 * - MONO: black and white with a red-filter-like mix, film contrast and grain.
 */
enum class Look(
    val label: String,
    /** Grain amplitude (fraction of full scale at mid-grey). */
    val grain: Float,
    /** Strength of the red-orange glow around highlights. */
    val halation: Float,
) {
    NATURAL("Natural", 0f, 0f),
    WARM("Warm", 0f, 0.05f),
    COOL("Cool", 0f, 0f),
    MONO("Mono", 0.032f, 0f),
    FILM("Film", 0.022f, 0.30f);

    /** Maps display sRGB (0..1) to display sRGB (0..1). */
    fun map(r: Float, g: Float, b: Float, out: FloatArray) {
        when (this) {
            NATURAL -> { out[0] = r; out[1] = g; out[2] = b }
            WARM -> balance(r, g, b, 1.06f, 1f, 0.91f, out)
            COOL -> balance(r, g, b, 0.94f, 1f, 1.08f, out)
            MONO -> mono(r, g, b, out)
            FILM -> film(r, g, b, out)
        }
    }

    companion object {
        val DEFAULT = FILM

        private fun balance(r: Float, g: Float, b: Float, kr: Float, kg: Float, kb: Float, out: FloatArray) {
            val lr = Srgb.toLinear(r) * kr
            val lg = Srgb.toLinear(g) * kg
            val lb = Srgb.toLinear(b) * kb
            // Keep brightness: rescale so luminance is unchanged.
            val before = 0.2126f * Srgb.toLinear(r) + 0.7152f * Srgb.toLinear(g) + 0.0722f * Srgb.toLinear(b)
            val after = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
            val k = if (after > 1e-6f) before / after else 1f
            out[0] = Srgb.fromLinear(lr * k)
            out[1] = Srgb.fromLinear(lg * k)
            out[2] = Srgb.fromLinear(lb * k)
        }

        private fun mono(r: Float, g: Float, b: Float, out: FloatArray) {
            // Red-filter-like mix: skies a touch darker, skin a touch lighter.
            val lin = 0.36f * Srgb.toLinear(r) + 0.54f * Srgb.toLinear(g) + 0.10f * Srgb.toLinear(b)
            val v = filmCurve(Srgb.fromLinear(lin), contrast = 0.22f)
            out[0] = v; out[1] = v; out[2] = v
        }

        /**
         * Film tone curve on display values: an S around mid-grey with a soft toe and shoulder.
         * Fixed points at 0, 0.5 (roughly) and 1; [contrast] 0 = identity.
         */
        fun filmCurve(x: Float, contrast: Float): Float {
            val c = x.coerceIn(0f, 1f)
            // Smoothstep-based S, blended with identity.
            val s = c * c * (3f - 2f * c)
            return (c + (s - c) * contrast * 2f).coerceIn(0f, 1f)
        }

        private fun film(r: Float, g: Float, b: Float, out: FloatArray) {
            val lab = Oklab.fromLinearSrgb(Srgb.toLinear(r), Srgb.toLinear(g), Srgb.toLinear(b))
            var l = lab[0]
            var chroma = hypot(lab[1], lab[2])
            var hue = atan2(lab[2], lab[1]) * 180f / PI.toFloat()
            if (hue < 0) hue += 360f

            // Hue-dependent palette (Oklab hue degrees): weights are smooth bumps, so no banding.
            val red = bump(hue, 25f, 30f)
            val skin = bump(hue, 60f, 22f)
            val yellow = bump(hue, 100f, 25f)
            val green = bump(hue, 140f, 40f)
            val cyan = bump(hue, 200f, 30f)
            val blue = bump(hue, 255f, 35f)

            hue += 4f * red - 4f * skin * signedDistance(hue, 60f) / 22f + 8f * green - 6f * blue
            var sat = 0.90f
            sat *= 1f + 0.06f * red
            sat *= 1f - 0.03f * skin
            sat *= 1f - 0.14f * yellow
            sat *= 1f - 0.22f * green
            sat *= 1f + 0.04f * cyan
            sat *= 1f + 0.06f * blue
            // Highly saturated colours roll off instead of clipping.
            chroma = softChroma(chroma * sat)

            // Tone: film S-curve on lightness; deep blacks, soft highlights.
            l = toneL(l)

            // Split toning: cool shadows, warm highlights.
            val shadow = (1f - l).pow(2) * 0.012f
            val highlight = l * l * 0.010f
            val h = hue * PI.toFloat() / 180f
            val a = chroma * cos(h) - shadow * 0.35f + highlight * 0.25f
            val bb = chroma * sin(h) - shadow * 0.65f + highlight * 0.55f

            val rgb = Oklab.toLinearSrgb(l, a, bb)
            out[0] = Srgb.fromLinear(rgb[0])
            out[1] = Srgb.fromLinear(rgb[1])
            out[2] = Srgb.fromLinear(rgb[2])
        }

        /** Oklab lightness tone curve: S around 0.6 with a lifted-but-deep toe. */
        fun toneL(l: Float): Float {
            val x = l.coerceIn(0f, 1f)
            val s = x * x * (3f - 2f * x)
            return (x + (s - x) * 0.30f).coerceIn(0f, 1f)
        }

        private fun softChroma(c: Float): Float {
            val knee = 0.16f
            if (c <= knee) return c
            return knee + (c - knee) / (1f + (c - knee) / 0.10f)
        }

        /** Smooth 0..1 weight centred on [centre] degrees with half-width [width]. */
        private fun bump(hue: Float, centre: Float, width: Float): Float {
            val d = abs(signedDistance(hue, centre))
            if (d >= width) return 0f
            val t = d / width
            return 0.5f + 0.5f * cos(t * PI.toFloat())
        }

        private fun signedDistance(hue: Float, centre: Float): Float {
            var d = hue - centre
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            return d
        }
    }
}

object Srgb {
    fun toLinear(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun fromLinear(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }
}

/** Björn Ottosson's Oklab: perceptually even, so hue/chroma edits don't shift lightness. */
object Oklab {
    fun fromLinearSrgb(r: Float, g: Float, b: Float): FloatArray {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    fun toLinearSrgb(lightness: Float, a: Float, b: Float): FloatArray {
        val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        return floatArrayOf(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
        )
    }
}
