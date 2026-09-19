package com.htn.breadboardar.ar

import com.google.ar.core.Pose

/**
 * Converts between ARCore's physical image-camera axes and its display-oriented
 * virtual-camera axes.
 *
 * The planar solver consumes [com.google.ar.core.Coordinates2d.IMAGE_PIXELS] and
 * image intrinsics, so its result is expressed in the physical camera frame.
 * ARCore's camera background and projection, however, are display-oriented. In
 * portrait those frames commonly differ by a quarter turn around Z. Keeping this
 * conversion in one place prevents the 3-D overlay from being rotated relative to
 * the 2-D detected outline.
 */
internal object CameraPoseFrames {
    /**
     * Returns the transform from physical-image-camera coordinates to
     * display-oriented-camera coordinates.
     *
     * Both input poses map their respective camera frames into the AR world, so
     * `inverse(display) * physical` maps a point from physical to display axes.
     */
    fun physicalToDisplayCamera(
        physicalCameraPose: Pose,
        displayOrientedCameraPose: Pose,
    ): Pose = displayOrientedCameraPose.inverse().compose(physicalCameraPose)

    /** Converts a board-to-physical-camera pose into board-to-display-camera axes. */
    fun boardInDisplayCamera(
        boardInPhysicalCamera: PlanarPoseSolver.Result,
        physicalToDisplayCamera: Pose,
    ): PlanarPoseSolver.Result {
        val boardPose = Pose(
            boardInPhysicalCamera.translation,
            boardInPhysicalCamera.quaternion,
        )
        val displayBoardPose = physicalToDisplayCamera.compose(boardPose)
        return PlanarPoseSolver.Result(
            displayBoardPose.translation.copyOf(),
            displayBoardPose.rotationQuaternion.copyOf(),
        )
    }
}
