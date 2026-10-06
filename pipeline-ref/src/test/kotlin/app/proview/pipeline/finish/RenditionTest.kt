package app.proview.pipeline.finish

import app.proview.pipeline.look.Oklab
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertTrue

class RenditionTest {
    private fun render(values: List<FloatArray>, style: RenderStyle = RenderStyle.DAY.copy(sharpen = 0f, liftStrength = 0f, flare = 0f), gain: Float = 1f): List<FloatArray> {
        val n = values.size
        val r = FloatArray(n) { values[it][0] }
        val g = FloatArray(n) { values[it][1] }
        val b = FloatArray(n) { values[it][2] }
        Rendition.render(r, g, b, n, 1, style.copy(autoExposure = false), gain)
        return List(n) { floatArrayOf(r[it], g[it], b[it]) }
    }

    @Test
    fun `greys stay neutral and tones rise monotonically to white at the clip`() {
        val levels = (0..60).map { 1e-3f * Math.pow(1.13, it.toDouble()).toFloat() }.map { it.coerceAtMost(1f) } + 1f
        val out = render(levels.map { floatArrayOf(it, it, it) })
        for (k in out.indices) {
            val c = out[k]
            assertTrue(abs(c[0] - c[1]) < 2e-3f && abs(c[1] - c[2]) < 2e-3f, "grey $k tinted: ${c.toList()}")
            if (k > 0) assertTrue(c[1] >= out[k - 1][1] - 1e-4f, "not monotonic at $k")
        }
        assertTrue(out.last()[1] > 0.995f, "sensor clip doesn't reach white: ${out.last()[1]}")
    }

    @Test
    fun `mid grey lands near the middle of the display range`() {
        val v = render(listOf(floatArrayOf(0.18f, 0.18f, 0.18f)))[0][1]
        assertTrue(v in 0.42f..0.52f, "mid grey -> $v")
    }

    @Test
    fun `bright saturated colours keep their hue instead of clipping`() {
        // A vivid sky blue and a saturated red pushed past the display gamut.
        for (c in listOf(floatArrayOf(0.05f, 0.25f, 0.9f), floatArrayOf(0.95f, 0.08f, 0.04f))) {
            val inLab = Oklab.fromLinearSrgb(c[0], c[1], c[2])
            val out = render(listOf(c), gain = 1.6f)[0]
            val lin = FloatArray(3) { srgbToLinear(out[it]) }
            val outLab = Oklab.fromLinearSrgb(lin[0], lin[1], lin[2])
            val h1 = Math.toDegrees(atan2(inLab[2], inLab[1]).toDouble())
            val h2 = Math.toDegrees(atan2(outLab[2], outLab[1]).toDouble())
            var dh = abs(h1 - h2); if (dh > 180) dh = 360 - dh
            assertTrue(dh < 4.0, "hue moved ${"%.1f".format(dh)} deg for ${c.toList()}")
        }
    }

    private fun chromaRatio(c: FloatArray): Double {
        val out = render(listOf(c))[0]
        val lin = FloatArray(3) { srgbToLinear(out[it]) }
        val a = Oklab.fromLinearSrgb(c[0], c[1], c[2])
        val o = Oklab.fromLinearSrgb(lin[0], lin[1], lin[2])
        return (Math.hypot(o[1].toDouble(), o[2].toDouble()) / o[0]) / (Math.hypot(a[1].toDouble(), a[2].toDouble()) / a[0])
    }

    @Test
    fun `skin keeps calibrated saturation and foliage lift stays bounded`() {
        val skin = chromaRatio(floatArrayOf(0.45f, 0.28f, 0.2f))
        assertTrue(skin < 1.08, "skin saturation boosted x$skin")
        val green = chromaRatio(floatArrayOf(0.12f, 0.2f, 0.08f))
        assertTrue(green in 1.0..1.4, "foliage lift out of range x$green")
        // An already vivid green is not pushed further.
        val vivid = chromaRatio(floatArrayOf(0.02f, 0.35f, 0.02f))
        assertTrue(vivid < 1.1, "vivid colour boosted x$vivid")
    }

    @Test
    fun `gamut map leaves in-gamut colours alone`() {
        val lab = Oklab.fromLinearSrgb(0.3f, 0.4f, 0.2f)
        val back = Rendition.gamutMap(lab[0], lab[1], lab[2])
        assertTrue(abs(back[0] - 0.3f) < 1e-3f && abs(back[1] - 0.4f) < 1e-3f && abs(back[2] - 0.2f) < 1e-3f)
    }

    private fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}
