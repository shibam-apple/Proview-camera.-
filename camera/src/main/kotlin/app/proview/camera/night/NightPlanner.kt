package app.proview.camera.night

import app.proview.camera.capture.Meter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pure night-mode decisions (docs/NIGHT_MODE.md, step 1): when to switch night on, how steady
 * the phone is, and how to split the capture into frames. No Android types, so all of it is
 * unit-tested on the JVM.
 */

/** How still the camera is, from the gyroscope. */
enum class Steadiness { TRIPOD, STEADY, HANDHELD }

/** Role of a frame in the burst. */
enum class FrameRole {
    /** Normal-brightness frame the camera's own pipeline turns into the N1 photo. */
    PHOTO,

    /** Underexposed RAW frames that get merged (N2). */
    BASE,

    /** Short RAW frames that keep detail in light sources (N4). */
    SHORT,
}

data class FrameSpec(val role: FrameRole, val iso: Int, val exposureNs: Long)

data class NightPlan(
    val frames: List<FrameSpec>,
    val steadiness: Steadiness,
    val subjectMotion: Boolean,
    /** Preview AE was at its limit, so the plan exposed for darkness rather than the meter. */
    val meterSaturated: Boolean = false,
) {
    val baseFrames: Int get() = frames.count { it.role == FrameRole.BASE }

    /** Sensor time for the whole burst; what the progress ring counts down. */
    val totalNs: Long get() = frames.sumOf { it.exposureNs }

    val totalSeconds: Float get() = totalNs / 1e9f
}

object NightPlanner {
    /**
     * Night switches on when auto-exposure needs at least ISO 800 at 1/25 s (or the equivalent
     * ISO x time; a dim living room), and off again below half of that, so it doesn't flicker.
     */
    const val ENTER_PRODUCT = 800.0 * 40_000_000L
    const val EXIT_PRODUCT = ENTER_PRODUCT * 0.5

    /** The scene must stay dark (or bright) this long before night mode changes state. */
    const val DWELL_NS = 700_000_000L

    /** Total sensor time: 2.5 s handheld (agreed with the user), 6 s on a tripod. */
    const val HANDHELD_BUDGET_NS = 2_500_000_000L
    const val TRIPOD_BUDGET_NS = 6_000_000_000L

    const val MIN_BASE_FRAMES = 6
    const val MAX_BASE_FRAMES = 15

    /** Base frames sit this many stops under the metered exposure to protect light sources. */
    const val BASE_UNDEREXPOSURE_STOPS = 0.7

    /** Short frames sit this many stops under the base frames. */
    const val SHORT_STOPS = 2.0
    const val SHORT_FRAMES = 2

    /**
     * When preview auto-exposure is pinned near its ISO ceiling the meter under-reads (the scene is
     * darker than the preview can expose), so it can't be trusted. Frames then use the longest
     * steady time at this ISO, with no underexposure margin.
     */
    const val SATURATED_ISO = 3200
    const val SATURATION_FRACTION = 0.8

    fun aeSaturated(meter: Meter, isoRange: IntRange): Boolean = meter.iso >= isoRange.last * SATURATION_FRACTION

    /** Longest frame by steadiness; moving subjects cap it at 1/15 s. */
    fun maxFrameNs(steadiness: Steadiness, subjectMotion: Boolean): Long {
        if (subjectMotion) return 66_666_667L
        return when (steadiness) {
            Steadiness.TRIPOD -> 1_000_000_000L
            Steadiness.STEADY -> 333_333_333L
            Steadiness.HANDHELD -> 125_000_000L
        }
    }

