package app.proview.pipeline.look

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Kotlin model must reproduce PyTorch (training/tools/make_parity.py) on the same input. */
class AdaptiveLutTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/adaptive/$name")!!.readBytes()

    @Test
    fun `matches the PyTorch model`() {
        val model = AdaptiveLut.read(resource("tiny.lut").inputStream())
        val exp = ByteBuffer.wrap(resource("expected.bin")).order(ByteOrder.LITTLE_ENDIAN)
        val h = exp.int
        val w = exp.int
        val inBuf = ByteBuffer.wrap(resource("input.argb")).order(ByteOrder.LITTLE_ENDIAN)
        val argb = IntArray(w * h) { inBuf.int }

        val weights = model.weights(argb, w, h)
        for (k in weights.indices) {
            val e = exp.float
            assertTrue(abs(weights[k] - e) < 1e-3f * maxOf(1f, abs(e)), "weight $k: ${weights[k]} vs $e")
        }
        val lut = model.compose(weights)
        val px = FloatArray(3)
        var worst = 0f
        for (i in 0 until w * h) {
            val c = argb[i]
            lut.apply(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, px)
            for (ch in 0..2) worst = maxOf(worst, abs(px[ch] - exp.float))
        }
        assertTrue(worst < 1e-3f, "output differs from PyTorch by $worst")
    }

    @Test
    fun `untrained-style zero weights give the identity`() {
        val model = AdaptiveLut.read(resource("tiny.lut").inputStream())
        val lut = model.compose(FloatArray(model.nBasis))
        val px = FloatArray(3)
        lut.apply(0.3f, 0.6f, 0.9f, px)
        assertTrue(abs(px[0] - 0.3f) < 1e-4f && abs(px[1] - 0.6f) < 1e-4f && abs(px[2] - 0.9f) < 1e-4f)
    }
}
