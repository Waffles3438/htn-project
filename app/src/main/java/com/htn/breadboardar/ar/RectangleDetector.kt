package com.htn.breadboardar.ar

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds bright four-sided blobs in a camera luma plane so the learner can tap the
 * one that is the breadboard.
 *
 * Deliberately dependency free: everything here is plain Kotlin over the Y plane
 * ARCore already hands us, so the app gains no native library and no APK size at
 * this stage. The pipeline is the classic one:
 *
 *  1. Downscale, because rectangle shape survives aggressive downsampling while
 *     the cost of every later stage scales with pixel count.
 *  2. Otsu threshold, so "bright" comes from the actual histogram rather than a
 *     hard-coded value that breaks under different lighting or exposure.
 *  3. Connected components, grouping bright pixels into candidate objects.
 *  4. Convex hull plus minimum-area rectangle, giving four corners and a measure
 *     of how rectangular each blob actually is.
 *
 * Corners come back in full resolution image pixel coordinates, so the caller can
 * hand them straight to ARCore's coordinate transforms.
 */
internal class RectangleDetector {

    /**
     * One candidate: four corners in order, as x0, y0, x1, y1, x2, y2, x3, y3.
     *
     * [stepPx] is the downscale factor the corners were recovered at, which bounds
     * their accuracy and lets the caller scale its tolerances accordingly.
     */
    class Candidate(val corners: FloatArray, val areaPx: Float, val stepPx: Int)

    private var gray = ByteArray(0)
    private var visited = BooleanArray(0)
    private var stack = IntArray(0)
    private var blobX = IntArray(0)
    private var blobY = IntArray(0)
    private var hullIndices = IntArray(0)
    private var stackSize = 0
    private var blobSize = 0
    private var blobArea = 0

    /**
     * @param lenient loosens the shape gates. While scanning, strict gates keep junk
     *   out of the candidate list. While tracking a board we already know exists and
     *   roughly where it is, the same gates reject perfectly usable blurred frames, so
     *   holding lock matters more than being fussy.
     */
    fun detect(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        lenient: Boolean = false,
    ): List<Candidate> {
        if (width <= 0 || height <= 0) return emptyList()

        val step = maxOf(1, maxOf(width, height) / TARGET_MAX_DIMENSION)
        val smallWidth = width / step
        val smallHeight = height / step
        if (smallWidth < 16 || smallHeight < 16) return emptyList()

        val pixelCount = smallWidth * smallHeight
        ensureCapacity(pixelCount)

        val histogram = IntArray(256)
        for (y in 0 until smallHeight) {
            val sourceRow = y * step * rowStride
            val targetRow = y * smallWidth
            for (x in 0 until smallWidth) {
                val value = luma[sourceRow + x * step].toInt() and 0xFF
                gray[targetRow + x] = value.toByte()
                histogram[value]++
            }
        }

        val threshold = otsuThreshold(histogram, pixelCount)
        Arrays.fill(visited, 0, pixelCount, false)

        val areaFraction = if (lenient) LENIENT_MIN_AREA_FRACTION else MIN_AREA_FRACTION
        val minRectangularity = if (lenient) LENIENT_MIN_RECTANGULARITY else MIN_RECTANGULARITY
        val minArea = maxOf(MIN_AREA_ABSOLUTE_PX, (pixelCount * areaFraction).toInt())
        val candidates = mutableListOf<Candidate>()

        for (seed in 0 until pixelCount) {
            if (visited[seed]) continue
            if (!isBright(seed, threshold)) continue

            fillRegion(seed, threshold, smallWidth, smallHeight)
            if (blobArea < minArea) continue
            // A blob running off the frame is clipped, so its corners are not the
            // object's corners and any pose derived from them would be wrong.
            if (touchesBorder(smallWidth, smallHeight)) continue

            val hull = convexHull() ?: continue
            val rect = minAreaRect(hull) ?: continue
            val rectArea = rectangleArea(rect)
            if (rectArea <= 0f) continue
            if (polygonArea(hull) / rectArea < minRectangularity) continue

            val longest = rectangleLongestSide(rect)
            val shortest = rectangleShortestSide(rect)
            if (shortest <= 0f || longest / shortest > MAX_ASPECT_RATIO) continue

            // The enclosing rectangle is only a coarse answer: a perspective view of
            // a rectangle is a trapezoid, so forcing a rectangle overshoots the real
            // outline and biases the corners. Refine to a general quad.
            val quad = refineQuad(hull, rect) ?: rect
            for (i in quad.indices) quad[i] *= step.toFloat()
            candidates += Candidate(quad, rectArea * step * step, step)
        }

        return candidates.sortedByDescending { it.areaPx }.take(MAX_CANDIDATES)
    }

