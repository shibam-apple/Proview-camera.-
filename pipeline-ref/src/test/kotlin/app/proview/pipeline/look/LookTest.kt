package app.proview.pipeline.look

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LookTest {
    private fun map(look: Look, r: Float, g: Float, b: Float) = FloatArray(3).also { look.map(r, g, b, it) }

    private fun lch(rgb: FloatArray): FloatArray {
        val lab = Oklab.fromLinearSrgb(Srgb.toLinear(rgb[0]), Srgb.toLinear(rgb[1]), Srgb.toLinear(rgb[2]))
        var h = atan2(lab[2], lab[1]) * 180f / Math.PI.toFloat()
        if (h < 0) h += 360f
        return floatArrayOf(lab[0], hypot(lab[1], lab[2]), h)
    }

    @Test
    fun `oklab round-trips`() {
        val lab = Oklab.fromLinearSrgb(0.3f, 0.5f, 0.1f)
        val rgb = Oklab.toLinearSrgb(lab[0], lab[1], lab[2])
        assertEquals(0.3f, rgb[0], 1e-4f); assertEquals(0.5f, rgb[1], 1e-4f); assertEquals(0.1f, rgb[2], 1e-4f)
    }

    @Test
    fun `natural is identity through the LUT`() {
        val lut = Lut3d.bake(Look.NATURAL)
        val out = FloatArray(3)
        for (v in listOf(0f, 0.13f, 0.5f, 0.77f, 1f)) {
            lut.apply(v, 1f - v, v * 0.5f, out)
            assertEquals(v, out[0], 1e-5f); assertEquals(1f - v, out[1], 1e-5f); assertEquals(v * 0.5f, out[2], 1e-5f)
        }
    }

    @Test
    fun `film keeps greys nearly neutral and tones in order`() {
        var last = -1f
        for (k in 0..20) {
            val v = k / 20f
            val o = map(Look.FILM, v, v, v)
            val c = lch(o)
            assertTrue(c[1] < 0.02f, "grey $v picked up chroma ${c[1]}")
            assertTrue(c[0] >= last, "tone curve not monotonic at $v")
            last = c[0]
        }
        // Black stays black, white stays white.
        assertTrue(map(Look.FILM, 0f, 0f, 0f).all { it < 0.02f })
        assertTrue(map(Look.FILM, 1f, 1f, 1f).all { it > 0.97f })
    }

    @Test
    fun `film mutes greens and leans them towards teal`() {
        val leaf = floatArrayOf(0.30f, 0.55f, 0.20f)
        val before = lch(leaf)
        val after = lch(map(Look.FILM, leaf[0], leaf[1], leaf[2]))
        assertTrue(after[1] < before[1] * 0.9f, "green chroma ${before[1]} -> ${after[1]}")
        assertTrue(after[2] > before[2], "green hue should move towards teal: ${before[2]} -> ${after[2]}")
    }

    @Test
    fun `film protects skin tones`() {
        val skin = floatArrayOf(0.85f, 0.64f, 0.52f)
        val before = lch(skin)
        val after = lch(map(Look.FILM, skin[0], skin[1], skin[2]))
        assertTrue(abs(after[2] - before[2]) < 6f, "skin hue moved ${before[2]} -> ${after[2]}")
        assertTrue(after[1] > before[1] * 0.8f && after[1] < before[1] * 1.1f, "skin chroma ${before[1]} -> ${after[1]}")
    }

    @Test
    fun `film never produces out of range values for saturated input`() {
        for (c in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 1f, 0f))) {
            assertTrue(map(Look.FILM, c[0], c[1], c[2]).all { it in 0f..1f })
        }
    }

    @Test
    fun `mono is grey`() {
        val o = map(Look.MONO, 0.8f, 0.3f, 0.1f)
        assertEquals(o[0], o[1], 1e-6f); assertEquals(o[1], o[2], 1e-6f)
    }

    @Test
    fun `warm warms and cool cools without changing brightness much`() {
        val g = 0.5f
        val w = map(Look.WARM, g, g, g)
        val c = map(Look.COOL, g, g, g)
        assertTrue(w[0] > w[2] && c[2] > c[0])
        val lum = { v: FloatArray -> 0.2126f * Srgb.toLinear(v[0]) + 0.7152f * Srgb.toLinear(v[1]) + 0.0722f * Srgb.toLinear(v[2]) }
        assertEquals(lum(floatArrayOf(g, g, g)), lum(w), 0.01f)
    }

    @Test
    fun `lut matches the look function between grid points`() {
        val lut = Lut3d.bake(Look.FILM)
        val out = FloatArray(3)
        for (s in listOf(floatArrayOf(0.31f, 0.47f, 0.22f), floatArrayOf(0.71f, 0.52f, 0.43f), floatArrayOf(0.2f, 0.3f, 0.6f))) {
            lut.apply(s[0], s[1], s[2], out)
            val exact = map(Look.FILM, s[0], s[1], s[2])
            for (c in 0..2) assertEquals(exact[c], out[c], 0.01f)
        }
    }
}
