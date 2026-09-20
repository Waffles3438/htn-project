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
     * Captures the board in the AR world using the camera pose from the same image
     * as the visual solve. Keep this world pose (or an ARCore anchor made from it)
     * fixed as the phone moves; replaying an old camera-relative solve would make
     * the model follow the phone instead of revealing its sides.
     */
    fun boardInWorld(
        boardInPhysicalCamera: PlanarPoseSolver.Result,
        physicalCameraInWorld: Pose,
    ): Pose = physicalCameraInWorld.compose(
        Pose(boardInPhysicalCamera.translation, boardInPhysicalCamera.quaternion),
    )

    /**
     * Expresses an anchored board in the current camera axes. Pass the current
     * physical camera pose for image projection, or its display-oriented pose for
     * rendering with ARCore's display projection.
     */
    fun boardInCamera(boardInWorld: Pose, cameraInWorld: Pose): PlanarPoseSolver.Result {
        val cameraBoardPose = cameraInWorld.inverse().compose(boardInWorld)
        return PlanarPoseSolver.Result(
            cameraBoardPose.translation.copyOf(),
            cameraBoardPose.rotationQuaternion.copyOf(),
        )
    }

    /**
     * Returns the transform from physical-image-camera coordinates to
     * display-oriented-camera coordinates.
     *
     * Both input poses map their respective camera frames into the AR world, so
     * `inverse(display) * physical` maps a point from physical to display axes.
     * Both cameras have the same origin: use rotation only to avoid cancellation
     * errors when a corrupted world map reports very large translations.
     */
    fun physicalToDisplayCamera(
        physicalCameraPose: Pose,
        displayOrientedCameraPose: Pose,
    ): Pose = Pose(floatArrayOf(0f, 0f, 0f), displayOrientedCameraPose.rotationQuaternion)
        .inverse().compose(Pose(floatArrayOf(0f, 0f, 0f), physicalCameraPose.rotationQuaternion))

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