    fun plan(
        meter: Meter,
        steadiness: Steadiness,
        subjectMotion: Boolean,
        isoRange: IntRange,
        exposureRangeNs: LongRange,
    ): NightPlan {
        val maxFrame = maxFrameNs(steadiness, subjectMotion).coerceAtMost(exposureRangeNs.last)
        val budget = if (steadiness == Steadiness.TRIPOD && !subjectMotion) TRIPOD_BUDGET_NS else HANDHELD_BUDGET_NS

        fun split(product: Double): Pair<Int, Long> {
            // Longest exposure the motion allows, then make up the rest with gain.
            val exposure = maxFrame.coerceIn(exposureRangeNs.first, exposureRangeNs.last)
            val iso = (product / exposure).roundToInt().coerceIn(isoRange)
            return iso to exposure
        }

        val saturated = aeSaturated(meter, isoRange)
        val product = if (saturated) {
            maxOf(meter.exposureProduct, maxFrame.toDouble() * minOf(isoRange.last, SATURATED_ISO))
        } else {
            meter.exposureProduct
        }
        val underexpose = if (saturated) 0.0 else BASE_UNDEREXPOSURE_STOPS
        val (photoIso, photoNs) = split(product)
        val (baseIso, baseNs) = split(product * 2.0.pow(-underexpose))
        val shortNs = (baseNs / 2.0.pow(SHORT_STOPS)).roundToLong().coerceIn(exposureRangeNs.first, exposureRangeNs.last)

        val frames = floor(budget.toDouble() / baseNs).toInt().coerceIn(MIN_BASE_FRAMES, MAX_BASE_FRAMES)
        val list = buildList {
            add(FrameSpec(FrameRole.PHOTO, photoIso, photoNs))
            repeat(frames) { add(FrameSpec(FrameRole.BASE, baseIso, baseNs)) }
            repeat(SHORT_FRAMES) { add(FrameSpec(FrameRole.SHORT, baseIso, shortNs)) }
        }
        return NightPlan(list, steadiness, subjectMotion, saturated)
    }
}

/** Night on/off with hysteresis and a dwell time, fed one metering sample per preview frame. */
class NightDetector {
    var active: Boolean = false
        private set

    private var pendingSinceNs: Long? = null

    fun update(meter: Meter?, timestampNs: Long): Boolean {
        if (meter == null) return active
        val wantsChange = if (active) meter.exposureProduct < NightPlanner.EXIT_PRODUCT else meter.exposureProduct >= NightPlanner.ENTER_PRODUCT
        if (!wantsChange) {
            pendingSinceNs = null
            return active
        }
        val since = pendingSinceNs ?: timestampNs.also { pendingSinceNs = it }
        if (timestampNs - since >= NightPlanner.DWELL_NS) {
            active = !active
            pendingSinceNs = null
        }
        return active
    }
}

/**
 * Steadiness from gyroscope angular speed (rad/s), smoothed over about half a second.
 * A phone on a tripod reads ~0.002 rad/s of sensor noise; a careful hand ~0.02-0.04.
 */
class SteadinessTracker(private val windowNs: Long = 500_000_000L) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun add(timestampNs: Long, wx: Float, wy: Float, wz: Float) {
        samples.addLast(timestampNs to sqrt(wx * wx + wy * wy + wz * wz))
        while (samples.isNotEmpty() && timestampNs - samples.first().first > windowNs) samples.removeFirst()
    }

    /** Root-mean-square angular speed over the window, rad/s. */
    val rms: Float
        get() = if (samples.isEmpty()) 0f else sqrt(samples.sumOf { (it.second * it.second).toDouble() } / samples.size).toFloat()

    val steadiness: Steadiness
        get() = classify(rms)

    companion object {
        const val TRIPOD_RAD_S = 0.008f
        const val STEADY_RAD_S = 0.05f

        fun classify(rms: Float): Steadiness = when {
            rms < TRIPOD_RAD_S -> Steadiness.TRIPOD
            rms < STEADY_RAD_S -> Steadiness.STEADY
            else -> Steadiness.HANDHELD
        }
    }
}

object SceneMotion {
    const val THUMB_W = 40
    const val THUMB_H = 30

    /**
     * Mean absolute difference between two 40x30 luma thumbnails (0..255 scale), with each
     * thumbnail's mean removed first, so a change in overall brightness isn't read as motion.
     */
    fun difference(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty())
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var sum = 0f
        for (i in a.indices) sum += abs((a[i] - ma) - (b[i] - mb))
        return sum / a.size
    }

    /** Above this, something in the scene is moving (with the phone held still). */
    const val MOTION_THRESHOLD = 4f
}
