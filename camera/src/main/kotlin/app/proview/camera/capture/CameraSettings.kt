package app.proview.camera.capture

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Shooting modes on the design's mode dial (UI_SPEC §2, "Mode behaviour"). */
enum class Mode(val letter: String, val title: String, val subtitle: String, val dialLabel: String) {
    AUTO("A", "Auto", "", "Auto"),
    PROGRAM("P", "Program", "Camera decides", "P"),
    APERTURE("A", "Aperture", "You set blur", "A"),
    SHUTTER("S", "Shutter", "You set motion", "S"),
    MANUAL("M", "Manual", "You set both", "M");

    val isPro: Boolean get() = this != AUTO
}

enum class WhiteBalance(val label: String) { AUTO("Auto"), SUN("Sun"), CLOUD("Cloud"), WARM("Warm") }

/** The values a pro control edits. */
enum class Control { APERTURE, SHUTTER, ISO, WB, FOCUS, EV }

/** The step tables from the design. */
object Steps {
    val apertures = listOf("1.4", "1.8", "2.8", "4", "5.6", "8")
    val shutterDenominators = listOf(60, 125, 250, 500, 1000)
    val isos = listOf(100, 200, 400, 800, 1600, 3200)
    val whiteBalances = WhiteBalance.entries

    /** Focus distances in metres: null is autofocus, infinity is infinity focus. */
    val focusMetres: List<Float?> = listOf(null, Float.POSITIVE_INFINITY, 10f, 5f, 3f, 2f, 1f)

    const val EV_MIN_THIRDS = -9
    const val EV_MAX_THIRDS = 9

    fun focusLabel(index: Int): String = when (val m = focusMetres[index]) {
        null -> "AF"
        Float.POSITIVE_INFINITY -> "∞"
        else -> "${m.roundToInt()} m"
    }

    fun evLabel(thirds: Int): String = signedStops(thirds / 3f)

    fun signedStops(stops: Float): String {
        if (abs(stops) < 0.05f) return "0.0"
        return (if (stops > 0) "+" else "−") + String.format(java.util.Locale.US, "%.1f", abs(stops))
    }

    fun shutterNs(index: Int): Long = 1_000_000_000L / shutterDenominators[index]

    /** Shutter speed as a reciprocal ("250" for 1/250 s). */
    fun reciprocal(exposureNs: Long): Int = maxOf(1, (1e9 / exposureNs).roundToInt())
}

/** What the camera's own auto-exposure chose for the current scene. */
data class Meter(val iso: Int, val exposureNs: Long) {
    val exposureProduct: Double get() = iso.toDouble() * exposureNs
}

/** How the sensor should be driven for a set of settings. */
sealed interface ExposurePlan {
    /** Let auto-exposure run, biased by [evThirds]; [locked] holds the current exposure. */
    data class Auto(val evThirds: Int, val locked: Boolean) : ExposurePlan

    /** Drive the sensor directly. */
    data class Fixed(val iso: Int, val exposureNs: Long) : ExposurePlan
}

/**
 * Everything the user can set. Indices point into [Steps]. Aperture is simulated
 * (phone lenses have a fixed aperture), so it never changes the exposure.
 */
data class CameraSettings(
    val mode: Mode = Mode.AUTO,
    val apertureIndex: Int = 1,
    val shutterIndex: Int = 2,
    val isoIndex: Int = 0,
    val wbIndex: Int = 0,
    val focusIndex: Int = 0,
    val evThirds: Int = 0,
    val aeAfLocked: Boolean = false,
) {
    val whiteBalance: WhiteBalance get() = Steps.whiteBalances[wbIndex]

    /** True when the user sets [c] in this mode; false when the camera chooses it. */
    fun isUserSet(c: Control): Boolean = when (c) {
        Control.APERTURE -> mode == Mode.APERTURE || mode == Mode.MANUAL
        Control.SHUTTER -> mode == Mode.SHUTTER || mode == Mode.MANUAL
        Control.ISO -> mode == Mode.MANUAL
        Control.EV -> mode != Mode.MANUAL
        Control.WB, Control.FOCUS -> true
    }

    fun stepCount(c: Control): Int = when (c) {
        Control.APERTURE -> Steps.apertures.size
        Control.SHUTTER -> Steps.shutterDenominators.size
        Control.ISO -> Steps.isos.size
        Control.WB -> Steps.whiteBalances.size
        Control.FOCUS -> Steps.focusMetres.size
        Control.EV -> Steps.EV_MAX_THIRDS - Steps.EV_MIN_THIRDS + 1
    }

    fun index(c: Control): Int = when (c) {
        Control.APERTURE -> apertureIndex
        Control.SHUTTER -> shutterIndex
        Control.ISO -> isoIndex
        Control.WB -> wbIndex
        Control.FOCUS -> focusIndex
        Control.EV -> evThirds - Steps.EV_MIN_THIRDS
    }

    fun withIndex(c: Control, i: Int): CameraSettings {
        val n = i.coerceIn(0, stepCount(c) - 1)
        return when (c) {
            Control.APERTURE -> copy(apertureIndex = n)
            Control.SHUTTER -> copy(shutterIndex = n)
            Control.ISO -> copy(isoIndex = n)
            Control.WB -> copy(wbIndex = n)
            Control.FOCUS -> copy(focusIndex = n)
            Control.EV -> copy(evThirds = n + Steps.EV_MIN_THIRDS)
        }
    }

    /**
     * Switch to Manual, seeded with what auto-exposure had chosen, so taking control of an
     * auto value doesn't change the picture (UI_SPEC §2).
     */
    fun toManual(meter: Meter?): CameraSettings {
        if (meter == null) return copy(mode = Mode.MANUAL)
        return copy(
            mode = Mode.MANUAL,
            isoIndex = nearestLog(Steps.isos, meter.iso.toDouble()),
            shutterIndex = nearestLog(Steps.shutterDenominators, 1e9 / meter.exposureNs),
            aeAfLocked = false,
        )
    }

    fun exposurePlan(meter: Meter?, isoRange: IntRange): ExposurePlan = when (mode) {
        Mode.MANUAL -> ExposurePlan.Fixed(Steps.isos[isoIndex].coerceIn(isoRange), Steps.shutterNs(shutterIndex))
        Mode.SHUTTER -> {
            // Shutter priority: keep the metered exposure (plus EV bias) by moving ISO.
            val exposureNs = Steps.shutterNs(shutterIndex)
            val target = (meter?.exposureProduct ?: (400.0 * 10_000_000L)) * Math.pow(2.0, evThirds / 3.0)
            ExposurePlan.Fixed((target / exposureNs).roundToInt().coerceIn(isoRange), exposureNs)
        }
        else -> ExposurePlan.Auto(evThirds, aeAfLocked)
    }

    /** Exposure relative to the camera's metering, in stops, for the EV meter (−3…+3). */
    fun meterStops(meter: Meter?, plan: ExposurePlan): Float {
        val stops = when (plan) {
            is ExposurePlan.Auto -> plan.evThirds / 3f
            is ExposurePlan.Fixed -> {
                if (meter == null) 0f
                else (ln(plan.iso.toDouble() * plan.exposureNs / meter.exposureProduct) / ln(2.0)).toFloat()
            }
        }
        return stops.coerceIn(-3f, 3f)
    }

    companion object {
        /** Index of the value nearest to [v] on a log scale (stops, not linear distance). */
        fun nearestLog(values: List<Int>, v: Double): Int =
            values.indices.minBy { abs(ln(values[it] / v)) }
    }
}