    private fun ensureCapacity(pixelCount: Int) {
        if (gray.size < pixelCount) gray = ByteArray(pixelCount)
        if (visited.size < pixelCount) visited = BooleanArray(pixelCount)
        if (stack.size < pixelCount) stack = IntArray(pixelCount)
        if (blobX.size < pixelCount) blobX = IntArray(pixelCount)
        if (blobY.size < pixelCount) blobY = IntArray(pixelCount)
        if (hullIndices.size < pixelCount + 1) hullIndices = IntArray(pixelCount + 1)
    }

    private fun isBright(index: Int, threshold: Int): Boolean =
        (gray[index].toInt() and 0xFF) > threshold

    /**
     * Four-connected flood fill from [seed]. Eight-connectivity would bridge the
     * board's dark hole grid more eagerly, but it also merges the board with
     * anything touching it diagonally.
     */
    private fun fillRegion(seed: Int, threshold: Int, width: Int, height: Int) {
        stackSize = 0
        blobSize = 0
        blobArea = 0
        stack[stackSize++] = seed
        visited[seed] = true

        while (stackSize > 0) {
            val index = stack[--stackSize]
            val x = index % width
            val y = index / width
            blobArea++

            // Only boundary pixels are kept. The convex hull and the line fits care
            // solely about the outline, and retaining the interior turned the hull's
            // sort into tens of thousands of boxed comparisons per blob.
            val onEdge = x == 0 || y == 0 || x == width - 1 || y == height - 1
            val isBoundary = onEdge ||
                !isBright(index - 1, threshold) ||
                !isBright(index + 1, threshold) ||
                !isBright(index - width, threshold) ||
                !isBright(index + width, threshold)
            if (isBoundary) {
                blobX[blobSize] = x
                blobY[blobSize] = y
                blobSize++
            }

            if (x > 0) push(index - 1, threshold)
            if (x < width - 1) push(index + 1, threshold)
            if (y > 0) push(index - width, threshold)
            if (y < height - 1) push(index + width, threshold)
        }
    }

    private fun push(index: Int, threshold: Int) {
        if (visited[index]) return
        if (!isBright(index, threshold)) return
        visited[index] = true
        stack[stackSize++] = index
    }

    private fun touchesBorder(width: Int, height: Int): Boolean {
        for (i in 0 until blobSize) {
            val x = blobX[i]
            val y = blobY[i]
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return true
        }
        return false
    }

    /** Otsu's method: the threshold maximising between-class variance. */
    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * histogram[i]

