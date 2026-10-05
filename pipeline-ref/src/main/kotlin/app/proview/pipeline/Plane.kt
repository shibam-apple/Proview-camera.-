package app.proview.pipeline

/**
 * A single-channel image of 32-bit float samples, stored row-major.
 *
 * This is the working format for the reference pipeline: RAW data is converted to planes
 * (luma for alignment, per-channel for merging) so every stage can be written and tested
 * without Android dependencies.
 */
class Plane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "Plane must be non-empty: ${width}x$height" }
        require(data.size == width * height) { "Data size ${data.size} != ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    operator fun set(x: Int, y: Int, value: Float) {
        data[y * width + x] = value
    }

    /** Sample with edge replication, so callers can read outside the image safely. */
    fun clamped(x: Int, y: Int): Float =
        data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun copy(): Plane = Plane(width, height, data.copyOf())

    companion object {
        inline fun create(width: Int, height: Int, f: (x: Int, y: Int) -> Float): Plane {
            val p = Plane(width, height)
            for (y in 0 until height) {
                for (x in 0 until width) p.data[y * width + x] = f(x, y)
            }
            return p
        }
    }
}
