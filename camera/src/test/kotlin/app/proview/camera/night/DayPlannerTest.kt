package app.proview.camera.night

import app.proview.camera.capture.Meter
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPlannerTest {
    private val isoRange = 100..6400
    private val exposureRange = 50_000L..500_000_000L

    @Test
    fun `bright scene keeps the metered shutter and underexposes half a stop`() {
        val meter = Meter(100, 2_000_000L) // ISO 100, 1/500 s
        val plan = DayPlanner.plan(meter, Steadiness.HANDHELD, false, isoRange, exposureRange)
        val base = plan.frames.filter { it.role == FrameRole.BASE }
        assertEquals(DayPlanner.FRAMES, base.size)
        assertEquals(1, plan.frames.count { it.role == FrameRole.PHOTO })
        val stops = ln(meter.exposureProduct / (base[0].iso.toDouble() * base[0].exposureNs)) / ln(2.0)
        // ISO can't go below 100 here, so the half stop comes off the product within ISO rounding.
        assertTrue("under by $stops stops", stops >= 0.0 && stops <= 0.6)
    }

    @Test
    fun `dim indoor scene caps the shutter for handheld and raises ISO`() {
        val meter = Meter(400, 100_000_000L) // ISO 400, 1/10 s
        val plan = DayPlanner.plan(meter, Steadiness.HANDHELD, false, isoRange, exposureRange)
        val base = plan.frames.first { it.role == FrameRole.BASE }
        assertEquals(DayPlanner.MAX_FRAME_NS, base.exposureNs)
        val stops = ln(meter.exposureProduct / (base.iso.toDouble() * base.exposureNs)) / ln(2.0)
        assertTrue("under by $stops stops", abs(stops - DayPlanner.UNDEREXPOSE_STOPS) < 0.05)
    }

    @Test
    fun `moving subject gets a faster shutter`() {
        val meter = Meter(200, 20_000_000L)
        val plan = DayPlanner.plan(meter, Steadiness.HANDHELD, true, isoRange, exposureRange)
        assertTrue(plan.frames.filter { it.role == FrameRole.BASE }.all { it.exposureNs <= DayPlanner.MAX_FRAME_MOVING_NS })
    }
}
