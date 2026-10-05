package app.proview.pipeline.finish

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorCalibrationTest {
    private val d50 = doubleArrayOf(0.9642, 1.0, 0.8249)

    /** A toy sensor whose RGB is exactly XYZ, calibrated like a real DNG profile. */
    private val xyzSensor = SensorColor(
        illuminant1Cct = 2856.0,
        illuminant2Cct = 6504.0,
        colorMatrix1 = M3.IDENTITY,
        colorMatrix2 = M3.IDENTITY,
        forwardMatrix1 = M3.diag(d50[0], d50[1], d50[2]),
        forwardMatrix2 = M3.diag(d50[0], d50[1], d50[2]),
    )

    @Test
    fun `matrix inverse round-trips`() {
        val m = doubleArrayOf(2.0, 0.5, 0.1, -0.3, 1.5, 0.2, 0.05, -0.4, 1.2)
        val p = M3.mul(m, M3.inverse(m))
        for (k in 0 until 9) assertEquals(M3.IDENTITY[k], p[k], 1e-9)
    }

    @Test
    fun `McCamy gives the right temperature for standard illuminants`() {
        assertEquals(6504.0, ColorCalibration.cctFromXy(0.3127, 0.3290), 30.0)
        assertEquals(2856.0, ColorCalibration.cctFromXy(0.4476, 0.4074), 30.0)
    }

    @Test
    fun `scene temperature is recovered from the camera neutral`() {
        // Under D65 an XYZ sensor sees white as the D65 white point.
        val neutral = doubleArrayOf(0.9505, 1.0, 1.0888)
        assertEquals(6504.0, ColorCalibration.sceneCct(xyzSensor, neutral), 60.0)
    }

    @Test
    fun `white-balanced neutral maps exactly to sRGB white`() {
        val gains = doubleArrayOf(1 / 0.9505, 1.0, 1 / 1.0888)
        val m = ColorCalibration.cameraToSrgb(xyzSensor, gains)
        val white = M3.apply(m, doubleArrayOf(1.0, 1.0, 1.0))
        white.forEach { assertEquals(1.0, it, 1e-9) }
    }

    @Test
    fun `a true colour sensor reproduces sRGB primaries`() {
        // Camera RGB = XYZ(D50) of the sRGB red primary, white-balanced for D50 (gains = 1/D50 white).
        val gains = doubleArrayOf(1 / d50[0], 1.0, 1 / d50[2])
        val m = ColorCalibration.cameraToSrgb(xyzSensor, gains)
        // sRGB red (1,0,0) in XYZ D50 (Bradford): (0.4360, 0.2225, 0.0139)
        val camWb = doubleArrayOf(0.4360 * gains[0], 0.2225 * gains[1], 0.0139 * gains[2])
        val srgb = M3.apply(m, camWb)
        assertEquals(1.0, srgb[0], 0.01)
        assertTrue(abs(srgb[1]) < 0.01 && abs(srgb[2]) < 0.01, "green/blue leak: ${srgb.toList()}")
    }

    @Test
    fun `colour-matrix fallback also keeps neutral neutral`() {
        val noForward = xyzSensor.copy(forwardMatrix1 = null, forwardMatrix2 = null)
        val gains = doubleArrayOf(1 / 0.9505, 1.0, 1 / 1.0888)
        val white = M3.apply(ColorCalibration.cameraToSrgb(noForward, gains), doubleArrayOf(1.0, 1.0, 1.0))
        white.forEach { assertEquals(1.0, it, 1e-9) }
    }
}
