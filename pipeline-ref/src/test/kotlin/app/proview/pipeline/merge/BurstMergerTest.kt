package app.proview.pipeline.merge

import app.proview.pipeline.SyntheticBurst
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class BurstMergerTest {
    private val w = 384
    private val h = 288

    /** Hand-shake offsets in RAW pixels (even, so the Bayer phase is kept). */
    private val shakes = listOf(0 to 0, 6 to -4, -8 to 2, 4 to 10, -2 to -6, 10 to 4, -6 to -8, 2 to 6)

    @Test
    fun `merging N static frames cuts noise by close to root N`() {
        val clean = SyntheticBurst.frame(w, h, 0, 0, seed = 0, clean = true)
        val frames = shakes.mapIndexed { k, (ox, oy) -> SyntheticBurst.frame(w, h, ox, oy, seed = 100 + k) }

        val merger = BurstMerger(frames[0])
        frames.drop(1).forEach { merger.add(it) }
        val merged = merger.result()

        val single = SyntheticBurst.rmsError(frames[0], clean)
        val after = SyntheticBurst.rmsError(merged, clean)
        val ideal = single / sqrt(frames.size.toDouble())
        println("noise: single=%.5f merged=%.5f ideal=%.5f weights=%s".format(single, after, ideal, merger.frameWeights))
        assertTrue(after <= ideal * 1.35, "merged noise $after should be within 35% of the ideal $ideal")
    }

    @Test
    fun `a subject that moves between frames leaves no ghost`() {
        val clean = SyntheticBurst.frame(w, h, 0, 0, seed = 0, clean = true)
        val ref = SyntheticBurst.frame(w, h, 0, 0, seed = 200)
        // A bright square (a passing car light) in every alternate frame, never where it was before.
        val alts = (1..6).map { k ->
            val (ox, oy) = shakes[k]
            SyntheticBurst.frame(w, h, ox, oy, seed = 200 + k, sqX = 60 + k * 40, sqY = 150, sqSize = 36)
        }

        val merger = BurstMerger(ref)
        alts.forEach { merger.add(it) }
        val merged = merger.result()

        // Where the square passed, the merge must still look like the empty scene.
        val path: (Int, Int) -> Boolean = { x, y -> x in 90..340 && y in 140..200 }
        val err = SyntheticBurst.rmsError(merged, clean, region = path)
        val single = SyntheticBurst.rmsError(ref, clean, region = path)
        println("ghost region: merged rms=%.5f single-frame rms=%.5f".format(err, single))
        // A ghost would add ~0.7/7 = 0.1 of error; noise alone is ~0.01.
        assertTrue(err < single * 1.2, "ghosting: rms $err vs single-frame noise $single")
    }

    @Test
    fun `a reference on its own comes back unchanged`() {
        val ref = SyntheticBurst.frame(w, h, 0, 0, seed = 7)
        val merged = BurstMerger(ref).result()
        assertTrue(SyntheticBurst.rmsError(merged, ref, margin = 0) < 1e-6)
    }
}
