package app.proview.camera.device

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraInfoTest {

    /** Roughly a 48 MP 1/2" main camera, like the IMX586 in the OnePlus 7T. */
    private fun mainCamera(
        level: HardwareLevel = HardwareLevel.LEVEL_3,
        raw: Boolean = true,
        manual: Boolean = true,
        burst: Boolean = true,
        yuv: Pair<Int, Int>? = 4000 to 3000,
    ) = CameraInfo(
        id = "0", facing = Facing.BACK, hardwareLevel = level,
        supportsRaw = raw, supportsManualSensor = manual, supportsBurst = burst,
        isLogicalMultiCamera = false, physicalIds = emptyList(),
        focalLengthsMm = listOf(4.76f), sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
        pixelArrayWidth = 8000, pixelArrayHeight = 6000,
        maxRawSize = 8000 to 6000, maxYuvSize = yuv,
        isoRange = 100..6400, exposureRangeNs = 10_000L..30_000_000_000L,
    )

    @Test
    fun fullCameraWithRawIsTierA() {
        assertEquals(Tier.A, mainCamera().tier)
        assertEquals(Tier.A, mainCamera(level = HardwareLevel.FULL).tier)
    }

    @Test
    fun rawWithoutManualControlFallsBackToYuvBurst() {
        assertEquals(Tier.B, mainCamera(manual = false).tier)
    }

    @Test
    fun limitedCameraWithBurstAndFullResYuvIsTierB() {
        assertEquals(Tier.B, mainCamera(level = HardwareLevel.LIMITED, raw = false).tier)
    }

    @Test
    fun lowResolutionOrLegacyIsTierC() {
        assertEquals(Tier.C, mainCamera(level = HardwareLevel.LIMITED, raw = false, yuv = 1920 to 1080).tier)
        assertEquals(Tier.C, mainCamera(level = HardwareLevel.LEGACY, raw = false, burst = false).tier)
    }

    @Test
    fun equivalentFocalLengthUsesSensorDiagonal() {
        // 4.76 mm on an 8 mm-diagonal sensor is about 26 mm equivalent.
        assertEquals(26, mainCamera().equivalentFocalMm)
    }

    @Test
    fun reportPicksBestBackCameraTier() {
        val tele = mainCamera(level = HardwareLevel.LIMITED, raw = false).copy(id = "2")
        val front = mainCamera().copy(id = "1", facing = Facing.FRONT)
        val report = DeviceReport("OnePlus", "HD1900", "12", 31, "SM8150", listOf(tele, front))

        assertEquals(Tier.B, report.bestBackTier)
    }
}
