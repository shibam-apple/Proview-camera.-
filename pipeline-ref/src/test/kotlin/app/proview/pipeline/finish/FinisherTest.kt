package app.proview.pipeline.finish

import app.proview.pipeline.raw.Cfa
import app.proview.pipeline.raw.NoiseModel
import app.proview.pipeline.raw.RawFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinisherTest {
    private val noise = NoiseModel(FloatArray(4) { 1e-5f }, FloatArray(4) { 1e-7f })
    private val identity = FinishParams(
        wbGains = floatArrayOf(1f, 1f, 1f, 1f),
        cameraToSrgb = M3.IDENTITY,
        exposureGain = 1f,
        chromaRadius = 0,
    )

    private fun flat(w: Int, h: Int, rgb: FloatArray): RawFrame {
        val cfa = Cfa.RGGB
        return RawFrame(w, h, FloatArray(w * h) { i ->
            when (cfa.channelAt(i % w, i / w)) {
                0 -> rgb[0]
                3 -> rgb[2]
                else -> rgb[1]
            }
        }, cfa, noise)
    }

    private fun Int.r() = (this shr 16) and 0xFF
    private fun Int.g() = (this shr 8) and 0xFF
    private fun Int.b() = this and 0xFF

    @Test
    fun `demosaic reproduces a flat colour exactly`() {
        val rgb = Demosaic.mhc(flat(32, 32, floatArrayOf(0.3f, 0.5f, 0.2f)))
        for (i in rgb.r.indices) {
            assertEquals(0.3f, rgb.r[i], 1e-5f)
            assertEquals(0.5f, rgb.g[i], 1e-5f)
            assertEquals(0.2f, rgb.b[i], 1e-5f)
        }
    }

    @Test
    fun `demosaic follows a smooth ramp`() {
        val w = 64
        val frame = RawFrame(w, 32, FloatArray(w * 32) { i -> (i % w) / w.toFloat() }, Cfa.RGGB, noise)
        val rgb = Demosaic.mhc(frame)
        for (y in 4 until 28) for (x in 4 until w - 4) {
            val want = x / w.toFloat()
            val i = y * w + x
            assertEquals(want, rgb.r[i], 1e-4f)
            assertEquals(want, rgb.g[i], 1e-4f)
            assertEquals(want, rgb.b[i], 1e-4f)
        }
    }

    @Test
    fun `tone curve is identity below the shoulder and reaches 1 at the clip level`() {
        assertEquals(0.3f, Finisher.tone(0.3f, 0.55f, 1.2f), 0f)
        assertEquals(1f, Finisher.tone(1.2f, 0.55f, 1.2f), 1e-6f)
        var last = 0f
        for (k in 1..24) {
            val t = Finisher.tone(k / 20f, 0.55f, 1.2f)
            assertTrue(t >= last && t <= 1f, "not monotonic at ${k / 20f}")
            last = t
        }
        // Smooth: no kink at the shoulder.
        val below = Finisher.tone(0.549f, 0.55f, 1.2f)
        val above = Finisher.tone(0.551f, 0.55f, 1.2f)
        assertTrue(above - below < 0.0025f)
    }

    @Test
    fun `neutral grey stays neutral`() {
        val out = Finisher.finish(flat(32, 32, floatArrayOf(0.18f, 0.18f, 0.18f)), identity.copy(chromaRadius = 3))
        val px = out.argb[16 * 32 + 16]
        assertTrue(abs(px.r() - px.g()) <= 1 && abs(px.b() - px.g()) <= 1, "grey came out ${px.r()},${px.g()},${px.b()}")
        // 0.18 linear is sRGB 118.
        assertTrue(abs(px.g() - 118) <= 2, "grey level ${px.g()}")
    }

    @Test
    fun `blown highlights go white, not magenta`() {
        val params = identity.copy(wbGains = floatArrayOf(2.0f, 1f, 1f, 1.6f))
        val out = Finisher.finish(flat(32, 32, floatArrayOf(1f, 1f, 1f)), params)
        val px = out.argb[16 * 32 + 16]
        assertTrue(px.r() == 255 && px.g() == 255 && px.b() == 255, "clipped highlight came out ${px.r()},${px.g()},${px.b()}")
    }

    @Test
    fun `a slow gradient has no banding`() {
        // A dark fog-like ramp covering ~12 output levels over 512 px.
        val w = 512
        val h = 16
        val frame = RawFrame(w, h, FloatArray(w * h) { i -> 0.02f + 0.02f * (i % w) / w }, Cfa.RGGB, noise)
        val out = Finisher.finish(frame, identity)
        val greens = (0 until w).map { x -> out.argb[8 * w + x].g() }
        val levels = greens.toSet()
        // Every level between the darkest and brightest appears (no gaps) ...
        assertEquals((greens.min()..greens.max()).toSet(), levels)
        // ... and the column means rise smoothly: no step bigger than one level.
        val colMeans = (0 until w step 16).map { x0 -> (x0 until x0 + 16).map { x -> (0 until h).map { y -> out.argb[y * w + x].g() }.average() }.average() }
        colMeans.zipWithNext().forEach { (a, b) -> assertTrue(b - a > -0.3 && b - a < 1.0, "step $a -> $b") }
    }

    @Test
    fun `output is rotated clockwise`() {
        // Bright pixel near the top-left of a landscape frame ends up near the top-right in portrait.
        val w = 32
        val h = 16
        val data = FloatArray(w * h) { 0.01f }
        for (y in 0..3) for (x in 0..3) data[y * w + x] = 0.9f
        val out = Finisher.finish(RawFrame(w, h, data, Cfa.RGGB, noise), identity.copy(orientation = 90))
        assertEquals(h, out.width)
        assertEquals(w, out.height)
        assertTrue(out.argb[1 * out.width + (out.width - 2)].g() > 200)
        assertTrue(out.argb[1 * out.width + 1].g() < 60)
    }
}
