package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** A calibrated board belongs to the world, never to the current camera image. */
internal class AnchoredBoardTracker {
    private var previous: PlanarPoseSolver.Result? = null
    private var previousTimestampNs = 0L
    var needsRecalibration = false
        private set

    fun reset() {
        previous = null
        previousTimestampNs = 0L
        needsRecalibration = false
    }

    /** Null inputs mean ARCore is paused, NOT that the rectangle detector missed a frame. */
    fun poseAt(anchorInWorld: Pose?, cameraInWorld: Pose?, timestampNs: Long): PlanarPoseSolver.Result? {
        if (needsRecalibration || anchorInWorld == null || cameraInWorld == null) return null
        if (!healthyWorldPose(anchorInWorld) || !healthyWorldPose(cameraInWorld)) return reject()
        val current = CameraPoseFrames.boardInCamera(anchorInWorld, cameraInWorld)
        if (length(current.translation) > 3f) return reject()
        val old = previous
        if (old != null) {
            if (timestampNs < previousTimestampNs) return null
            val seconds = ((timestampNs - previousTimestampNs) / 1e9f).coerceAtMost(1f)
            val distance = length(FloatArray(3) { current.translation[it] - old.translation[it] })
            val dot = abs((0..3).sumOf { (old.quaternion[it] * current.quaternion[it]).toDouble() }).toFloat()
            val angle = 2f * acos(dot.coerceIn(0f, 1f))
            // Reject broken world-map jumps even if ARCore reports TRACKING.
            // Compare camera-relative poses so coherent world-map rebases are harmless.
            if (distance > 0.04f + 1.5f * seconds || angle > 0.12f + 4f * seconds) return reject()
        }
        previous = current
        previousTimestampNs = timestampNs
        return current
    }

    private fun reject(): PlanarPoseSolver.Result? {
        needsRecalibration = true
        return null
    }

    companion object {
        fun healthyWorldPose(pose: Pose): Boolean =
            pose.translation.all { it.isFinite() && abs(it) < 100f } &&
                pose.rotationQuaternion.all { it.isFinite() } &&
                length(pose.rotationQuaternion) in 0.99f..1.01f

        private fun length(values: FloatArray): Float =
            sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
    }
}
