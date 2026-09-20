package com.htn.breadboardar.ar

import com.google.ar.core.Pose

/** Visual calibration always works independently of world-map health. */
internal class HybridBoardTracker {
    private val visual = VisualBoardTracker()
    private val world = AnchoredBoardTracker()
    private val alignment = AnchorAlignmentGate()
    val anchorUsable: Boolean get() = alignment.canRender && !world.needsRecalibration

    fun reset() {
        visual.reset()
        world.reset()
        alignment.reset()
    }

    fun observeVisual(pose: PlanarPoseSolver.Result, timestampNs: Long): Boolean =
        // Never inject a drifting world transform into the visual fallback.
        visual.observe(pose, null, timestampNs) != null

    fun checkAnchor(expected: FloatArray, measured: FloatArray?, timestampNs: Long) {
        alignment.observe(expected, measured, timestampNs)
    }

    fun poseAt(anchorWorld: Pose?, cameraWorld: Pose?, timestampNs: Long): PlanarPoseSolver.Result? {
        if (anchorUsable) world.poseAt(anchorWorld, cameraWorld, timestampNs)?.let { return it }
        return visual.poseAt(null, timestampNs)
    }
}
