package app.proview.pipeline.enhance

import app.proview.pipeline.SyntheticBurst
import app.proview.pipeline.look.Look
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleImageEnhancerTest {
    private val w = 320
    private val h = 240

    private fun argb(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().let { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }
    private fun Int.g() = ((this shr 8) and 0xFF) / 255f

    /** Smooth gradient with a hard vertical edge at x = w/2, plus Gaussian noise. */
    private fun scene(sigma: Float, seed: Int = 1, dark: Boolean = false): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var v = if (x < w / 2) 0.25f + 0.1f * y / h else 0.65f
            if (dark) v *= 0.25f
            argb(v + sigma * SyntheticBurst.gaussian(rnd))
        }
    }

    private fun lumaOf(px: IntArray) = FloatArray(px.size) { px[it].g() }

    @Test
    fun `noise estimate matches the noise added`() {
        val sigma = SingleImageEnhancer.estimateNoise(lumaOf(scene(0.03f)), w, h)
        assertEquals(0.03f, sigma, 0.006f)
    }

    @Test
    fun `denoise cuts noise by half or more and keeps the edge`() {
        val img = scene(0.03f)
        val (out, m) = SingleImageEnhancer.enhance(img, w, h, EnhanceParams(denoise = 1f, tone = 0f, detail = 0f, look = Look.NATURAL))
        assertTrue(m.noiseReduction >= 0.5f, "noise reduction ${m.noiseReduction}")
        // Edge: mean levels two pixels either side of the step stay far apart.
        val y = lumaOf(out)
        var left = 0f
        var right = 0f
        for (row in 40 until 200) { left += y[row * w + w / 2 - 3]; right += y[row * w + w / 2 + 2] }
        val step = (right - left) / 160f
        assertTrue(step > 0.3f, "edge softened: step $step")
    }

    @Test
    fun `tone lifts deep shadows without adding clipping`() {
        val img = scene(0.005f, dark = true)
        val (_, m) = SingleImageEnhancer.enhance(img, w, h, EnhanceParams(denoise = 0f, tone = 1f, detail = 0f, look = Look.NATURAL))
        assertTrue(m.shadowsAfter < m.shadowsBefore * 0.7f, "shadows ${m.shadowsBefore} -> ${m.shadowsAfter}")
        assertTrue(m.clippedAfter <= m.clippedBefore + 0.001f)
    }

    @Test
    fun `detail raises local contrast but not on noise alone`() {
        val (_, onEdges) = SingleImageEnhancer.enhance(scene(0.002f), w, h, EnhanceParams(denoise = 0f, tone = 0f, detail = 1f, look = Look.NATURAL))
        assertTrue(onEdges.localContrastAfter > onEdges.localContrastBefore, "no detail boost")
        // Pure noise on a flat field: the threshold keeps noise from being amplified much.
        val rnd = Random(3)
        val flat = IntArray(w * h) { argb(0.5f + 0.01f * SyntheticBurst.gaussian(rnd)) }
        val (_, m) = SingleImageEnhancer.enhance(flat, w, h, EnhanceParams(denoise = 0f, tone = 0f, detail = 1f, look = Look.NATURAL))
        assertTrue(m.noiseAfter < m.noiseBefore * 1.25f, "noise amplified ${m.noiseBefore} -> ${m.noiseAfter}")
    }

    @Test
    fun `all strengths at zero with Natural leaves the image alone`() {
        val img = scene(0.01f)
        val (out, _) = SingleImageEnhancer.enhance(img, w, h, EnhanceParams(0f, 0f, 0f, Look.NATURAL))
        var maxDiff = 0f
        for (i in img.indices) maxDiff = maxOf(maxDiff, abs(img[i].g() - out[i].g()))
        assertTrue(maxDiff <= 2.5f / 255f, "changed by ${maxDiff * 255} levels")
    }

    @Test
    fun `guided filter preserves a step and smooths a flat noisy area`() {
        val rnd = Random(5)
        val p = FloatArray(w * h) { i -> (if (i % w < w / 2) 0.2f else 0.8f) + 0.02f * SyntheticBurst.gaussian(rnd) }
        val q = SingleImageEnhancer.guidedFilter(p, p, w, h, radius = 3, eps = 0.002f)
        var noiseBefore = 0.0
        var noiseAfter = 0.0
        for (row in 20 until 220) for (x in 20 until 120) {
            noiseBefore += abs(p[row * w + x] - 0.2f); noiseAfter += abs(q[row * w + x] - 0.2f)
        }
        assertTrue(noiseAfter < noiseBefore * 0.4, "flat area not smoothed")
        assertTrue(q[120 * w + w / 2 + 2] - q[120 * w + w / 2 - 3] > 0.5f, "edge lost")
    }
}
