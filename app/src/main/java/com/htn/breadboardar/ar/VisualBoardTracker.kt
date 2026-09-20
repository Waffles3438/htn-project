package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Image measurements own the board position. ARCore may interpolate between two
 * detections only after its motion agrees with several visual measurements.
 * Never accumulate a world-map correction into the next visual measurement.
 */
internal class VisualBoardTracker {
    private val smoother = PoseSmoother()
    private var observation: Observation? = null
    private var agreeingMotionSamples = 0
    private var pendingJump: PendingJump? = null

    private data class PendingJump(
        val reference: PlanarPoseSolver.Result,
        val firstTimestampNs: Long,
        val lastTimestampNs: Long,
        val samples: Int,
    )

    private data class Observation(
        val raw: PlanarPoseSolver.Result,
        val filtered: PlanarPoseSolver.Result,
        val cameraInWorld: Pose?,
        val timestampNs: Long,
    )

    fun reset() {
        observation = null
        agreeingMotionSamples = 0
        pendingJump = null
    }

    fun observe(
        visual: PlanarPoseSolver.Result,
        cameraInWorld: Pose?,
        timestampNs: Long,
    ): PlanarPoseSolver.Result? {
        val previous = observation
        if (previous != null && timestampNs <= previous.timestampNs) return null
        val gap = previous?.let { timestampNs - it.timestampNs } ?: Long.MAX_VALUE
        if (previous != null && gap < VISUAL_GRACE_NS &&
            (rotationDistance(previous.raw, visual) > MAX_INSTANT_ROTATION_RAD ||
                translationDistance(previous.raw, visual) > MAX_INSTANT_TRANSLATION_M)
        ) {
            // A noisy trapezoid/rectangle alternation looks like fast motion to
            // the adaptive smoother. Confirm it BEFORE smoothing; rejected poses
            // must not refresh timestamps, corner history or AR motion trust.
            val pending = pendingJump
            val consistent = pending != null && timestampNs > pending.lastTimestampNs &&
                timestampNs - pending.lastTimestampNs <= MAX_CONFIRMATION_GAP_NS &&
                rotationDistance(pending.reference, visual) < JUMP_AGREEMENT_RAD &&
                translationDistance(pending.reference, visual) < JUMP_AGREEMENT_M
            val next = if (consistent) pending!!.copy(lastTimestampNs = timestampNs, samples = pending.samples + 1)
                else PendingJump(copy(visual), timestampNs, timestampNs, 1)
            pendingJump = next
            agreeingMotionSamples = 0
            if (next.samples < REQUIRED_JUMP_SAMPLES || timestampNs - next.firstTimestampNs < JUMP_CONFIRMATION_NS) {
                return null
            }
        }
        pendingJump = null
        val predicted = previous?.let { predict(it, cameraInWorld, timestampNs, raw = true) }
        val agrees = predicted != null &&
            translationDistance(predicted, visual) <= MAX_VISUAL_DISAGREEMENT_M &&
            rotationDistance(predicted, visual) <= MAX_VISUAL_DISAGREEMENT_RAD
        agreeingMotionSamples = if (agrees) minOf(agreeingMotionSamples + 1, REQUIRED_AGREEMENTS) else 0

        // Smooth only image-derived poses. A drifted world estimate is never the
        // starting point of the filter, even when it reports TRACKING.
        val filtered = smoother.smooth(previous?.filtered?.takeIf { gap < VISUAL_GRACE_NS }, visual)
        observation = Observation(copy(visual), filtered, cameraInWorld, timestampNs)
        return filtered
    }

    fun poseAt(cameraInWorld: Pose?, timestampNs: Long): PlanarPoseSolver.Result? {
        val latest = observation ?: return null
        val age = timestampNs - latest.timestampNs
        if (age < 0L || age > VISUAL_GRACE_NS) return null
        if (agreeingMotionSamples >= REQUIRED_AGREEMENTS) {
            predict(latest, cameraInWorld, timestampNs)?.let { return it }
            // Stop accepting AR motion until several new image measurements agree.
            agreeingMotionSamples = 0
        }
        return latest.filtered
    }

    private fun predict(
        source: Observation,
        camera: Pose?,
        timestampNs: Long,
        raw: Boolean = false,
    ): PlanarPoseSolver.Result? {
        val previousCamera = source.cameraInWorld ?: return null
        val currentCamera = camera ?: return null
        val age = timestampNs - source.timestampNs
        if (age !in 0L..MAX_PREDICTION_NS) return null
        val relative = currentCamera.inverse().compose(previousCamera)
        val t = relative.translation
        if (!t.all { it.isFinite() } || sqrt(t.sumOf { (it * it).toDouble() }) > MAX_PREDICTED_TRANSLATION_M) return null
        val q = relative.rotationQuaternion
        if (!q.all { it.isFinite() } || 2f * acos(abs(q[3]).coerceIn(0f, 1f)) > MAX_PREDICTED_ROTATION_RAD) return null
        val sourcePose = if (raw) source.raw else source.filtered
        val predicted = relative.compose(Pose(sourcePose.translation, sourcePose.quaternion))
        if (!predicted.translation.all { it.isFinite() } || predicted.tz() >= -0.02f) return null
        return PlanarPoseSolver.Result(predicted.translation, predicted.rotationQuaternion)
    }

    private fun translationDistance(a: PlanarPoseSolver.Result, b: PlanarPoseSolver.Result): Float =
        sqrt((0 until 3).sumOf { val d = a.translation[it] - b.translation[it]; (d * d).toDouble() }).toFloat()

    private fun rotationDistance(a: PlanarPoseSolver.Result, b: PlanarPoseSolver.Result): Float =
        2f * acos(abs((0 until 4).sumOf { (a.quaternion[it] * b.quaternion[it]).toDouble() }.toFloat()).coerceIn(0f, 1f))

    private fun copy(pose: PlanarPoseSolver.Result) =
        PlanarPoseSolver.Result(pose.translation.copyOf(), pose.quaternion.copyOf())

    private companion object {
        const val REQUIRED_AGREEMENTS = 5
        const val MAX_VISUAL_DISAGREEMENT_M = 0.008f
        const val MAX_VISUAL_DISAGREEMENT_RAD = 0.15f
        const val MAX_PREDICTED_TRANSLATION_M = 0.015f
        const val MAX_PREDICTED_ROTATION_RAD = 0.20f
        const val MAX_PREDICTION_NS = 150_000_000L
        const val VISUAL_GRACE_NS = 900_000_000L
        const val MAX_INSTANT_ROTATION_RAD = 0.21f // ~12 degrees between measurements.
        const val MAX_INSTANT_TRANSLATION_M = 0.025f
        const val JUMP_AGREEMENT_RAD = 0.10f // ~6 degrees, relative to the first candidate.
        const val JUMP_AGREEMENT_M = 0.010f
        const val REQUIRED_JUMP_SAMPLES = 3
        const val JUMP_CONFIRMATION_NS = 120_000_000L
        const val MAX_CONFIRMATION_GAP_NS = 200_000_000L
    }
}
