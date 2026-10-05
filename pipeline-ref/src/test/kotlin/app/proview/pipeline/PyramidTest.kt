package app.proview.pipeline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PyramidTest {

    @Test
    fun `levels shrink by their factors`() {
        val pyr = Pyramid.build(Plane(640, 480), listOf(2, 4, 4))

        assertEquals(listOf(640 to 480, 320 to 240, 80 to 60, 20 to 15), pyr.levels.map { it.width to it.height })
        assertEquals(32, pyr.scaleOf(3))
    }

    @Test
    fun `blur preserves a flat field`() {
        val flat = Plane.create(32, 32) { _, _ -> 0.42f }

        val blurred = Pyramid.binomialBlur(flat)

        assertTrue(blurred.data.all { abs(it - 0.42f) < 1e-6f })
    }

    @Test
    fun `downsampling suppresses a pixel-level checkerboard`() {
        val checker = Plane.create(64, 64) { x, y -> if ((x + y) % 2 == 0) 1f else 0f }

        val down = Pyramid.downsample(checker, 2)

        // A Nyquist-frequency pattern must average out to mid-grey instead of aliasing.
        // The outer samples are skipped: edge replication there breaks the pattern.
        for (y in 2 until down.height - 2) {
            for (x in 2 until down.width - 2) {
                assertTrue(abs(down[x, y] - 0.5f) < 0.02f, "sample ($x, $y) = ${down[x, y]}")
            }
        }
    }
}
