package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VisualBoardTrackerTest {
    @Test
    fun `brief flat-pose bursts cannot flip a tilted board at startup`() {
        val tracker = VisualBoardTracker()
        val tilted = boardAt(Math.PI.toFloat() / 4f)
        val flat = boardAt()
        var time = 1_000_000_000L
        tracker.observe(tilted, null, time)
        repeat(10) {
            // Recording pattern: two flattened frames, then a correct detection.
            repeat(2) {
                time += 33_000_000L
                assertNull(tracker.observe(flat, null, time))
                val pose = tracker.poseAt(null, time)!!
                assertArrayEquals(tilted.quaternion, pose.quaternion, 0.0001f)
            }
            time += 33_000_000L
            assertNotNull(tracker.observe(tilted, null, time))
        }
    }

    @Test
    fun `large genuine tilt is accepted after repeated agreement`() {
        val tracker = VisualBoardTracker()
        var time = 1_000_000_000L
        tracker.observe(boardAt(), null, time)
        val tilted = boardAt(Math.PI.toFloat() / 3f)
        repeat(4) {
            time += 33_000_000L
            assertNull(tracker.observe(tilted, null, time))
        }
        time += 33_000_000L
        assertNotNull(tracker.observe(tilted, null, time))
        repeat(12) { time += 33_000_000L; tracker.observe(tilted, null, time) }
        assertArrayEquals(tilted.quaternion, tracker.poseAt(null, time)!!.quaternion, 0.01f)
    }

    @Test
    fun `smooth deliberate tilt is accepted without waiting for jump confirmation`() {
        val tracker = VisualBoardTracker()
        var time = 1_000_000_000L
        for (degrees in 0..65 step 5) {
            assertNotNull(tracker.observe(boardAt(degrees * Math.PI.toFloat() / 180f), null, time))
            time += 33_000_000L
        }
    }

    @Test
    fun `rejected outliers do not keep an old pose alive forever`() {
        val tracker = VisualBoardTracker()
        tracker.observe(boardAt(), null, 1_000_000_000L)
        for (i in 1..8) {
            val alternating = boardAt(if (i % 2 == 0) 0.7f else -0.7f)
            assertNull(tracker.observe(alternating, null, 1_000_000_000L + i * 100_000_000L))
        }
        assertNull(tracker.poseAt(null, 2_000_000_000L))
        tracker.reset()
        assertNotNull(tracker.observe(boardAt(0.7f), null, 2_100_000_000L))
    }

    @Test
    fun `metres of reported world drift cannot drag a visible board away`() {
        val tracker = VisualBoardTracker()
        val visual = boardAt()
        var time = 1_000_000_000L
        // Distances seen in the Samsung trace, despite a board still close to it.
        for (drift in floatArrayOf(0f, 0.3f, 1.8f, 5.4f, 9.1f, 66f, 1814f, 14000f)) {
            val camera = Pose.makeTranslation(drift, -drift / 2f, drift)
            tracker.observe(visual, camera, time)
            val rendered = tracker.poseAt(camera, time + 16_000_000L)!!
            assertArrayEquals(visual.translation, rendered.translation, 1e-5f)
            assertArrayEquals(visual.quaternion, rendered.quaternion, 1e-5f)
            time += 33_000_000L
        }
    }

    @Test
    fun `sudden tracking jump between camera detections is rejected even after healthy tracking`() {
        val tracker = VisualBoardTracker()
        val visual = boardAt()
        var time = 1_000_000_000L
        repeat(10) {
            tracker.observe(visual, Pose.IDENTITY, time)
            time += 33_000_000L
        }
        val jumped = tracker.poseAt(Pose.makeTranslation(0f, 0f, 14f), time)!!
        assertArrayEquals(visual.translation, jumped.translation, 1e-5f)
        assertEquals(-0.3f, jumped.translation[2], 1e-5f)
    }

    @Test
    fun `visible board still renders while ARCore world tracking is paused`() {
        val tracker = VisualBoardTracker()
        val visual = boardAt()
        tracker.observe(visual, null, 1_000_000_000L)
        assertNotNull(tracker.poseAt(null, 1_033_000_000L))
        assertArrayEquals(visual.translation, tracker.poseAt(null, 1_033_000_000L)!!.translation, 1e-5f)
    }

    @Test
    fun `tilted visual measurements still change the 3D perspective without world tracking`() {
        val tracker = VisualBoardTracker()
        var time = 1_000_000_000L
        for (degrees in floatArrayOf(0f, 45f, 65f)) {
            val radians = degrees * Math.PI.toFloat() / 180f
            val tilted = boardAt(radians)
            repeat(12) {
                tracker.observe(tilted, null, time)
                time += 33_000_000L
            }
            val rendered = tracker.poseAt(null, time)!!
            val normal = Pose(rendered.translation, rendered.quaternion).rotateVector(floatArrayOf(0f, 0f, -1f))
            assertArrayEquals(floatArrayOf(0f, -sin(radians), cos(radians)), normal, 0.015f)
        }
    }

    @Test
    fun `old visual measurement expires and a new measurement reacquires it`() {
        val tracker = VisualBoardTracker()
        tracker.observe(boardAt(), Pose.IDENTITY, 1_000_000_000L)
        assertNull(tracker.poseAt(Pose.IDENTITY, 2_000_000_000L))
        tracker.observe(boardAt(), null, 2_033_000_000L)
        assertNotNull(tracker.poseAt(null, 2_050_000_000L))
        tracker.reset()
        assertNull(tracker.poseAt(Pose.IDENTITY, 2_060_000_000L))
    }

    @Test
    fun `portrait conversion cannot inherit corrupted world translations`() {
        val physical = Pose.makeTranslation(-11000290f, 19893592f, -154420192f)
        val roll = Pose.makeRotation(0f, 0f, sin(Math.PI.toFloat() / 4f), cos(Math.PI.toFloat() / 4f))
        val display = physical.compose(roll)
        val conversion = CameraPoseFrames.physicalToDisplayCamera(physical, display)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), conversion.translation, 1e-6f)
        val board = CameraPoseFrames.boardInDisplayCamera(boardAt(), conversion)
        assertEquals(-0.3f, board.translation[2], 1e-5f)
    }

    private fun boardAt(tilt: Float = 0f) = PlanarPoseSolver.Result(
        floatArrayOf(-0.08f, 0.03f, -0.3f),
        floatArrayOf(cos(tilt / 2f), 0f, 0f, -sin(tilt / 2f)),
    )
}
