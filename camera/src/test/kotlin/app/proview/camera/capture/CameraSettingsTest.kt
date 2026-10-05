package app.proview.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsTest {
    private val isoRange = 100..5699

    @Test
    fun autoModesLetAutoExposureRun() {
        for (mode in listOf(Mode.AUTO, Mode.PROGRAM, Mode.APERTURE)) {
            val plan = CameraSettings(mode = mode, evThirds = -2).exposurePlan(Meter(400, 10_000_000), isoRange)
            assertEquals(ExposurePlan.Auto(-2, false), plan)
        }
    }

    @Test
    fun manualUsesTheChosenValues() {
        val plan = CameraSettings(mode = Mode.MANUAL, isoIndex = 3, shutterIndex = 1).exposurePlan(null, isoRange)
        assertEquals(ExposurePlan.Fixed(800, 8_000_000), plan)
    }

    @Test
    fun manualIsoIsClampedToTheSensor() {
        val plan = CameraSettings(mode = Mode.MANUAL, isoIndex = 5).exposurePlan(null, 100..1600)
        assertEquals(1600, (plan as ExposurePlan.Fixed).iso)
    }

    @Test
    fun shutterPriorityKeepsTheMeteredExposureByMovingIso() {
        // Metered ISO 400 at 1/100 s; shooting at 1/500 s needs 5x the gain: ISO 2000.
        val plan = CameraSettings(mode = Mode.SHUTTER, shutterIndex = 3).exposurePlan(Meter(400, 10_000_000), isoRange)
        assertEquals(ExposurePlan.Fixed(2000, 2_000_000), plan)
    }

    @Test
    fun shutterPriorityAppliesEvBias() {
        // +1 EV doubles the target exposure.
        val plan = CameraSettings(mode = Mode.SHUTTER, shutterIndex = 3, evThirds = 3).exposurePlan(Meter(400, 10_000_000), isoRange)
        assertEquals(4000, (plan as ExposurePlan.Fixed).iso)
    }

    @Test
    fun meterShowsStopsAgainstTheCameraMetering() {
        val s = CameraSettings(mode = Mode.MANUAL, isoIndex = 2, shutterIndex = 0) // ISO 400, 1/60
        val meter = Meter(100, 1_000_000_000L / 60)
        assertEquals(2f, s.meterStops(meter, s.exposurePlan(meter, isoRange)), 0.01f)
    }

    @Test
    fun takingControlSeedsManualFromTheMeter() {
        val s = CameraSettings(mode = Mode.PROGRAM).toManual(Meter(780, 4_100_000)) // ~ISO 800, ~1/244 s
        assertEquals(Mode.MANUAL, s.mode)
        assertEquals(800, Steps.isos[s.isoIndex])
        assertEquals(250, Steps.shutterDenominators[s.shutterIndex])
    }

    @Test
    fun userSetControlsFollowTheMode() {
        val a = CameraSettings(mode = Mode.APERTURE)
        assertTrue(a.isUserSet(Control.APERTURE))
        assertFalse(a.isUserSet(Control.SHUTTER))
        val m = CameraSettings(mode = Mode.MANUAL)
        assertFalse(m.isUserSet(Control.EV))
        assertTrue(m.isUserSet(Control.ISO))
    }

    @Test
    fun indicesAreClamped() {
        val s = CameraSettings().withIndex(Control.EV, 99).withIndex(Control.ISO, -4)
        assertEquals(Steps.EV_MAX_THIRDS, s.evThirds)
        assertEquals(0, s.isoIndex)
    }

    @Test
    fun labels() {
        assertEquals("AF", Steps.focusLabel(0))
        assertEquals("∞", Steps.focusLabel(1))
        assertEquals("3 m", Steps.focusLabel(4))
        assertEquals("+0.7", Steps.evLabel(2))
        assertEquals("−1.0", Steps.evLabel(-3))
        assertEquals("0.0", Steps.evLabel(0))
        assertEquals(250, Steps.reciprocal(4_000_000))
    }
}
