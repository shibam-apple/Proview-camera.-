package app.proview.pipeline.merge

import app.proview.pipeline.AlignmentField
import app.proview.pipeline.Plane
import app.proview.pipeline.TileAligner
import app.proview.pipeline.raw.RawFrame
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.cos

/**
 * Robust burst merge in the Bayer domain (docs/NIGHT_MODE.md, steps 4-5).
 *
 * Frames are added one at a time, so only the reference, the accumulators and the current
 * frame are in memory. Each alternate frame is aligned to the reference on the half-resolution
 * grey image, then blended tile by tile:
 *
 * - Tiles are 32x32 RAW pixels (16x16 grey) with 50% overlap and a raised-cosine window, so
 *   neighbouring tiles cross-fade and no seams appear.
 * - Each tile gets a weight from how well it matches the reference *relative to the noise*:
 *   a static tile only differs by noise and gets weight ~1; a tile with motion differs far more
 *   than noise allows and is pushed towards 0, so moving subjects never ghost.
 *
 * Shifts are even in RAW pixels (whole grey pixels), so the Bayer pattern lines up.
 */
class BurstMerger(
    private val reference: RawFrame,
    private val aligner: TileAligner = TileAligner(),
    /** How hard mismatch is punished; higher rejects motion sooner but also some real detail. */
    private val robustness: Float = 6f,
    /**
     * Difference / expected-noise ratio still treated as "only noise". Over a 256-pixel tile the
     * ratio of two pure-noise frames scatters by ~±9%, and sub-pixel misalignment adds a little.
     */
    private val tolerance: Float = 1.4f,
) {
    private val w = reference.width
    private val h = reference.height
    private val refGray: Plane = reference.grayHalf()
    private val sum = FloatArray(w * h)
    private val weight = FloatArray(w * h)

    var framesMerged: Int = 0
        private set

    /** Mean of the merge weights of each added alternate frame (1 = fully used). */
    val frameWeights = mutableListOf<Float>()

    init {
        accumulate(reference, null, null)
        framesMerged = 1
    }

    fun add(frame: RawFrame) {
        require(frame.width == w && frame.height == h) { "Frame size must match the reference" }
        val gray = frame.grayHalf()
        val field = aligner.align(refGray, gray)
        val mean = accumulate(frame, gray, field)
        frameWeights += mean
        framesMerged++
    }

    /** The merged frame, normalised like the inputs. Accumulators are released. */
    fun result(): RawFrame {
        val out = FloatArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                out[i] = if (weight[i] > 0f) sum[i] / weight[i] else reference.data[i]
            }
        }
        return RawFrame(w, h, out, reference.cfa, reference.noise)
    }

    /**
     * Adds [frame] into the accumulators. For the reference, [gray] and [field] are null and every
     * tile has weight 1. Returns the mean tile weight.
     */
    private fun accumulate(frame: RawFrame, gray: Plane?, field: AlignmentField?): Float {
        val tilesX = (w - TILE + STRIDE - 1) / STRIDE + 1
        val tilesY = (h - TILE + STRIDE - 1) / STRIDE + 1
        val tileWeights = FloatArray(tilesX * tilesY)

        // Tile rows two apart never overlap, so even rows then odd rows can run in parallel.
        for (parity in 0..1) {
            IntStream.range(0, (tilesY - parity + 1) / 2).parallel().forEach { k ->
                val ty = 2 * k + parity
                for (tx in 0 until tilesX) {
                    val x0 = tx * STRIDE
                    val y0 = ty * STRIDE
                    var dx = 0
                    var dy = 0
                    var tw = 1f
                    if (gray != null && field != null) {
                        val t = field.nearestTile((x0 + TILE / 2) / 2, (y0 + TILE / 2) / 2)
                        dx = field.dx[t]
                        dy = field.dy[t]
                        tw = tileWeight(gray, x0 / 2, y0 / 2, dx, dy, frame)
                    }
                    tileWeights[ty * tilesX + tx] = tw
                    if (tw <= MIN_WEIGHT) continue
                    blendTile(frame, x0, y0, 2 * dx, 2 * dy, tw)
                }
            }
        }
        return tileWeights.average().toFloat()
    }

    private fun blendTile(frame: RawFrame, x0: Int, y0: Int, rdx: Int, rdy: Int, tw: Float) {
        for (j in 0 until TILE) {
            val y = y0 + j
            if (y >= h) break
            val wy = WINDOW[j] * tw
            val row = y * w
            for (i in 0 until TILE) {
                val x = x0 + i
                if (x >= w) break
                val wgt = wy * WINDOW[i]
                sum[row + x] += wgt * frame.mirrored(x + rdx, y + rdy)
                weight[row + x] += wgt
            }
        }
    }

    /**
     * Weight in (0, 1] from the mean squared grey difference against the reference, compared with
     * what noise alone would give: two independent noisy samples differ by 2x the grey variance.
     */
    private fun tileWeight(gray: Plane, gx: Int, gy: Int, dx: Int, dy: Int, frame: RawFrame): Float {
        var d2 = 0.0
        var mean = 0.0
        var n = 0
        val size = TILE / 2
        for (j in 0 until size) {
            for (i in 0 until size) {
                val r = refGray.clamped(gx + i, gy + j)
                val a = gray.clamped(gx + i + dx, gy + j + dy)
                val d = (r - a).toDouble()
                d2 += d * d
                mean += r
                n++
            }
        }
        d2 /= n
        mean /= n
        // Grey is the mean of 4 RAW pixels: variance / 4 per frame, two frames in the difference.
        val expected = 2.0 * frame.noise.greenVariance(mean.toFloat()) / 4.0
        val excess = (d2 / expected - tolerance).coerceAtLeast(0.0)
        return (1.0 / (1.0 + robustness * excess)).toFloat()
    }

    companion object {
        const val TILE = 32
        const val STRIDE = TILE / 2
        private const val MIN_WEIGHT = 0.02f

        /** Raised cosine; with 50% overlap neighbouring windows sum to 1. */
        private val WINDOW = FloatArray(TILE) { i -> (0.5 - 0.5 * cos(2 * PI * (i + 0.5) / TILE)).toFloat() }
    }
}
