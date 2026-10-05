package app.proview.pipeline.finish

import kotlin.math.abs

/** Row-major 3x3 matrix helpers. */
object M3 {
    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { k ->
        val r = k / 3
        val c = k % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun apply(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun inverse(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(det) > 1e-12) { "Singular matrix" }
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    fun lerp(a: DoubleArray, b: DoubleArray, t: Double) = DoubleArray(9) { a[it] + (b[it] - a[it]) * t }

    fun diag(x: Double, y: Double, z: Double) = doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, z)

    val IDENTITY = diag(1.0, 1.0, 1.0)
}

/**
 * The sensor's factory colour calibration, as Camera2 reports it (identical to the DNG tags):
 * ColorMatrix (XYZ -> camera), ForwardMatrix (white-balanced camera -> XYZ D50) and
 * CameraCalibration, each for two reference illuminants.
 */
data class SensorColor(
    val illuminant1Cct: Double,
    val illuminant2Cct: Double,
    val colorMatrix1: DoubleArray,
    val colorMatrix2: DoubleArray,
    val forwardMatrix1: DoubleArray?,
    val forwardMatrix2: DoubleArray?,
    val calibration1: DoubleArray = M3.IDENTITY,
    val calibration2: DoubleArray = M3.IDENTITY,
)

/**
 * "True colour" in the Hasselblad sense: one colorimetric transform from the sensor's own
 * calibration, with no saturation boost or scene-dependent styling (docs/NIGHT_MODE.md, step 7).
 */
object ColorCalibration {
    /** XYZ (D50) -> linear sRGB (D65), Bradford-adapted. */
    val XYZ_D50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427,
    )

    /** CCT of the DNG / EXIF LightSource codes Camera2 uses for SENSOR_REFERENCE_ILLUMINANT. */
    fun illuminantCct(code: Int): Double = when (code) {
        17, 3 -> 2856.0 // Standard A, tungsten
        24 -> 3200.0 // ISO studio tungsten
        15 -> 3450.0 // white fluorescent
        2, 14 -> 4150.0 // fluorescent, cool white fluorescent
        18 -> 4874.0 // standard B
        23 -> 5003.0 // D50
        13 -> 5000.0 // day white fluorescent
        1, 4, 9, 20 -> 5503.0 // daylight, flash, fine weather, D55
        12 -> 6430.0 // daylight fluorescent
        10, 21 -> 6504.0 // cloudy, D65
        19 -> 6774.0 // standard C
        11, 22 -> 7504.0 // shade, D75
        else -> 5003.0
    }

    /** McCamy's approximation of correlated colour temperature from CIE xy. */
    fun cctFromXy(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
    }

    /** Interpolation weight for illuminant 1, on inverse CCT as the DNG spec prescribes. */
    fun weight1(cct: Double, cct1: Double, cct2: Double): Double {
        if (cct1 == cct2) return 1.0
        val t = (1.0 / cct - 1.0 / cct2) / (1.0 / cct1 - 1.0 / cct2)
        return t.coerceIn(0.0, 1.0)
    }

    /**
     * CCT of the scene light, from the camera neutral (the raw RGB of a white object, i.e. the
     * reciprocal of the white-balance gains). Iterates as the DNG SDK does.
     */
    fun sceneCct(s: SensorColor, cameraNeutral: DoubleArray): Double {
        var cct = 5003.0
        repeat(8) {
            val w = weight1(cct, s.illuminant1Cct, s.illuminant2Cct)
            val cm = M3.lerp(s.colorMatrix2, s.colorMatrix1, w)
            val cc = M3.lerp(s.calibration2, s.calibration1, w)
            val xyz = M3.apply(M3.inverse(M3.mul(cc, cm)), cameraNeutral)
            val sum = xyz[0] + xyz[1] + xyz[2]
            if (sum <= 0) return cct
            cct = cctFromXy(xyz[0] / sum, xyz[1] / sum).coerceIn(2000.0, 12000.0)
        }
        return cct
    }

    /**
     * Matrix from white-balanced camera RGB (each channel multiplied by its WB gain) to linear sRGB.
     * Rows are normalised so a white-balanced neutral maps exactly to neutral.
     */
    fun cameraToSrgb(s: SensorColor, wbGains: DoubleArray): DoubleArray {
        val neutral = DoubleArray(3) { 1.0 / wbGains[it] }.let { n -> DoubleArray(3) { n[it] / n[1] } }
        val cct = sceneCct(s, neutral)
        val w = weight1(cct, s.illuminant1Cct, s.illuminant2Cct)
        val cc = M3.lerp(s.calibration2, s.calibration1, w)
        val fm = if (s.forwardMatrix1 != null && s.forwardMatrix2 != null) M3.lerp(s.forwardMatrix2, s.forwardMatrix1, w) else null

        val camToXyz = if (fm != null) {
            // DNG: CameraToXYZ_D50 = FM * D * inverse(CC), D maps the reference neutral to 1.
            val refNeutral = M3.apply(M3.inverse(cc), neutral)
            val d = M3.diag(1.0 / refNeutral[0], 1.0 / refNeutral[1], 1.0 / refNeutral[2])
            M3.mul(fm, M3.mul(d, M3.inverse(cc)))
        } else {
            // No forward matrix: invert the colour matrix and adapt the neutral to D50.
            val cm = M3.lerp(s.colorMatrix2, s.colorMatrix1, w)
            val xyzToCam = M3.mul(cc, cm)
            val camToXyzRaw = M3.inverse(xyzToCam)
            val white = M3.apply(camToXyzRaw, neutral)
            val d50 = doubleArrayOf(0.9642, 1.0, 0.8249)
            M3.mul(M3.diag(d50[0] / white[0], d50[1] / white[1], d50[2] / white[2]), camToXyzRaw)
        }
        // Our pixels are already white-balanced: undo the gains inside the matrix.
        val unbalance = M3.diag(1.0 / wbGains[0], 1.0 / wbGains[1], 1.0 / wbGains[2])
        val m = M3.mul(XYZ_D50_TO_SRGB, M3.mul(camToXyz, unbalance))
        return normaliseRows(m)
    }

    /** Scales rows so (1, 1, 1) maps to (1, 1, 1): neutral stays exactly neutral. */
    fun normaliseRows(m: DoubleArray): DoubleArray = DoubleArray(9) { k ->
        val r = k / 3
        val rowSum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2]
        if (abs(rowSum) < 1e-9) m[k] else m[k] / rowSum
    }
}
