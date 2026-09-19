package com.htn.breadboardar.ui

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stabilizes a detected rectangle in screen pixels.
 *
 * The detector reports independently estimated corners, so drawing every result
 * verbatim makes a stationary breadboard look like it is vibrating. This filter
 * holds sub-pixel noise, damps ordinary static noise, and increases its response
 * when the entire rectangle is genuinely moving with the phone.
 */
internal class ScreenQuadSmoother(
    private val stillMotionPixels: Float = 4f,
    private val movingMotionPixels: Float = 14f,
    private val stationaryBlend: Float = 0.14f,
    private val movingBlend: Float = 0.84f,
    private val deadbandPixels: Float = 1.25f,
) {
    init {
        require(stillMotionPixels >= 0f)
        require(movingMotionPixels > stillMotionPixels)
        require(stationaryBlend in 0f..1f)
        require(movingBlend in stationaryBlend..1f)
        require(deadbandPixels >= 0f)
    }

    /** Returns a copied, filtered four-corner quad in x/y/x/y order. */
    fun smooth(previous: FloatArray?, fresh: FloatArray): FloatArray {
        if (!isQuad(fresh)) return fresh.copyOf()
        val previousQuad = previous ?: return fresh.copyOf()
        if (!isQuad(previousQuad)) return fresh.copyOf()

        val centreDelta = distance(centre(previousQuad), centre(fresh))
        var meanCornerDelta = 0f
        var maximumCornerDelta = 0f
        for (corner in 0 until CORNERS) {
            val delta = distanceAt(previousQuad, fresh, corner)
            meanCornerDelta += delta
            maximumCornerDelta = max(maximumCornerDelta, delta)
        }
        meanCornerDelta /= CORNERS

        // A small wobble on every corner is visual noise, not learner movement.
        // Holding the last filtered quad eliminates the high-frequency shimmer.
        if (maximumCornerDelta <= deadbandPixels) return previousQuad.copyOf()

        // Centre motion catches pans; mean corner motion catches rotations and
        // perspective changes. Mean (rather than max) keeps a single bad corner
        // from making the whole outline jump.
        val motion = max(centreDelta, meanCornerDelta)
        val motionFraction = ((motion - stillMotionPixels) /
            (movingMotionPixels - stillMotionPixels)).coerceIn(0f, 1f)
        val blend = stationaryBlend + (movingBlend - stationaryBlend) * motionFraction

        return FloatArray(COMPONENTS) { index ->
            previousQuad[index] * (1f - blend) + fresh[index] * blend
        }
    }

    private fun isQuad(points: FloatArray?): Boolean =
        points?.size == COMPONENTS && points.all { it.isFinite() }

    private fun centre(points: FloatArray): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (corner in 0 until CORNERS) {
            x += points[corner * 2]
            y += points[corner * 2 + 1]
        }
        return Pair(x / CORNERS, y / CORNERS)
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun distanceAt(a: FloatArray, b: FloatArray, corner: Int): Float {
        val index = corner * 2
        val dx = a[index] - b[index]
        val dy = a[index + 1] - b[index + 1]
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val CORNERS = 4
        const val COMPONENTS = CORNERS * 2
    }
}
