package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CameraPoseFramesTest {
    @Test
    fun `converts physical image axes into portrait display axes`() {
        val halfQuarterTurn = (Math.PI / 4.0).toFloat()
        val physicalCamera = Pose.makeTranslation(0f, 0f, 0f)
        // World-from-display is +90 degrees around Z. Therefore display-from-
        // physical is -90 degrees, which is the portrait mismatch this prevents.
        val displayCamera = Pose(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, 0f, sin(halfQuarterTurn), cos(halfQuarterTurn)),
        )
        val rawBoard = PlanarPoseSolver.Result(
            floatArrayOf(0.10f, 0.20f, -0.50f),
            floatArrayOf(0f, 0f, 0f, 1f),
        )

        val result = CameraPoseFrames.boardInDisplayCamera(
            rawBoard,
            CameraPoseFrames.physicalToDisplayCamera(physicalCamera, displayCamera),
        )

        assertArrayEquals(floatArrayOf(0.20f, -0.10f, -0.50f), result.translation, 1e-5f)
        assertArrayEquals(
            floatArrayOf(0f, 0f, -sin(halfQuarterTurn), cos(halfQuarterTurn)),
            result.quaternion,
            1e-5f,
        )
    }

    @Test
    fun `identity display pose preserves a physical board pose`() {
        val rawBoard = PlanarPoseSolver.Result(
            floatArrayOf(-0.17f, 0.05f, -0.65f),
            floatArrayOf(0.1f, -0.2f, 0.3f, 0.92736185f),
        )

        val result = CameraPoseFrames.boardInDisplayCamera(
            rawBoard,
            CameraPoseFrames.physicalToDisplayCamera(
                Pose.makeTranslation(0f, 0f, 0f),
                Pose.makeTranslation(0f, 0f, 0f),
            ),
        )

        assertArrayEquals(rawBoard.translation, result.translation, 1e-6f)
        assertArrayEquals(rawBoard.quaternion, result.quaternion, 1e-6f)
    }
}
