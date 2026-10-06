package app.proview.pipeline.finish

import app.proview.pipeline.raw.Cfa
import app.proview.pipeline.raw.NoiseModel
import app.proview.pipeline.raw.RawFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class FinisherRenditionTest {
    @Test
    fun `rendition path exposes a dim grey frame to a neutral mid tone`() {
        val w = 64
        val h = 48
        val frame = RawFrame(w, h, FloatArray(w * h) { 0.04f }, Cfa.RGGB, NoiseModel.generic(100))
        val out = Finisher.finish(
            frame,
            FinishParams(wbGains = floatArrayOf(1f, 1f, 1f, 1f), cameraToSrgb = M3.IDENTITY, exposureGain = 1f, orientation = 0, style = RenderStyle.DAY),
        )
        val c = out.argb[out.argb.size / 2]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        assertTrue(abs(r - g) <= 3 && abs(g - b) <= 3, "not neutral: $r $g $b")
        assertTrue(g in 95..140, "grey landed at $g")
    }
}
