package app.proview.camera.night

import app.proview.camera.capture.Meter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightPlannerTest {
    // OnePlus 7T Pro main camera ranges from the device report.
    private val isoRange = 100..5699
    private val exposureRange = 10_783L..31_617_300_000L

    /** A dim street: auto-exposure picks ISO 3200 at 1/15 s. */
    private val dimStreet = Meter(3200, 66_666_667L)

    @Test
    fun handheldUsesShortFramesAndFillsTheBudget() {
        val plan = NightPlanner.plan(dimStreet, Steadiness.HANDHELD, subjectMotion = false, isoRange, exposureRange)

        val base = plan.frames.filter { it.role == FrameRole.BASE }
        assertEquals(125_000_000L, base.first().exposureNs)
        assertEquals(15, plan.baseFrames) // 2.5 s / 0.125 s = 20, capped at 15
        assertTrue(plan.totalSeconds < 3f)
    }

    @Test
    fun steadyHandsUseLongerFramesWithinTwoAndAHalfSeconds() {
        val plan = NightPlanner.plan(dimStreet, Steadiness.STEADY, subjectMotion = false, isoRange, exposureRange)

        val base = plan.frames.filter { it.role == FrameRole.BASE }
        assertEquals(333_333_333L, base.first().exposureNs)
        assertEquals(7, plan.baseFrames)
        val baseSeconds = base.sumOf { it.exposureNs } / 1e9
        assertTrue("base frames take $baseSeconds s", baseSeconds <= 2.5)
    }

    @Test
    fun tripodGetsOneSecondFramesAndSixSeconds() {
        val plan = NightPlanner.plan(dimStreet, Steadiness.TRIPOD, subjectMotion = false, isoRange, exposureRange)

        assertEquals(1_000_000_000L, plan.frames.first { it.role == FrameRole.BASE }.exposureNs)
        assertEquals(6, plan.baseFrames)
    }

    @Test
    fun movingSubjectsCapFramesAtOneFifteenth() {
        val plan = NightPlanner.plan(dimStreet, Steadiness.TRIPOD, subjectMotion = true, isoRange, exposureRange)

        assertTrue(plan.frames.all { it.exposureNs <= 66_666_667L })
    }

    @Test
    fun baseFramesSitBelowTheMeteredExposure() {
        val plan = NightPlanner.plan(dimStreet, Steadiness.STEADY, subjectMotion = false, isoRange, exposureRange)

        val photo = plan.frames.first { it.role == FrameRole.PHOTO }
        val base = plan.frames.first { it.role == FrameRole.BASE }
        val stops = Math.log(photo.iso.toDouble() * photo.exposureNs / (base.iso.toDouble() * base.exposureNs)) / Math.log(2.0)
        assertEquals(NightPlanner.BASE_UNDEREXPOSURE_STOPS, stops, 0.05)
        // The photo frame keeps the metered brightness.
        assertEquals(dimStreet.exposureProduct, photo.iso.toDouble() * photo.exposureNs, dimStreet.exposureProduct * 0.01)
    }

    @Test
    fun shortFramesAreTwoStopsUnderTheBase() {
        val plan = NightPlanner.plan(dimStreet, Steadiness.STEADY, subjectMotion = false, isoRange, exposureRange)

        val base = plan.frames.first { it.role == FrameRole.BASE }
        val short = plan.frames.filter { it.role == FrameRole.SHORT }
        assertEquals(NightPlanner.SHORT_FRAMES, short.size)
        assertEquals(base.exposureNs / 4.0, short.first().exposureNs.toDouble(), 2.0)
    }

    @Test
    fun isoIsClampedToTheSensor() {
        val veryDark = Meter(5699, 125_000_000L)
        val plan = NightPlanner.plan(veryDark, Steadiness.HANDHELD, subjectMotion = false, isoRange, exposureRange)

        assertTrue(plan.frames.all { it.iso in isoRange })
    }

    @Test
    fun detectorNeedsADarkSceneForTheDwellTime() {
        val d = NightDetector()
        val dark = Meter(3200, 66_666_667L)

        assertFalse(d.update(dark, 0))
        assertFalse(d.update(dark, 500_000_000))
        assertTrue(d.update(dark, 700_000_000))
    }

    @Test
    fun aSteadyDimRoomTurnsNightOn() {
        // The same meter value every tick (a converged AE in a still scene) must still trigger.
        val d = NightDetector()
        val dimRoom = Meter(1000, 40_000_000L)
        var on = false
        for (t in 0..10) on = d.update(dimRoom, t * 150_000_000L)
        assertTrue(on)
    }

    @Test
    fun anOrdinaryIndoorSceneStaysDay() {
        val d = NightDetector()
        val office = Meter(400, 20_000_000L)
        var on = false
        for (t in 0..20) on = d.update(office, t * 150_000_000L)
        assertFalse(on)
    }

    @Test
    fun detectorHasHysteresis() {
        val d = NightDetector()
        val dark = Meter(3200, 66_666_667L)
        d.update(dark, 0)
        d.update(dark, 1_000_000_000)

        // Brighter than the switch-on level, but not below the switch-off level.
        val dusk = Meter(800, 25_000_000L)
        d.update(dusk, 2_000_000_000)
        assertTrue(d.update(dusk, 3_500_000_000))

        // Clearly bright: switches off after the dwell time.
        val lit = Meter(400, 10_000_000L)
        d.update(lit, 4_000_000_000)
        assertFalse(d.update(lit, 5_000_000_000))
    }

    @Test
    fun detectorResetsWhenTheSceneFlickers() {
        val d = NightDetector()
        val dark = Meter(3200, 66_666_667L)
        val lit = Meter(400, 10_000_000L)

        d.update(dark, 0)
        d.update(lit, 600_000_000)
        assertFalse(d.update(dark, 1_200_000_000))
    }

    @Test
    fun steadinessClassification() {
        assertEquals(Steadiness.TRIPOD, SteadinessTracker.classify(0.002f))
        assertEquals(Steadiness.STEADY, SteadinessTracker.classify(0.03f))
        assertEquals(Steadiness.HANDHELD, SteadinessTracker.classify(0.2f))

        val t = SteadinessTracker()
        repeat(50) { t.add(it * 10_000_000L, 0.001f, 0.001f, 0f) }
        assertEquals(Steadiness.TRIPOD, t.steadiness)
    }

    @Test
    fun sceneMotionIgnoresBrightnessChanges() {
        val a = FloatArray(SceneMotion.THUMB_W * SceneMotion.THUMB_H) { (it % 40) * 3f }
        val brighter = FloatArray(a.size) { a[it] + 30f }
        assertEquals(0f, SceneMotion.difference(a, brighter), 1e-3f)

        val shifted = FloatArray(a.size) { ((it + 5) % 40) * 3f }
        assertTrue(SceneMotion.difference(a, shifted) > SceneMotion.MOTION_THRESHOLD)
    }
}
