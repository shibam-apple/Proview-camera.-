package app.proview.pipeline.look

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilmRenderTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun Int.r() = (this shr 16) and 0xFF
    private fun Int.g() = (this shr 8) and 0xFF
    private fun Int.b() = this and 0xFF

    @Test
    fun `halation glows red-orange around a lamp and leaves far areas alone`() {
        val w = 400
        val h = 300
        val img = IntArray(w * h) { grey(20) }
        for (y in 140..160) for (x in 190..210) img[y * w + x] = grey(255)

        FilmRender.apply(img, w, h, Look.FILM.let { it }, Lut3d.bake(Look.NATURAL))
        val near = img[150 * w + 225] // just outside the lamp
        val far = img[20 * w + 20]
        assertTrue(near.r() > 30, "no glow next to the lamp: ${near.r()}")
        assertTrue(near.r() > near.b() + 5, "glow should be warm: ${near.r()},${near.g()},${near.b()}")
        assertTrue(far.r() < 26, "glow leaked far away: ${far.r()}")
    }

    @Test
    fun `grain keeps the average brightness`() {
        val w = 256
        val h = 256
        val img = IntArray(w * h) { grey(118) }
        FilmRender.apply(img, w, h, Look.MONO, Lut3d.bake(Look.NATURAL))
        val mean = img.map { it.g() }.average()
        val varSum = img.map { (it.g() - mean) * (it.g() - mean) }.average()
        assertEquals(118.0, mean, 0.6)
        assertTrue(varSum > 1.0, "no visible grain (variance $varSum)")
        assertTrue(varSum < 60.0, "grain too strong (variance $varSum)")
    }

    @Test
    fun `natural with no texture changes nothing`() {
        val w = 64
        val h = 64
        val img = IntArray(w * h) { i -> (0xFF shl 24) or ((i % 256) shl 16) or (((i / 3) % 256) shl 8) or ((i / 7) % 256) }
        val copy = img.copyOf()
        FilmRender.apply(img, w, h, Look.NATURAL)
        for (i in img.indices) {
            assertTrue(kotlin.math.abs(img[i].r() - copy[i].r()) <= 1 && kotlin.math.abs(img[i].g() - copy[i].g()) <= 1)
        }
    }
}
