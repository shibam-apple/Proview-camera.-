package app.proview.pipeline.look

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.stream.IntStream

/**
 * The learned, image-adaptive 3D LUT (training/README.md; Zeng et al., TPAMI 2020), run in plain
 * Kotlin so the app needs no ML runtime.
 *
 * A small CNN looks at a 256x256 thumbnail of the photo (adaptive average pooling, as PyTorch)
 * and predicts one weight per basis LUT; the photo's LUT is identity + sum(w_i * delta_i). The
 * result is an ordinary [Lut3d], so it applies to photos and uploads to the viewfinder shader
 * like any look. Numerically matches the PyTorch model (see AdaptiveLutTest).
 */
class AdaptiveLut private constructor(
    val nBasis: Int,
    val size: Int,
    private val thumb: Int,
    private val convs: List<Conv>,
    private val headIn: Int,
    private val headW: FloatArray,
    private val headB: FloatArray,
    private val deltas: FloatArray,
) {
    private class Conv(val cin: Int, val cout: Int, val k: Int, val stride: Int, val w: FloatArray, val b: FloatArray)

    /** Basis weights for a photo given as ARGB pixels. */
    fun weights(argb: IntArray, width: Int, height: Int): FloatArray {
        var x = thumbnail(argb, width, height)
        var h = thumb
        var w = thumb
        for (c in convs) {
            val (out, oh, ow) = conv(x, h, w, c)
            x = out; h = oh; w = ow
        }
        // Global average pool, then the linear head.
        val c = convs.last().cout
        val pooled = FloatArray(c) { ch ->
            var s = 0.0
            for (i in 0 until h * w) s += x[ch * h * w + i]
            (s / (h * w)).toFloat()
        }
        return FloatArray(nBasis) { k ->
            var s = headB[k]
            for (j in 0 until headIn) s += headW[k * headIn + j] * pooled[j]
            s
        }
    }

    /** The photo's own LUT. */
    fun lutFor(argb: IntArray, width: Int, height: Int): Lut3d = compose(weights(argb, width, height))

    fun compose(wts: FloatArray): Lut3d {
        val n = size * size * size
        val data = FloatArray(n * 3)
        val step = 1f / (size - 1)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val cell = (b * size + g) * size + r
            var vr = r * step
            var vg = g * step
            var vb = b * step
            for (k in 0 until nBasis) {
                val o = (k * n + cell) * 3
                vr += wts[k] * deltas[o]
                vg += wts[k] * deltas[o + 1]
                vb += wts[k] * deltas[o + 2]
            }
            data[cell * 3] = vr.coerceIn(0f, 1f)
            data[cell * 3 + 1] = vg.coerceIn(0f, 1f)
            data[cell * 3 + 2] = vb.coerceIn(0f, 1f)
        }
        return Lut3d(size, data)
    }

    /** Planar (3, thumb, thumb) display sRGB via PyTorch's adaptive_avg_pool2d bins. */
    private fun thumbnail(argb: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(3 * thumb * thumb)
        IntStream.range(0, thumb).parallel().forEach { oy ->
            val y0 = oy * height / thumb
            val y1 = ((oy + 1) * height + thumb - 1) / thumb
            for (ox in 0 until thumb) {
                val x0 = ox * width / thumb
                val x1 = ((ox + 1) * width + thumb - 1) / thumb
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = argb[y * width + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                val k = 255.0 * (y1 - y0) * (x1 - x0)
                val i = oy * thumb + ox
                out[i] = (r / k).toFloat()
                out[thumb * thumb + i] = (g / k).toFloat()
                out[2 * thumb * thumb + i] = (b / k).toFloat()
            }
        }
        return out
    }

    /** Zero-padded (k/2) strided convolution + LeakyReLU(0.2), planar layout. */
    private fun conv(x: FloatArray, h: Int, w: Int, c: Conv): Triple<FloatArray, Int, Int> {
        val pad = c.k / 2
        val oh = (h + 2 * pad - c.k) / c.stride + 1
        val ow = (w + 2 * pad - c.k) / c.stride + 1
        val out = FloatArray(c.cout * oh * ow)
        IntStream.range(0, c.cout).parallel().forEach { o ->
            val base = o * oh * ow
            for (oy in 0 until oh) for (ox in 0 until ow) {
                var s = c.b[o]
                for (ci in 0 until c.cin) {
                    val wBase = (o * c.cin + ci) * c.k * c.k
                    val xBase = ci * h * w
                    for (ky in 0 until c.k) {
                        val iy = oy * c.stride + ky - pad
                        if (iy < 0 || iy >= h) continue
                        for (kx in 0 until c.k) {
                            val ix = ox * c.stride + kx - pad
                            if (ix < 0 || ix >= w) continue
                            s += c.w[wBase + ky * c.k + kx] * x[xBase + iy * w + ix]
                        }
                    }
                }
                out[base + oy * ow + ox] = if (s >= 0f) s else 0.2f * s
            }
        }
        return Triple(out, oh, ow)
    }

    companion object {
        private const val VERSION = 1

        /** Reads the file written by training/export.py. */
        fun read(input: InputStream): AdaptiveLut {
            val d = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "PVAL") { "not a Proview adaptive LUT" }
            fun int(): Int = Integer.reverseBytes(d.readInt())
            fun floats(n: Int): FloatArray {
                val bytes = ByteArray(n * 4).also { d.readFully(it) }
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(n).also { fb.get(it) }
            }
            val version = int()
            require(version == VERSION) { "unsupported model version $version" }
            val nBasis = int()
            val size = int()
            val layers = int()
            val thumb = int()
            val convs = List(layers) {
                val cin = int()
                val cout = int()
                val k = int()
                val stride = int()
                Conv(cin, cout, k, stride, floats(cout * cin * k * k), floats(cout))
            }
            val headIn = int()
            val headOut = int()
            require(headOut == nBasis)
            val headW = floats(headOut * headIn)
            val headB = floats(headOut)
            val deltas = floats(nBasis * size * size * size * 3)
            return AdaptiveLut(nBasis, size, thumb, convs, headIn, headW, headB, deltas)
        }
    }
}
