package app.proview.pipeline

import app.proview.pipeline.raw.B
import app.proview.pipeline.raw.Cfa
import app.proview.pipeline.raw.NoiseModel
import app.proview.pipeline.raw.R
import app.proview.pipeline.raw.RawFrame
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic night bursts for tests: a linear RGB scene with flat patches and texture, mosaiced
 * to RGGB, with Poisson-Gaussian noise from a [NoiseModel] like the phone reports.
 */
object SyntheticBurst {
    /** Noise of a 1/2" sensor around ISO 3200 in normalised units. */
    val NIGHT_NOISE = NoiseModel(FloatArray(4) { 7e-4f }, FloatArray(4) { 2e-6f })

    /** Clean linear scene value for channel [ch] at scene coordinates (x, y). */
    fun scene(x: Int, y: Int, ch: Int): Float {
        val base = 0.08f + 0.05f * sin(x * 0.045f) * cos(y * 0.038f)
        val texture = 0.03f * sin(x * 0.61f + y * 0.37f) * sin(y * 0.53f - x * 0.11f)
        // Flat patch top-left (a wall), coloured patch bottom-right (a sign).
        if (x in 40..120 && y in 40..110) return 0.12f
        val tint = when (ch) {
            R -> if (x > 300 && y > 200) 0.25f else 0f
            B -> if (x > 300 && y > 200) 0.02f else 0.01f
            else -> 0f
        }
        return (base + texture + tint).coerceAtLeast(0.002f)
    }

    /**
     * One RAW frame: the scene seen with camera offset ([ox], [oy]) (even, in RAW pixels), plus an
     * optional bright moving square at ([sqX], [sqY]).
     */
    fun frame(
        width: Int, height: Int, ox: Int, oy: Int, seed: Int,
        noise: NoiseModel = NIGHT_NOISE, sqX: Int = -1, sqY: Int = -1, sqSize: Int = 40,
        clean: Boolean = false,
    ): RawFrame {
        val rnd = Random(seed)
        val cfa = Cfa.RGGB
        val data = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ch = cfa.channelAt(x, y)
                var v = scene(x + ox + 64, y + oy + 64, ch)
                if (sqX >= 0 && x in sqX until sqX + sqSize && y in sqY until sqY + sqSize) v = 0.8f
                if (!clean) v += sqrt(noise.variance(ch, v)) * gaussian(rnd)
                data[y * width + x] = v
            }
        }
        return RawFrame(width, height, data, cfa, noise)
    }

    fun gaussian(rnd: Random): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return (sqrt(-2 * ln(u1)) * cos(2 * Math.PI * u2)).toFloat()
    }

    /** Root-mean-square difference between two frames over the interior (away from borders). */
    fun rmsError(a: RawFrame, b: RawFrame, margin: Int = 48, region: ((Int, Int) -> Boolean)? = null): Double {
        var s = 0.0
        var n = 0
        for (y in margin until a.height - margin) {
            for (x in margin until a.width - margin) {
                if (region != null && !region(x, y)) continue
                val d = (a[x, y] - b[x, y]).toDouble()
                s += d * d
                n++
            }
        }
        return sqrt(s / n)
    }
}
