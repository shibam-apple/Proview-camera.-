package app.proview.camera.night

import app.proview.camera.capture.Meter
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The RAW day burst (docs/ALGORITHM.md, "Day path"): a few frames at the metered brightness less
 * [UNDEREXPOSE_STOPS], so highlights keep headroom for the Rendition's soft shoulder; merging them
 * cuts noise ~2x. A normal JPEG frame from the phone's own pipeline is the fallback photo.
 * Shutter is capped so a handheld shot stays sharp, with ISO making up the rest.
 */
object DayPlanner {
    const val FRAMES = 4
    const val UNDEREXPOSE_STOPS = 0.5

    /** Handheld-safe frame time; moving subjects get a faster cap. */
    const val MAX_FRAME_NS = 33_333_333L
    const val MAX_FRAME_MOVING_NS = 8_333_333L

    fun plan(
        meter: Meter,
        steadiness: Steadiness,
        subjectMotion: Boolean,
        isoRange: IntRange,
        exposureRangeNs: LongRange,
    ): NightPlan {
        val cap = (if (subjectMotion) MAX_FRAME_MOVING_NS else MAX_FRAME_NS).coerceIn(exposureRangeNs.first, exposureRangeNs.last)
        val product = meter.exposureProduct * 2.0.pow(-UNDEREXPOSE_STOPS)
        val exposure = minOf(meter.exposureNs, cap).coerceIn(exposureRangeNs.first, exposureRangeNs.last)
        val iso = (product / exposure).roundToInt().coerceIn(isoRange)
        val frames = buildList {
            add(FrameSpec(FrameRole.PHOTO, meter.iso.coerceIn(isoRange), meter.exposureNs.coerceIn(exposureRangeNs.first, exposureRangeNs.last)))
            repeat(FRAMES) { add(FrameSpec(FrameRole.BASE, iso, exposure)) }
        }
        return NightPlan(frames, steadiness, subjectMotion)
    }
}
