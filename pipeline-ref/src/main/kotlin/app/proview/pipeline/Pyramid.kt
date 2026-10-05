package app.proview.pipeline

/**
 * Gaussian image pyramid used for coarse-to-fine alignment.
 *
 * Level 0 is the input. Each further level is the previous one blurred with a binomial
 * [1 4 6 4 1]/16 kernel (a close approximation of a Gaussian) and decimated by its factor.
 */
class Pyramid(val levels: List<Plane>, val factors: List<Int>) {
    init {
        require(levels.size == factors.size + 1) { "Need one factor per level after the first" }
    }

    /** Total downsampling of [level] relative to level 0. */
    fun scaleOf(level: Int): Int = factors.take(level).fold(1) { acc, f -> acc * f }

    companion object {
        /** HDR+-style factors: the finest step is 2x, the coarser steps 4x. */
        val DEFAULT_FACTORS = listOf(2, 4, 4)

        fun build(base: Plane, factors: List<Int> = DEFAULT_FACTORS): Pyramid {
            val levels = mutableListOf(base)
            var current = base
            for (f in factors) {
                require(f >= 1) { "Downsample factor must be >= 1" }
                current = downsample(current, f)
                levels += current
            }
            return Pyramid(levels, factors)
        }

        /** Blurs with repeated binomial passes, then keeps every [factor]-th sample. */
        fun downsample(src: Plane, factor: Int): Plane {
            if (factor == 1) return src.copy()
            var blurred = src
            // One 5-tap pass removes frequencies above 1/2 Nyquist; 4x needs two passes.
            repeat(if (factor <= 2) 1 else 2) { blurred = binomialBlur(blurred) }
            val w = maxOf(1, src.width / factor)
            val h = maxOf(1, src.height / factor)
            val offset = factor / 2
            return Plane.create(w, h) { x, y -> blurred.clamped(x * factor + offset, y * factor + offset) }
        }

        fun binomialBlur(src: Plane): Plane {
            val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
            val tmp = Plane.create(src.width, src.height) { x, y ->
                var s = 0f
                for (i in -2..2) s += k[i + 2] * src.clamped(x + i, y)
                s / 16f
            }
            return Plane.create(src.width, src.height) { x, y ->
                var s = 0f
                for (i in -2..2) s += k[i + 2] * tmp.clamped(x, y + i)
                s / 16f
            }
        }
    }
}
