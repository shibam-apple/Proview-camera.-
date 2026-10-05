package app.proview.camera.device

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Camera2 hardware support level, ordered from least to most capable. */
enum class HardwareLevel { LEGACY, EXTERNAL, LIMITED, FULL, LEVEL_3 }

enum class Facing { BACK, FRONT, EXTERNAL }

/**
 * Which capture pipeline a camera can run (SPEC §3).
 * A: RAW burst merge. B: full-resolution YUV burst merge. C: single frame + enhancement.
 */
enum class Tier { A, B, C }

/**
 * Everything the app needs to know about one camera, copied out of CameraCharacteristics
 * into plain values so the rules below are testable on the JVM.
 */
data class CameraInfo(
    val id: String,
    val facing: Facing,
    val hardwareLevel: HardwareLevel,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val supportsBurst: Boolean,
    val isLogicalMultiCamera: Boolean,
    val physicalIds: List<String>,
    /** Lens focal lengths in mm; more than one means an optical zoom range. */
    val focalLengthsMm: List<Float>,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val maxRawSize: Pair<Int, Int>?,
    val maxYuvSize: Pair<Int, Int>?,
    val isoRange: IntRange?,
    val exposureRangeNs: LongRange?,
) {
    /** 35 mm-equivalent focal length of the widest lens, using the diagonal crop factor. */
    val equivalentFocalMm: Int?
        get() {
            val f = focalLengthsMm.minOrNull() ?: return null
            val diagonal = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
            if (diagonal <= 0f) return null
            return (f * FULL_FRAME_DIAGONAL_MM / diagonal).roundToInt()
        }

    val megapixels: Float get() = pixelArrayWidth * pixelArrayHeight / 1_000_000f

    val tier: Tier
        get() {
            val atLeastFull = hardwareLevel >= HardwareLevel.FULL
            if (atLeastFull && supportsRaw && supportsManualSensor) return Tier.A
            val yuvPixels = maxYuvSize?.let { it.first.toLong() * it.second } ?: 0L
            val fullResYuv = yuvPixels >= MIN_BURST_YUV_PIXELS
            if ((atLeastFull || supportsBurst) && fullResYuv) return Tier.B
            return Tier.C
        }

    companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.27f

        /** Below about 8 MP a YUV burst isn't worth merging over a single frame. */
        const val MIN_BURST_YUV_PIXELS = 8_000_000L
    }
}

/** The whole device, as seen by a third-party app. */
data class DeviceReport(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val socModel: String?,
    val cameras: List<CameraInfo>,
) {
    /** Best tier among back cameras: the app's capture path for the main shooting mode. */
    val bestBackTier: Tier?
        get() = cameras.filter { it.facing == Facing.BACK }.minByOrNull { it.tier.ordinal }?.tier

    /** Plain-text report the user can share back to the developers. */
    fun toText(): String = buildString {
        appendLine("Proview device report")
        appendLine("Device: $manufacturer $model")
        appendLine("Android: $androidVersion (SDK $sdkInt)")
        socModel?.let { appendLine("SoC: $it") }
        appendLine("Best back-camera tier: ${bestBackTier ?: "none"}")
        for (c in cameras) {
            appendLine()
            appendLine("Camera ${c.id} (${c.facing.name.lowercase()})")
            appendLine("  tier ${c.tier}, hardware level ${c.hardwareLevel}")
            appendLine("  RAW ${c.supportsRaw}, manual ${c.supportsManualSensor}, burst ${c.supportsBurst}")
            appendLine("  focal ${c.focalLengthsMm.joinToString { "%.2f".format(it) }} mm (${c.equivalentFocalMm ?: "?"} mm eq.)")
            appendLine("  sensor %.2f x %.2f mm, %.1f MP".format(c.sensorWidthMm, c.sensorHeightMm, c.megapixels))
            c.maxRawSize?.let { appendLine("  max RAW ${it.first}x${it.second}") }
            c.maxYuvSize?.let { appendLine("  max YUV ${it.first}x${it.second}") }
            c.isoRange?.let { appendLine("  ISO ${it.first}-${it.last}") }
            c.exposureRangeNs?.let { appendLine("  exposure ${it.first} ns - ${it.last} ns") }
            if (c.isLogicalMultiCamera) appendLine("  logical multi-camera, physical ${c.physicalIds}")
        }
    }
}