        var sumBelow = 0L
        var countBelow = 0
        var bestVariance = -1.0
        var best = 127
        for (t in 0..255) {
            countBelow += histogram[t]
            if (countBelow == 0) continue
            val countAbove = total - countBelow
            if (countAbove == 0) break
            sumBelow += t.toLong() * histogram[t]
            val meanBelow = sumBelow.toDouble() / countBelow
            val meanAbove = (sum - sumBelow).toDouble() / countAbove
            val delta = meanBelow - meanAbove
            val variance = countBelow.toDouble() * countAbove * delta * delta
            if (variance > bestVariance) {
                bestVariance = variance
                best = t
            }
        }
        return best
    }

    /**
     * Andrew's monotone chain over the current blob. Returns the hull as x, y
     * pairs in counter-clockwise order, or null when the blob is degenerate.
     */
    private fun convexHull(): FloatArray? {
        if (blobSize < 4) return null

        val order = (0 until blobSize).sortedWith(compareBy({ blobX[it] }, { blobY[it] }))
        var size = 0

        // Strictly-less keeps collinear points on the hull. That matters: corner
        // refinement fits a line to each side, and a straight edge stripped down to
        // its two endpoints leaves nothing to fit.
        for (i in 0 until blobSize) {
            val point = order[i]
            while (size >= 2 && cross(hullIndices[size - 2], hullIndices[size - 1], point) < 0L) {
                size--
            }
            hullIndices[size++] = point
        }

        // Upper hull. The floor stops popping at the lower hull's final point.
        val floor = size + 1
        for (i in blobSize - 2 downTo 0) {
            val point = order[i]
            while (size >= floor && cross(hullIndices[size - 2], hullIndices[size - 1], point) < 0L) {
                size--
            }
            hullIndices[size++] = point
        }

        // The last point repeats the first.
        val hullCount = size - 1
        if (hullCount < 3) return null

        val result = FloatArray(hullCount * 2)
        for (i in 0 until hullCount) {
            val point = hullIndices[i]
            result[i * 2] = blobX[point].toFloat()
            result[i * 2 + 1] = blobY[point].toFloat()
        }
        return result
    }

    private fun cross(a: Int, b: Int, c: Int): Long {
        val ax = blobX[a].toLong()
        val ay = blobY[a].toLong()
        return (blobX[b] - ax) * (blobY[c] - ay) - (blobY[b] - ay) * (blobX[c] - ax)
    }

    /**
     * Rotating calipers. For a convex hull the minimum-area enclosing rectangle
     * always has one side flush with a hull edge, so testing every edge is exact
     * rather than approximate.
     */
    private fun minAreaRect(hull: FloatArray): FloatArray? {
        val pointCount = hull.size / 2
        if (pointCount < 3) return null

        var bestArea = Float.MAX_VALUE
        var best: FloatArray? = null

        for (i in 0 until pointCount) {
            val j = (i + 1) % pointCount
            val edgeX = hull[j * 2] - hull[i * 2]
            val edgeY = hull[j * 2 + 1] - hull[i * 2 + 1]
            val length = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (length < 1e-3f) continue

            val ux = edgeX / length
            val uy = edgeY / length
            val vx = -uy
            val vy = ux

            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (k in 0 until pointCount) {
                val x = hull[k * 2]
                val y = hull[k * 2 + 1]
                val u = x * ux + y * uy
                val v = x * vx + y * vy
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }

            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                best = floatArrayOf(
                    minU * ux + minV * vx, minU * uy + minV * vy,
                    maxU * ux + minV * vx, maxU * uy + minV * vy,
                    maxU * ux + maxV * vx, maxU * uy + maxV * vy,
                    minU * ux + maxV * vx, minU * uy + maxV * vy,
                )
            }
        }
        return best
    }

    /**
     * Turns the coarse enclosing rectangle into a true quadrilateral.
     *
     * Each hull point is assigned to the nearest rectangle side, a line is fitted to
     * each group, and adjacent lines are intersected. Averaging many edge points per
     * side gives corners far more accurate than any individual boundary pixel, and
     * unlike an enclosing rectangle the result can be a trapezoid, which is what a
     * perspective view of a rectangle actually looks like.
     *
     * Points near the corners are excluded: blob corners are rounded by thresholding
     * and would drag the line fits inward.
     */
    private fun refineQuad(hull: FloatArray, rect: FloatArray): FloatArray? {
        val pointCount = hull.size / 2
        if (pointCount < MIN_HULL_POINTS_FOR_REFINEMENT) return null

        val counts = IntArray(4)
        val sumX = DoubleArray(4)
        val sumY = DoubleArray(4)
        val sumXX = DoubleArray(4)
        val sumXY = DoubleArray(4)
        val sumYY = DoubleArray(4)

        for (i in 0 until pointCount) {
            val px = hull[i * 2]
            val py = hull[i * 2 + 1]
            var bestSide = -1
            var bestDistance = Float.MAX_VALUE
            for (side in 0 until 4) {
                val distance = perpendicularDistance(px, py, rect, side) ?: continue
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestSide = side
                }
            }
            if (bestSide < 0) continue

            counts[bestSide]++
            sumX[bestSide] += px.toDouble()
            sumY[bestSide] += py.toDouble()
            sumXX[bestSide] += px.toDouble() * px
            sumXY[bestSide] += px.toDouble() * py
            sumYY[bestSide] += py.toDouble() * py
        }

        val originX = FloatArray(4)
        val originY = FloatArray(4)
        val dirX = FloatArray(4)
        val dirY = FloatArray(4)
        for (side in 0 until 4) {
            val n = counts[side]
            if (n < MIN_POINTS_PER_SIDE) return null
            val meanX = sumX[side] / n
            val meanY = sumY[side] / n
            val varianceX = sumXX[side] / n - meanX * meanX
            val varianceY = sumYY[side] / n - meanY * meanY
            val covariance = sumXY[side] / n - meanX * meanY
            // Principal axis of the point group: a total-least-squares line fit that,
            // unlike y = mx + c, stays stable for near-vertical sides.
            val angle = 0.5 * kotlin.math.atan2(2.0 * covariance, varianceX - varianceY)
            originX[side] = meanX.toFloat()
            originY[side] = meanY.toFloat()
            dirX[side] = kotlin.math.cos(angle).toFloat()
            dirY[side] = kotlin.math.sin(angle).toFloat()
        }

        val quad = FloatArray(8)
        for (corner in 0 until 4) {
            val a = (corner + 3) % 4
            val intersection = intersect(
                originX[a], originY[a], dirX[a], dirY[a],
                originX[corner], originY[corner], dirX[corner], dirY[corner],
            ) ?: return null
            quad[corner * 2] = intersection[0]
            quad[corner * 2 + 1] = intersection[1]
        }

        // Validate against the hull, not against the enclosing rectangle. Refinement
        // is *supposed* to pull corners well inside that rectangle for oblique views,
        // so bounding the movement would veto the correction we want. The hull is a
        // close approximation of the true outline, so a good quad has nearly its area.
        if (!isConvex(quad)) return null
        val hullArea = polygonArea(hull)
        val quadArea = polygonArea(quad)
        if (hullArea <= 0f || quadArea <= 0f) return null
        if (abs(quadArea - hullArea) > hullArea * MAX_AREA_DEVIATION) return null
        return quad
    }

    private fun isConvex(quad: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = i * 2
            val b = ((i + 1) % 4) * 2
            val c = ((i + 2) % 4) * 2
            val cross = (quad[b] - quad[a]) * (quad[c + 1] - quad[b + 1]) -
                (quad[b + 1] - quad[a + 1]) * (quad[c] - quad[b])
            if (abs(cross) < 1e-6f) continue
            val current = if (cross > 0f) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return sign != 0
    }

    /**
     * Perpendicular distance from a point to a rectangle side, or null when the point
     * projects near either end of that side.
     */
    private fun perpendicularDistance(px: Float, py: Float, rect: FloatArray, side: Int): Float? {
        val ax = rect[side * 2]
        val ay = rect[side * 2 + 1]
        val bx = rect[((side + 1) % 4) * 2]
        val by = rect[((side + 1) % 4) * 2 + 1]
        val edgeX = bx - ax
        val edgeY = by - ay
        val lengthSquared = edgeX * edgeX + edgeY * edgeY
        if (lengthSquared < 1e-6f) return null

        val t = ((px - ax) * edgeX + (py - ay) * edgeY) / lengthSquared
        if (t < CORNER_EXCLUSION || t > 1f - CORNER_EXCLUSION) return null
        val closestX = ax + t * edgeX
        val closestY = ay + t * edgeY
        val dx = px - closestX
        val dy = py - closestY
        return sqrt(dx * dx + dy * dy)
    }

    private fun intersect(
        ax: Float, ay: Float, adx: Float, ady: Float,
        bx: Float, by: Float, bdx: Float, bdy: Float,
    ): FloatArray? {
        val denominator = adx * bdy - ady * bdx
        if (abs(denominator) < 1e-6f) return null
        val t = ((bx - ax) * bdy - (by - ay) * bdx) / denominator
        return floatArrayOf(ax + t * adx, ay + t * ady)
    }

    private fun polygonArea(polygon: FloatArray): Float {
        val count = polygon.size / 2
        var total = 0f
        for (i in 0 until count) {
            val j = (i + 1) % count
            total += polygon[i * 2] * polygon[j * 2 + 1] - polygon[j * 2] * polygon[i * 2 + 1]
        }
        return abs(total) / 2f
    }

    private fun rectangleArea(rect: FloatArray): Float =
        rectangleSide(rect, 0) * rectangleSide(rect, 1)

    private fun rectangleLongestSide(rect: FloatArray): Float =
        maxOf(rectangleSide(rect, 0), rectangleSide(rect, 1))

    private fun rectangleShortestSide(rect: FloatArray): Float =
        minOf(rectangleSide(rect, 0), rectangleSide(rect, 1))

    private fun rectangleSide(rect: FloatArray, index: Int): Float {
        val a = index * 2
        val b = ((index + 1) % 4) * 2
        val dx = rect[b] - rect[a]
        val dy = rect[b + 1] - rect[a + 1]
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        /** Detection runs on a downscaled copy; shape survives, cost drops sharply. */
        const val TARGET_MAX_DIMENSION = 240

        /** "Bigger than a certain size", as a fraction of the downscaled frame. */
        const val MIN_AREA_FRACTION = 0.01f
        const val MIN_AREA_ABSOLUTE_PX = 200

        /** Hull area over enclosing-rectangle area. Below this the blob is not a rectangle. */
        const val MIN_RECTANGULARITY = 0.72f

        /** Relaxed gates for holding lock through motion blur. */
        const val LENIENT_MIN_RECTANGULARITY = 0.55f
        const val LENIENT_MIN_AREA_FRACTION = 0.004f

        /** Rejects slivers such as table edges and cable runs. */
        const val MAX_ASPECT_RATIO = 12f

        /** Keeps the overlay readable and each tap target unambiguous. */
        const val MAX_CANDIDATES = 6

        /** Corner refinement needs enough boundary points to fit four lines. */
        const val MIN_HULL_POINTS_FOR_REFINEMENT = 12
        const val MIN_POINTS_PER_SIDE = 4

        /** Fraction of each side discarded at both ends, where corners round off. */
        const val CORNER_EXCLUSION = 0.18f

        /** How far the refined quad's area may differ from the hull's before we distrust it. */
        const val MAX_AREA_DEVIATION = 0.2f
    }
}
