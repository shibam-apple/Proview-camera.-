package app.proview.pipeline

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileAlignerTest {

    /** Smooth, textured test scene: overlapping sinusoids plus fine random detail. */
    private fun scene(width: Int, height: Int, seed: Int = 1): Plane {
        val rnd = Random(seed)
        val detail = Plane.create(width + 64, height + 64) { _, _ -> rnd.nextFloat() }
        val fine = Pyramid.binomialBlur(detail)
        return Plane.create(width + 64, height + 64) { x, y ->
            0.5f + 0.2f * sin(x * 0.07f) * sin(y * 0.05f) + 0.15f * sin((x + 2 * y) * 0.021f) + 0.3f * (fine[x, y] - 0.5f)
        }
    }

    /** Crops [w]x[h] from [src] starting at ([x0], [y0]). */
    private fun crop(src: Plane, x0: Int, y0: Int, w: Int, h: Int) = Plane.create(w, h) { x, y -> src[x + x0, y + y0] }

    private fun addNoise(p: Plane, sigma: Float, seed: Int): Plane {
        val rnd = Random(seed)
        return Plane.create(p.width, p.height) { x, y ->
            // Box-Muller Gaussian noise.
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
            val u2 = rnd.nextDouble()
            val g = kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
            p[x, y] + sigma * g.toFloat()
        }
    }

    /** Fraction of tiles (away from the borders) whose displacement equals ([dx], [dy]). */
    private fun accuracy(field: AlignmentField, dx: Int, dy: Int, margin: Int = 2): Double {
        var ok = 0
        var total = 0
        for (ty in margin until field.tilesY - margin) {
            for (tx in margin until field.tilesX - margin) {
                val i = field.index(tx, ty)
                total++
                if (field.dx[i] == dx && field.dy[i] == dy) ok++
            }
        }
        return ok.toDouble() / total
    }

    @Test
    fun `recovers a global integer shift exactly`() {
        val big = scene(320, 240)
        val ref = crop(big, 32, 32, 320, 240)
        // Alternate frame = scene moved so that ref(x, y) == alt(x + 7, y - 5).
        val alt = crop(big, 32 - 7, 32 + 5, 320, 240)

        val field = TileAligner().align(ref, alt)

        assertEquals(1.0, accuracy(field, 7, -5), "every interior tile should find (7, -5)")
    }

    @Test
    fun `recovers a large shift through the coarse levels`() {
        val big = scene(320, 240)
        val ref = crop(big, 32, 32, 320, 240)
        val alt = crop(big, 32 - 23, 32 - 17, 320, 240)

        val field = TileAligner().align(ref, alt)

        assertEquals(1.0, accuracy(field, 23, 17, margin = 4), "interior tiles (clear of the edges) should find (23, 17)")
    }

    @Test
    fun `stays exact when noise is below the scene texture`() {
        // Fine texture std is about 0.024; sigma 0.02 keeps the signal ahead of the noise.
        val big = scene(320, 240)
        val ref = addNoise(crop(big, 32, 32, 320, 240), sigma = 0.02f, seed = 2)
        val alt = addNoise(crop(big, 32 - 4, 32 + 9, 320, 240), sigma = 0.02f, seed = 3)

        val field = TileAligner().align(ref, alt)

        val acc = accuracy(field, 4, -9)
        assertTrue(acc >= 0.97, "exact accuracy $acc below 97%")
    }

    @Test
    fun `stays within one pixel when noise exceeds the scene texture`() {
        val big = scene(320, 240)
        val ref = addNoise(crop(big, 32, 32, 320, 240), sigma = 0.03f, seed = 2)
        val alt = addNoise(crop(big, 32 - 4, 32 + 9, 320, 240), sigma = 0.03f, seed = 3)

        val field = TileAligner().align(ref, alt)

        var near = 0
        var total = 0
        for (ty in 2 until field.tilesY - 2) {
            for (tx in 2 until field.tilesX - 2) {
                val i = field.index(tx, ty)
                total++
                if (abs(field.dx[i] - 4) <= 1 && abs(field.dy[i] + 9) <= 1) near++
            }
        }
        val acc = near.toDouble() / total
        assertTrue(acc >= 0.99, "within-one-pixel accuracy $acc below 99%")
    }

    @Test
    fun `identical frames give zero displacement`() {
        val ref = crop(scene(160, 120), 0, 0, 160, 120)

        val field = TileAligner().align(ref, ref.copy())

        assertTrue(field.dx.all { it == 0 } && field.dy.all { it == 0 })
    }
}
