package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AnchoredBoardTrackerTest {
    private fun rotation(degrees: Float, axis: Int): Pose {
        val half = degrees * Math.PI.toFloat() / 360f
        val q = floatArrayOf(0f, 0f, 0f, cos(half))
        q[axis] = sin(half)
        return Pose(floatArrayOf(0f, 0f, 0f), q)
    }

    @Test
    fun `full orbit without any detections keeps the model on its calibrated world side`() {
        val tracker = AnchoredBoardTracker()
        val anchor = Pose.makeTranslation(0.1f, 0.2f, 0f)
        // The renderer places the model north in BOARD coordinates, not screen coordinates.
        val north = floatArrayOf(0.0825f, -0.06f, -0.01f)
        val fixedWorldPosition = anchor.transformPoint(north)
        for (degrees in 0..360) {
            val camera = anchor.compose(rotation(degrees.toFloat(), 1)).compose(Pose.makeTranslation(0f, 0f, 0.5f))
            val board = tracker.poseAt(anchor, camera, 1_000_000_000L + degrees * 33_000_000L)
            assertNotNull("Orbit angle $degrees", board)
            val modelInCamera = Pose(board!!.translation, board.quaternion).transformPoint(north)
            assertArrayEquals(fixedWorldPosition, camera.transformPoint(modelInCamera), 1e-5f)
        }
        assertFalse(tracker.needsRecalibration)
    }

    @Test
    fun `tilt along either phone axis changes perspective without moving the board`() {
        for (axis in 0..1) {
            val tracker = AnchoredBoardTracker()
            for (degrees in 0..65) {
                val camera = rotation(degrees.toFloat(), axis).compose(Pose.makeTranslation(0f, 0f, 0.5f))
                val board = tracker.poseAt(Pose.IDENTITY, camera, 1_000_000_000L + degrees * 33_000_000L)!!
                val reconstructed = camera.compose(Pose(board.translation, board.quaternion))
                assertArrayEquals(Pose.IDENTITY.translation, reconstructed.translation, 1e-5f)
                assertArrayEquals(Pose.IDENTITY.rotationQuaternion, reconstructed.rotationQuaternion, 1e-5f)
                if (degrees == 65) assertTrue(kotlin.math.abs(board.quaternion[axis]) > 0.5f)
            }
        }
    }

    @Test
    fun `anchor does not have the old nine hundred millisecond image expiry`() {
        val tracker = AnchoredBoardTracker()
        val camera = Pose.makeTranslation(0f, 0f, 0.5f)
        assertNotNull(tracker.poseAt(Pose.IDENTITY, camera, 1_000_000_000L))
        assertNotNull(tracker.poseAt(Pose.IDENTITY, camera, 120_000_000_000L))
    }

    @Test
    fun `paused tracking hides pose and healthy tracking resumes the same anchor`() {
        val tracker = AnchoredBoardTracker()
        val camera = Pose.makeTranslation(0f, 0f, 0.5f)
        val original = tracker.poseAt(Pose.IDENTITY, camera, 1_000_000_000L)!!
        assertNull(tracker.poseAt(null, camera, 1_033_000_000L))
        assertNull(tracker.poseAt(Pose.IDENTITY, null, 1_066_000_000L))
        val resumed = tracker.poseAt(Pose.IDENTITY, camera, 1_099_000_000L)!!
        assertArrayEquals(original.translation, resumed.translation, 1e-5f)
    }

    @Test
    fun `sudden map jump latches failure until recalibration`() {
        val tracker = AnchoredBoardTracker()
        val camera = Pose.makeTranslation(0f, 0f, 0.5f)
        tracker.poseAt(Pose.IDENTITY, camera, 1_000_000_000L)
        assertNull(tracker.poseAt(Pose.IDENTITY, Pose.makeTranslation(0.4f, 0f, 0.5f), 1_033_000_000L))
        assertTrue(tracker.needsRecalibration)
        assertNull(tracker.poseAt(Pose.IDENTITY, camera, 1_066_000_000L))
        tracker.reset()
        assertNotNull(tracker.poseAt(Pose.IDENTITY, camera, 1_099_000_000L))
    }

    @Test
    fun `coherent anchor and camera world rebase does not trigger drift guard`() {
        val tracker = AnchoredBoardTracker()
        val camera = Pose.makeTranslation(0f, 0f, 0.5f)
        val original = tracker.poseAt(Pose.IDENTITY, camera, 1_000_000_000L)!!
        val rebase = Pose.makeTranslation(2f, 3f, 1f).compose(rotation(60f, 1))
        val rebased = tracker.poseAt(rebase, rebase.compose(camera), 1_033_000_000L)!!
        assertArrayEquals(original.translation, rebased.translation, 1e-5f)
        assertArrayEquals(original.quaternion, rebased.quaternion, 1e-5f)
    }

    @Test
    fun `corrupted and runaway world poses are rejected`() {
        for (position in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 14f, 14000f)) {
            assertNull(AnchoredBoardTracker().poseAt(
                Pose.IDENTITY, Pose.makeTranslation(position, 0f, 0.5f), 1_000_000_000L,
            ))
        }
    }
}
