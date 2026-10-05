package app.proview.pipeline.finish

import app.proview.pipeline.raw.B
import app.proview.pipeline.raw.R
import app.proview.pipeline.raw.RawFrame
import java.util.stream.IntStream

/** Three full-resolution colour planes. */
class Rgb(val width: Int, val height: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray)

/**
 * Malvar-He-Cutler gradient-corrected linear demosaicing ("High-quality linear interpolation
 * for demosaicing of Bayer-patterned color images", ICASSP 2004). Bilinear interpolation plus a
 * Laplacian correction from the known channel: sharp edges, little zippering, cheap enough for
 * a phone CPU.
 */
object Demosaic {
    fun mhc(frame: RawFrame): Rgb {
        val w = frame.width
        val h = frame.height
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val cfa = frame.cfa
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = frame.data[i]
                when (val ch = cfa.channelAt(x, y)) {
                    R, B -> {
                        val gv = greenAtRb(frame, x, y, c)
                        val other = otherAtRb(frame, x, y, c)
                        g[i] = gv
                        if (ch == R) { r[i] = c; b[i] = other } else { b[i] = c; r[i] = other }
                    }
                    else -> {
                        g[i] = c
                        val horizontal = horizontalNeighbours(frame, x, y, c)
                        val vertical = verticalNeighbours(frame, x, y, c)
                        // Which colour sits left/right of this green decides the kernel.
                        if (cfa.channelAt(x + 1, y) == R) {
                            r[i] = horizontal; b[i] = vertical
                        } else {
                            b[i] = horizontal; r[i] = vertical
                        }
                    }
                }
            }
        }
        return Rgb(w, h, r, g, b)
    }

    private fun greenAtRb(f: RawFrame, x: Int, y: Int, c: Float): Float =
        (4f * c + 2f * (f.mirrored(x - 1, y) + f.mirrored(x + 1, y) + f.mirrored(x, y - 1) + f.mirrored(x, y + 1)) -
            (f.mirrored(x - 2, y) + f.mirrored(x + 2, y) + f.mirrored(x, y - 2) + f.mirrored(x, y + 2))) / 8f

    private fun otherAtRb(f: RawFrame, x: Int, y: Int, c: Float): Float =
        (6f * c + 2f * (f.mirrored(x - 1, y - 1) + f.mirrored(x + 1, y - 1) + f.mirrored(x - 1, y + 1) + f.mirrored(x + 1, y + 1)) -
            1.5f * (f.mirrored(x - 2, y) + f.mirrored(x + 2, y) + f.mirrored(x, y - 2) + f.mirrored(x, y + 2))) / 8f

    /** Colour whose samples are left and right of a green pixel. */
    private fun horizontalNeighbours(f: RawFrame, x: Int, y: Int, c: Float): Float =
        (5f * c + 4f * (f.mirrored(x - 1, y) + f.mirrored(x + 1, y)) -
            (f.mirrored(x - 2, y) + f.mirrored(x + 2, y) + f.mirrored(x - 1, y - 1) + f.mirrored(x + 1, y - 1) +
                f.mirrored(x - 1, y + 1) + f.mirrored(x + 1, y + 1)) +
            0.5f * (f.mirrored(x, y - 2) + f.mirrored(x, y + 2))) / 8f

    /** Colour whose samples are above and below a green pixel. */
    private fun verticalNeighbours(f: RawFrame, x: Int, y: Int, c: Float): Float =
        (5f * c + 4f * (f.mirrored(x, y - 1) + f.mirrored(x, y + 1)) -
            (f.mirrored(x, y - 2) + f.mirrored(x, y + 2) + f.mirrored(x - 1, y - 1) + f.mirrored(x + 1, y - 1) +
                f.mirrored(x - 1, y + 1) + f.mirrored(x + 1, y + 1)) +
            0.5f * (f.mirrored(x - 2, y) + f.mirrored(x + 2, y))) / 8f
}
