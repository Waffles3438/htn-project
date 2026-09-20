package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.*
import org.junit.Test

class HybridBoardTrackerTest {
    private val board = PlanarPoseSolver.Result(floatArrayOf(0f, 0f, -0.5f), floatArrayOf(0f, 0f, 0f, 1f))
    private val edges = floatArrayOf(100f, 100f, 400f, 100f, 400f, 200f, 100f, 200f)
    private fun shifted(dx: Float) = FloatArray(8) { edges[it] + if (it % 2 == 0) dx else 0f }

    @Test fun `valid board calibrates immediately without an AR anchor or world tracking`() {
        val tracker = HybridBoardTracker()
        assertTrue(tracker.observeVisual(board, 1_000_000_000L))
        assertArrayEquals(board.translation, tracker.poseAt(null, null, 1_016_000_000L)!!.translation, 1e-6f)
        assertFalse(tracker.anchorUsable)
    }

    @Test fun `startup map drift neither blocks calibration nor slides the visible model`() {
        val tracker = HybridBoardTracker()
        for (i in 0..60) {
            val time = 1_000_000_000L + i * 100_000_000L
            assertTrue(tracker.observeVisual(board, time))
            tracker.checkAnchor(shifted(i * 4f), edges, time)
            val driftedAnchor = Pose.makeTranslation(i * 0.03f, 0f, -0.5f)
            assertArrayEquals(board.translation,
                tracker.poseAt(driftedAnchor, Pose.IDENTITY, time + 16_000_000L)!!.translation, 1e-6f)
        }
        assertFalse(tracker.anchorUsable)
    }

    @Test fun `first disagreement returns to measured board without waiting for a fatal error`() {
        val tracker = verifiedTracker()
        val time = 1_700_000_000L
        tracker.observeVisual(board, time)
        tracker.checkAnchor(shifted(12f), edges, time)
        assertFalse(tracker.anchorUsable)
        assertArrayEquals(board.translation,
            tracker.poseAt(Pose.makeTranslation(0.04f, 0f, -0.5f), Pose.IDENTITY, time)!!.translation, 1e-6f)
        for (i in 1..20) {
            tracker.observeVisual(board, time + i * 100_000_000L)
            tracker.checkAnchor(shifted(12f + i), edges, time + i * 100_000_000L)
        }
        assertNotNull(tracker.poseAt(null, null, time + 2_010_000_000L))
    }

    @Test fun `verified world anchor persists when angled view has no rectangle detection`() {
        val tracker = verifiedTracker()
        val anchor = Pose.makeTranslation(0f, 0f, -0.5f)
        tracker.checkAnchor(edges, null, 4_000_000_000L)
        assertTrue(tracker.anchorUsable)
        assertArrayEquals(board.translation, tracker.poseAt(anchor, Pose.IDENTITY, 4_000_000_000L)!!.translation, 1e-6f)
    }

    @Test fun `sudden corrupted world pose uses visual fallback instead of failing calibration`() {
        val tracker = verifiedTracker()
        tracker.observeVisual(board, 1_700_000_000L)
        val result = tracker.poseAt(Pose.makeTranslation(14000f, 0f, -0.5f), Pose.IDENTITY, 1_700_000_000L)
        assertArrayEquals(board.translation, result!!.translation, 1e-6f)
        assertFalse(tracker.anchorUsable)
        tracker.reset()
        assertNull(tracker.poseAt(null, null, 1_800_000_000L))
    }

    @Test fun `without either recent board evidence or verified world tracking no stale pose is shown`() {
        val tracker = HybridBoardTracker()
        tracker.observeVisual(board, 1_000_000_000L)
        assertNull(tracker.poseAt(null, null, 3_000_000_000L))
    }

    private fun verifiedTracker(): HybridBoardTracker {
        val tracker = HybridBoardTracker()
        for (i in 0..6) {
            val time = 1_000_000_000L + i * 100_000_000L
            tracker.observeVisual(board, time)
            tracker.checkAnchor(edges, edges, time)
            tracker.poseAt(Pose.makeTranslation(0f, 0f, -0.5f), Pose.IDENTITY, time)
        }
        assertTrue(tracker.anchorUsable)
        return tracker
    }
}
