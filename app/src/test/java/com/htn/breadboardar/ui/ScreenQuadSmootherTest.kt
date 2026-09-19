package com.htn.breadboardar.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScreenQuadSmootherTest {
    private val smoother = ScreenQuadSmoother()
    private val board = floatArrayOf(
        100f, 100f,
        300f, 100f,
        300f, 180f,
        100f, 180f,
    )

    @Test
    fun `holds subpixel stationary detector noise`() {
        val fresh = FloatArray(board.size) { index ->
            board[index] + if (index % 2 == 0) 0.8f else -0.6f
        }

        val filtered = smoother.smooth(board, fresh)

        assertArrayEquals(board, filtered, 1e-6f)
        assertTrue("filter must not expose its input array", board !== filtered)
    }

    @Test
    fun `strongly damps a small stationary outline wobble`() {
        val fresh = translated(board, 6f, 0f)

        val filtered = smoother.smooth(board, fresh)

        // A six-pixel detector wobble should render as around one to two pixels,
        // rather than jumping the entire orange outline by six pixels.
        assertTrue(filtered[0] - board[0] in 1f..2f)
        assertTrue(filtered[2] - board[2] in 1f..2f)
    }

    @Test
    fun `responds quickly when the board really moves`() {
        val fresh = translated(board, 30f, -10f)

        val filtered = smoother.smooth(board, fresh)

        assertTrue("large pan should follow within one update", filtered[0] - board[0] > 20f)
        assertTrue("large pan should follow within one update", filtered[1] - board[1] < -6f)
    }

    @Test
    fun `damps one noisy corner without moving the other corners`() {
        val fresh = board.copyOf()
        fresh[0] += 24f
        fresh[1] -= 18f

        val filtered = smoother.smooth(board, fresh)

        assertTrue(filtered[0] - board[0] < 10f)
        assertTrue(filtered[1] - board[1] > -8f)
        assertArrayEquals(floatArrayOf(300f, 100f), floatArrayOf(filtered[2], filtered[3]), 1e-6f)
    }

    @Test
    fun `keeps alternating small detector shimmer close to the last stable outline`() {
        var filtered = board.copyOf()

        repeat(16) { pass ->
            val offset = if (pass % 2 == 0) 3f else -3f
            filtered = smoother.smooth(filtered, translated(board, offset, -offset))
        }

        assertTrue(abs(filtered[0] - board[0]) < 1.2f)
        assertTrue(abs(filtered[1] - board[1]) < 1.2f)
    }

    @Test
    fun `catches up during a sustained diagonal pan`() {
        var filtered = board.copyOf()
        repeat(4) { pass ->
            filtered = smoother.smooth(
                filtered,
                translated(board, (pass + 1) * 10f, -(pass + 1) * 6f),
            )
        }

        assertTrue(abs(filtered[0] - (board[0] + 40f)) < 3f)
        assertTrue(abs(filtered[1] - (board[1] - 24f)) < 3f)
    }

    private fun translated(points: FloatArray, x: Float, y: Float): FloatArray =
        FloatArray(points.size) { index -> points[index] + if (index % 2 == 0) x else y }
}
