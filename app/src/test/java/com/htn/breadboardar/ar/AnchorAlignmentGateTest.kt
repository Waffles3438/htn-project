package com.htn.breadboardar.ar

import org.junit.Assert.*
import org.junit.Test

class AnchorAlignmentGateTest {
    private val board = floatArrayOf(100f, 100f, 400f, 100f, 400f, 200f, 100f, 200f)
    private fun shifted(dx: Float) = FloatArray(8) { board[it] + if (it % 2 == 0) dx else 0f }
    private fun verify(gate: AnchorAlignmentGate) {
        for (i in 0..5) gate.observe(board, board, 1_000_000_000L + i * 100_000_000L)
        assertTrue(gate.verified)
    }

    @Test fun `new anchor cannot immediately show the orange outline`() {
        val gate = AnchorAlignmentGate()
        gate.observe(board, board, 1_000_000_000L)
        assertFalse(gate.verified)
        for (i in 1..5) gate.observe(board, board, 1_000_000_000L + i * 33_000_000L)
        assertFalse("Six frames without enough elapsed time are not stable calibration", gate.verified)
        gate.observe(board, board, 1_500_000_000L)
        assertFalse("A long gap must restart the window", gate.verified)
    }

    @Test fun `gradual startup drift that passes frame jump guard never becomes calibrated`() {
        val gate = AnchorAlignmentGate()
        for (i in 0..30) gate.observe(shifted(i * 3f), board, 1_000_000_000L + i * 100_000_000L)
        assertFalse(gate.verified)
    }

    @Test fun `sustained drift after verification invalidates anchor rather than moving it`() {
        val gate = AnchorAlignmentGate()
        verify(gate)
        for (i in 1..5) gate.observe(shifted(i * 4f), board, 1_500_000_000L + i * 100_000_000L)
        assertTrue(gate.failed)
        gate.observe(board, board, 2_100_000_000L)
        assertTrue("Bad anchors require recalibration", gate.failed)
        gate.reset()
        assertFalse(gate.failed)
        assertFalse(gate.verified)
    }

    @Test fun `missed contours while orbiting do not expire a verified anchor`() {
        val gate = AnchorAlignmentGate()
        verify(gate)
        repeat(100) { gate.observe(board, null, 2_000_000_000L + it * 100_000_000L) }
        assertTrue(gate.verified)
        assertFalse(gate.failed)
    }

    @Test fun `one noisy contour does not invalidate stable tracking`() {
        val gate = AnchorAlignmentGate()
        verify(gate)
        gate.observe(board, shifted(30f), 1_600_000_000L)
        gate.observe(board, board, 1_700_000_000L)
        assertTrue(gate.verified)
        assertFalse(gate.failed)
    }

    @Test fun `perspective and contour start changes can verify without changing calibrated identity`() {
        val gate = AnchorAlignmentGate()
        for (i in 0..10) {
            val perspective = floatArrayOf(100f + i, 100f, 400f - i, 100f, 400f, 200f, 100f, 200f)
            val cyclic = FloatArray(8) { perspective[(it + 2) % 8] }
            gate.observe(perspective, cyclic, 1_000_000_000L + i * 100_000_000L)
        }
        assertTrue(gate.verified)
        assertFalse(gate.failed)
    }

    @Test fun `unrelated contours and a power rail are not evidence of anchor drift`() {
        val rail = floatArrayOf(100f, 100f, 400f, 100f, 400f, 110f, 100f, 110f)
        assertNull(AnchorAlignmentGate.matchingOutline(board, listOf(shifted(500f), rail)))
        assertArrayEquals(board, AnchorAlignmentGate.matchingOutline(board, listOf(shifted(500f), board)), 0f)
    }
}
