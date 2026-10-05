package app.proview.pipeline.raw

import app.proview.pipeline.Plane

/** Colour of each pixel in a 2x2 Bayer cell, listed as (0,0), (1,0), (0,1), (1,1). */
enum class Cfa(val layout: IntArray) {
    RGGB(intArrayOf(R, G1, G2, B)),
    GRBG(intArrayOf(G1, R, B, G2)),
    GBRG(intArrayOf(G1, B, R, G2)),
    BGGR(intArrayOf(B, G1, G2, R));

    /** Channel (R, G1, G2 or B) of the pixel at (x, y). */
    fun channelAt(x: Int, y: Int): Int = layout[(y and 1) * 2 + (x and 1)]
}

/** The four CFA channels in Camera2 / DNG order: R, G (even rows), G (odd rows), B. */
const val R = 0
const val G1 = 1
const val G2 = 2
const val B = 3

/**
 * Sensor noise as Camera2 reports it (SENSOR_NOISE_PROFILE): for a normalised signal x in
 * [0, 1], variance = scale * x + offset, per CFA channel (R, G1, G2, B).
 */
class NoiseModel(val scale: FloatArray, val offset: FloatArray) {
    init {
        require(scale.size == 4 && offset.size == 4)
    }

    fun variance(channel: Int, x: Float): Float = maxOf(1e-12f, scale[channel] * maxOf(0f, x) + offset[channel])

    /** Green-channel variance, used for the luma-like alignment image. */
    fun greenVariance(x: Float): Float = 0.5f * (variance(G1, x) + variance(G2, x))

    companion object {
        /** Fallback when the HAL reports nothing: roughly a 1/2" sensor at ISO 1600. */
        fun generic(iso: Int): NoiseModel {
            val k = iso / 100f
            val s = 2.2e-5f * k
            val o = 1.0e-8f * k * k
            return NoiseModel(FloatArray(4) { s }, FloatArray(4) { o })
        }
    }
}

/**
 * One RAW frame, black-subtracted and normalised so 0 is black and 1 is the white level.
 * Values can dip slightly below 0 from noise; that's kept for unbiased averaging.
 */
class RawFrame(
    val width: Int,
    val height: Int,
    val data: FloatArray,
    val cfa: Cfa,
    val noise: NoiseModel,
) {
    init {
        require(width % 2 == 0 && height % 2 == 0) { "Bayer frames must have even dimensions" }
        require(data.size == width * height)
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    fun clamped(x: Int, y: Int): Float =
        data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    /**
     * Sample with mirror reflection about the edge pixel (-1 -> 1, w -> w-2). Unlike clamping, this
     * keeps the Bayer colour of every out-of-range read.
     */
    fun mirrored(x: Int, y: Int): Float {
        val mx = if (x < 0) -x else if (x >= width) 2 * (width - 1) - x else x
        val my = if (y < 0) -y else if (y >= height) 2 * (height - 1) - y else y
        return data[my.coerceIn(0, height - 1) * width + mx.coerceIn(0, width - 1)]
    }

    /**
     * Half-resolution grey image: the mean of each 2x2 Bayer cell. Used for alignment; even
     * shifts at full resolution are integer shifts here, and they keep the Bayer phase.
     */
    fun grayHalf(): Plane {
        val w = width / 2
        val h = height / 2
        val out = Plane(w, h)
        java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
            val r0 = 2 * y * width
            val r1 = r0 + width
            for (x in 0 until w) {
                val c = 2 * x
                out.data[y * w + x] = 0.25f * (data[r0 + c] + data[r0 + c + 1] + data[r1 + c] + data[r1 + c + 1])
            }
        }
        return out
    }

    companion object {
        /**
         * From 16-bit sensor values (as Camera2 RAW_SENSOR delivers them): subtract the per-channel
         * black level and divide by (white - black).
         */
        fun fromU16(
            width: Int,
            height: Int,
            raw: ShortArray,
            cfa: Cfa,
            blackLevel: FloatArray,
            whiteLevel: Float,
            noise: NoiseModel,
        ): RawFrame {
            require(raw.size >= width * height)
            val data = FloatArray(width * height)
            java.util.stream.IntStream.range(0, height).parallel().forEach { y ->
                for (x in 0 until width) {
                    val ch = cfa.channelAt(x, y)
                    val v = (raw[y * width + x].toInt() and 0xFFFF).toFloat()
                    data[y * width + x] = (v - blackLevel[ch]) / (whiteLevel - blackLevel[ch])
                }
            }
            return RawFrame(width, height, data, cfa, noise)
        }
    }
}
