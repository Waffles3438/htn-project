package com.htn.breadboardar.ar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smooths camera-relative planar-board poses without adding a fixed amount of lag
 * to deliberate phone movement.
 *
 * The rectangle detector is most noisy while the phone is held still, so small
 * updates use a conservative blend and sub-millimetre/sub-degree updates are held.
 * A genuine pan or tilt uses a larger blend and catches up within a few detection
 * passes. This class owns no state: the caller passes the last displayed pose back
 * as [previous] on each call.
 */
internal class PoseSmoother(
    private val stillTranslationMeters: Float = 0.003f,
    private val movingTranslationMeters: Float = 0.012f,
    private val stillRotationRadians: Float = degreesToRadians(1.5f),
    private val movingRotationRadians: Float = degreesToRadians(6f),
    private val stationaryBlend: Float = 0.22f,
    private val movingBlend: Float = 0.72f,
    private val translationDeadbandMeters: Float = 0.001f,
    private val rotationDeadbandRadians: Float = degreesToRadians(0.35f),
) {
    init {
        require(stillTranslationMeters >= 0f)
        require(movingTranslationMeters > stillTranslationMeters)
        require(stillRotationRadians >= 0f)
        require(movingRotationRadians > stillRotationRadians)
        require(stationaryBlend in 0f..1f)
        require(movingBlend in stationaryBlend..1f)
        require(translationDeadbandMeters >= 0f)
        require(rotationDeadbandRadians >= 0f)
    }

    /**
     * Returns a copied, filtered pose. A null or malformed [previous] starts a
     * new sequence from [fresh], which avoids carrying an invalid estimate forward.
     */
    fun smooth(
        previous: PlanarPoseSolver.Result?,
        fresh: PlanarPoseSolver.Result,
    ): PlanarPoseSolver.Result {
        val freshQuaternion = normalisedQuaternion(fresh.quaternion) ?: return copyOf(fresh)
        if (previous == null || previous.translation.size < TRANSLATION_COMPONENTS) {
            return PlanarPoseSolver.Result(fresh.translation.copyOf(), freshQuaternion)
        }
        val previousQuaternion = normalisedQuaternion(previous.quaternion)
            ?: return PlanarPoseSolver.Result(fresh.translation.copyOf(), freshQuaternion)

        val translationDelta = translationDistance(previous.translation, fresh.translation)
        val rotationDelta = rotationDistanceRadians(previousQuaternion, freshQuaternion)
        if (translationDelta <= translationDeadbandMeters &&
            rotationDelta <= rotationDeadbandRadians
        ) {
            return PlanarPoseSolver.Result(previous.translation.copyOf(), previousQuaternion)
        }

        val translationMotion = normalisedRange(
            translationDelta,
            stillTranslationMeters,
            movingTranslationMeters,
        )
        val rotationMotion = normalisedRange(
            rotationDelta,
            stillRotationRadians,
            movingRotationRadians,
        )
        val blend = stationaryBlend +
            (movingBlend - stationaryBlend) * max(translationMotion, rotationMotion)

        val translation = FloatArray(TRANSLATION_COMPONENTS) { index ->
            previous.translation[index] * (1f - blend) + fresh.translation[index] * blend
        }
        return PlanarPoseSolver.Result(translation, slerp(previousQuaternion, freshQuaternion, blend))
    }

    private fun translationDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size < TRANSLATION_COMPONENTS || b.size < TRANSLATION_COMPONENTS) {
            return Float.POSITIVE_INFINITY
        }
        var sum = 0f
        for (index in 0 until TRANSLATION_COMPONENTS) {
            val delta = a[index] - b[index]
            sum += delta * delta
        }
        return sqrt(sum)
    }

    /** Quaternion distance independent of the q/-q double cover. */
    private fun rotationDistanceRadians(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (index in 0 until QUATERNION_COMPONENTS) dot += a[index] * b[index]
        return 2f * acos(abs(dot).coerceIn(0f, 1f))
    }

    /**
     * Sign-corrected SLERP. A near-identical rotation uses normalised lerp to
     * avoid division by a very small sine.
     */
    private fun slerp(from: FloatArray, to: FloatArray, blend: Float): FloatArray {
        var dot = 0f
        for (index in 0 until QUATERNION_COMPONENTS) dot += from[index] * to[index]

        val alignedTo = if (dot < 0f) {
            dot = -dot
            FloatArray(QUATERNION_COMPONENTS) { index -> -to[index] }
        } else {
            to
        }
        dot = dot.coerceIn(0f, 1f)

        if (dot > NEARLY_IDENTICAL_DOT) {
            return normalisedQuaternion(
                FloatArray(QUATERNION_COMPONENTS) { index ->
                    from[index] * (1f - blend) + alignedTo[index] * blend
                },
            ) ?: from.copyOf()
        }

        val totalAngle = acos(dot)
        val sine = sin(totalAngle)
        if (abs(sine) < MIN_SINE) return from.copyOf()
        val fromWeight = sin((1f - blend) * totalAngle) / sine
        val toWeight = sin(blend * totalAngle) / sine
        return normalisedQuaternion(
            FloatArray(QUATERNION_COMPONENTS) { index ->
                from[index] * fromWeight + alignedTo[index] * toWeight
            },
        ) ?: from.copyOf()
    }

    private fun normalisedQuaternion(quaternion: FloatArray): FloatArray? {
        if (quaternion.size < QUATERNION_COMPONENTS) return null
        var squaredMagnitude = 0f
        for (index in 0 until QUATERNION_COMPONENTS) {
            val value = quaternion[index]
            if (!value.isFinite()) return null
            squaredMagnitude += value * value
        }
        if (squaredMagnitude < MIN_QUATERNION_MAGNITUDE_SQUARED) return null
        val inverseMagnitude = 1f / sqrt(squaredMagnitude)
        return FloatArray(QUATERNION_COMPONENTS) { index -> quaternion[index] * inverseMagnitude }
    }

    private fun copyOf(result: PlanarPoseSolver.Result): PlanarPoseSolver.Result =
        PlanarPoseSolver.Result(result.translation.copyOf(), result.quaternion.copyOf())

    private fun normalisedRange(value: Float, start: Float, end: Float): Float =
        ((value - start) / (end - start)).coerceIn(0f, 1f)

    private companion object {
        const val TRANSLATION_COMPONENTS = 3
        const val QUATERNION_COMPONENTS = 4
        const val MIN_QUATERNION_MAGNITUDE_SQUARED = 1e-12f
        const val NEARLY_IDENTICAL_DOT = 0.9995f
        const val MIN_SINE = 1e-6f

        fun degreesToRadians(degrees: Float): Float = degrees * Math.PI.toFloat() / 180f
    }
}
