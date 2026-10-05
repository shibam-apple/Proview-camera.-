package app.proview.pipeline

import kotlin.math.abs

/**
 * Per-tile integer displacements that map tiles of the reference frame onto an alternate frame:
 * reference pixel (x, y) in tile t corresponds to alternate pixel (x + dx[t], y + dy[t]).
 */
class AlignmentField(
    val tilesX: Int,
    val tilesY: Int,
    val tileSize: Int,
    val stride: Int,
    val dx: IntArray = IntArray(tilesX * tilesY),
    val dy: IntArray = IntArray(tilesX * tilesY),
) {
    fun index(tx: Int, ty: Int) = ty * tilesX + tx

    /** The tile whose centre is nearest to pixel (x, y). */
    fun nearestTile(x: Int, y: Int): Int {
        val tx = ((x - tileSize / 2).toFloat() / stride).let { Math.round(it) }.coerceIn(0, tilesX - 1)
        val ty = ((y - tileSize / 2).toFloat() / stride).let { Math.round(it) }.coerceIn(0, tilesY - 1)
        return index(tx, ty)
    }
}

/**
 * Coarse-to-fine tile alignment, following the approach of Hasinoff et al.,
 * "Burst photography for high dynamic range and low-light imaging on mobile cameras" (2016).
 *
 * At each pyramid level, from coarsest to finest, every tile searches a small window around
 * the displacement inherited from the coarser level. Coarse levels use L2 distance (robust to
 * the large initial search); the finest level uses L1 (more robust to noise and outliers).
 */
class TileAligner(
    private val tileSize: Int = 16,
    private val coarseTileSize: Int = 8,
    private val searchRadius: Int = 4,
    private val fineSearchRadius: Int = 1,
    private val factors: List<Int> = Pyramid.DEFAULT_FACTORS,
) {
    init {
        require(tileSize % 2 == 0 && coarseTileSize % 2 == 0) { "Tile sizes must be even" }
    }

    fun align(reference: Plane, alternate: Plane): AlignmentField {
        require(reference.width == alternate.width && reference.height == alternate.height) {
            "Frames must have the same size"
        }
        val refPyr = Pyramid.build(reference, factors)
        val altPyr = Pyramid.build(alternate, factors)

        var previous: AlignmentField? = null
        var previousFactor = 1
        for (level in factors.size downTo 0) {
            val ref = refPyr.levels[level]
            val alt = altPyr.levels[level]
            val finest = level == 0
            val size = if (finest) tileSize else coarseTileSize
            val radius = if (finest) fineSearchRadius else searchRadius
            val field = alignLevel(ref, alt, size, radius, useL1 = finest, previous, previousFactor)
            previous = field
            previousFactor = if (level > 0) factors[level - 1] else 1
        }
        return previous!!
    }

    private fun alignLevel(
        ref: Plane,
        alt: Plane,
        size: Int,
        radius: Int,
        useL1: Boolean,
        coarser: AlignmentField?,
        upFactor: Int,
    ): AlignmentField {
        val stride = size / 2
        val tilesX = maxOf(1, (ref.width - size) / stride + 1)
        val tilesY = maxOf(1, (ref.height - size) / stride + 1)
        val field = AlignmentField(tilesX, tilesY, size, stride)

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * stride
                val y0 = ty * stride
                // Inherit the displacement of the coarser tile covering this tile's centre.
                var initX = 0
                var initY = 0
                if (coarser != null) {
                    val c = coarser.nearestTile((x0 + size / 2) / upFactor, (y0 + size / 2) / upFactor)
                    initX = coarser.dx[c] * upFactor
                    initY = coarser.dy[c] * upFactor
                }

                var bestCost = Double.MAX_VALUE
                var bestX = initX
                var bestY = initY
                for (sy in -radius..radius) {
                    for (sx in -radius..radius) {
                        val ox = initX + sx
                        val oy = initY + sy
                        val cost = tileDistance(ref, alt, x0, y0, size, ox, oy, useL1, bestCost)
                        // Prefer the smaller displacement on ties, so flat tiles stay put.
                        if (cost < bestCost ||
                            (cost == bestCost && abs(ox) + abs(oy) < abs(bestX) + abs(bestY))
                        ) {
                            bestCost = cost
                            bestX = ox
                            bestY = oy
                        }
                    }
                }
                val i = field.index(tx, ty)
                field.dx[i] = bestX
                field.dy[i] = bestY
            }
        }
        return field
    }

    /** Sum of absolute (L1) or squared (L2) differences; stops early once above [limit]. */
    private fun tileDistance(
        ref: Plane, alt: Plane, x0: Int, y0: Int, size: Int,
        ox: Int, oy: Int, useL1: Boolean, limit: Double,
    ): Double {
        var sum = 0.0
        for (j in 0 until size) {
            val y = y0 + j
            for (i in 0 until size) {
                val x = x0 + i
                val d = (ref.clamped(x, y) - alt.clamped(x + ox, y + oy)).toDouble()
                sum += if (useL1) abs(d) else d * d
            }
            if (sum > limit) return sum
        }
        return sum
    }
}
