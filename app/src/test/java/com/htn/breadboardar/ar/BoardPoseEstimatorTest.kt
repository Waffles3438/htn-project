package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class BoardPoseEstimatorTest {
    @Test
    fun `corner noise cannot relabel the selected board ends to lower fit error`() {
        val previous = project(45f, rollDegrees = 17f)
        val random = java.util.Random(1234L)
        repeat(150) { frame ->
            val noisy = FloatArray(8) { previous[it] + (random.nextFloat() - 0.5f) * 12f }
            val estimate = solve(reorder(noisy, frame % 4, if (frame % 2 == 0) 1 else -1), previous)
            assertArrayEquals("Corner identity must survive imperfect fits", noisy, estimate.orderedCorners, 0.0001f)
        }
    }
    @Test
    fun `reversed detector winding cannot turn the visible top into the underside`() {
        val corners = project(45f)
        val reversed = reorder(corners, 0, -1)

        // This is the regression: both reflected and correct correspondences fit
        // the same flat quad, although only one shows the model's visible top.
        val reflected = PlanarPoseSolver.solve(reversed, BOARD, FOCAL, PRINCIPAL)!!
        assertTrue(facingCamera(reflected) < 0f)

        val forward = solve(corners, corners)
        val corrected = solve(reversed, corners)

        assertArrayEquals(forward.orderedCorners, corrected.orderedCorners, 1e-4f)
        assertArrayEquals(topNormal(forward.result), topNormal(corrected.result), 1e-4f)
        assertTrue(facingCamera(corrected.result) > 0f)
        assertTrue(corrected.reprojectionErrorPx < 0.05f)
    }

    @Test
    fun `tilted views recover three dimensional orientation at 45 and 65 degrees`() {
        for (degrees in floatArrayOf(0f, 45f, 65f)) {
            val corners = project(degrees)
            val estimate = solve(corners, corners)
            val radians = Math.toRadians(degrees.toDouble()).toFloat()

            // Compute expected normal directly, independently of the solver's
            // homography/quaternion conversion. GL flips the CV Y and Z axes.
            assertArrayEquals(
                floatArrayOf(0f, -sin(radians), cos(radians)),
                topNormal(estimate.result),
                0.001f,
            )
            assertTrue("$degrees degree residual", estimate.reprojectionErrorPx < 0.05f)
            assertTrue("$degrees degree front face", facingCamera(estimate.result) > 0f)
        }
    }

    @Test
    fun `all cyclic detector starts preserve a selected corner correspondence`() {
        val corners = project(45f, rollDegrees = 28f)
        val selected = reorder(corners, 2, 1)
        for (direction in intArrayOf(1, -1)) {
            for (start in 0 until 4) {
                val estimate = solve(reorder(corners, start, direction), selected)
                assertArrayEquals(selected, estimate.orderedCorners, 1e-4f)
                assertEquals(0f, estimate.cornerContinuityPx, 1e-4f)
                assertTrue(facingCamera(estimate.result) > 0f)
            }
        }
    }

    @Test
    fun `initial orientation is deterministic regardless of contour enumeration`() {
        val corners = project(65f, rollDegrees = -20f)
        val initial = solve(corners)
        for (direction in intArrayOf(1, -1)) {
            for (start in 0 until 4) {
                val estimate = solve(reorder(corners, start, direction))
                assertArrayEquals(initial.orderedCorners, estimate.orderedCorners, 1e-4f)
            }
        }
    }

    @Test
    fun `small camera movement does not swap symmetrical board ends`() {
        var previous = project(40f, rollDegrees = -2f)
        for (degrees in 41..65) {
            val corners = project(degrees.toFloat(), rollDegrees = (degrees - 43) * 0.3f)
            val input = reorder(corners, degrees % 4, if (degrees % 2 == 0) 1 else -1)
            val estimate = solve(input, previous)
            assertArrayEquals(corners, estimate.orderedCorners, 1e-4f)
            assertTrue(estimate.cornerContinuityPx < 8f)
            assertTrue(facingCamera(estimate.result) > 0f)
            previous = estimate.orderedCorners
        }
    }

    @Test
    fun `invalid and non-convex quads are rejected before pose solving`() {
        val valid = project(0f)
        val invalid = listOf(
            FloatArray(8) { 100f },
            floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f),
            floatArrayOf(0f, 0f, 100f, 0f, 25f, 25f, 0f, 100f),
            floatArrayOf(0f, 0f, 100f, 100f, 100f, 0f, 0f, 100f),
            valid.copyOf().also { it[2] = Float.NaN },
            valid.copyOf().also { it[2] = Float.POSITIVE_INFINITY },
            valid.copyOf(6),
        )
        invalid.forEach { assertNull(BoardPoseEstimator.solve(it, BOARD, FOCAL, PRINCIPAL)) }
        assertNull(BoardPoseEstimator.solve(valid, reorder(BOARD, 0, -1), FOCAL, PRINCIPAL))
        assertNull(BoardPoseEstimator.solve(valid, BOARD, floatArrayOf(-520f, 520f), PRINCIPAL))
        assertNull(BoardPoseEstimator.solve(valid, BOARD, floatArrayOf(0f, 520f), PRINCIPAL))
        assertNull(BoardPoseEstimator.solve(valid, BOARD, FOCAL, floatArrayOf(Float.NaN, 240f)))
        assertNull(BoardPoseEstimator.solve(valid, BOARD, FOCAL, PRINCIPAL, floatArrayOf(1f)))
    }

    @Test
    fun `negative image coordinates remain valid for partially offscreen projection`() {
        val corners = project(45f, offsetX = -0.4f)
        assertTrue(corners[0] < 0f)
        val estimate = solve(corners, corners)
        assertTrue(estimate.reprojectionErrorPx < 0.05f)
        assertTrue(facingCamera(estimate.result) > 0f)
    }

    private fun solve(corners: FloatArray, previous: FloatArray? = null): BoardPoseEstimator.Estimate {
        val estimate = BoardPoseEstimator.solve(corners, BOARD, FOCAL, PRINCIPAL, previous)
        assertNotNull(estimate)
        return estimate!!
    }

    private fun topNormal(result: PlanarPoseSolver.Result): FloatArray =
        Pose(result.translation, result.quaternion).rotateVector(floatArrayOf(0f, 0f, -1f))

    private fun facingCamera(result: PlanarPoseSolver.Result): Float {
        val center = Pose(result.translation, result.quaternion)
            .transformPoint(floatArrayOf(0.165f / 2f, 0.055f / 2f, 0f))
        val normal = topNormal(result)
        return -(normal[0] * center[0] + normal[1] * center[1] + normal[2] * center[2])
    }

    /** Independent CV pinhole projection with X tilt followed by image-plane roll. */
    private fun project(degrees: Float, rollDegrees: Float = 0f, offsetX: Float = -0.05f): FloatArray {
        val tilt = Math.toRadians(degrees.toDouble())
        val roll = Math.toRadians(rollDegrees.toDouble())
        return FloatArray(8).also { corners ->
            for (i in 0 until 4) {
                val x = BOARD[i * 2].toDouble()
                val y = cos(tilt) * BOARD[i * 2 + 1]
                val z = sin(tilt) * BOARD[i * 2 + 1] + 0.4
                val cameraX = cos(roll) * x - sin(roll) * y + offsetX
                val cameraY = sin(roll) * x + cos(roll) * y - 0.02
                corners[i * 2] = (FOCAL[0] * cameraX / z + PRINCIPAL[0]).toFloat()
                corners[i * 2 + 1] = (FOCAL[1] * cameraY / z + PRINCIPAL[1]).toFloat()
            }
        }
    }

    private fun reorder(corners: FloatArray, start: Int, direction: Int): FloatArray =
        FloatArray(8) { corners[((start + direction * (it / 2) + 4) % 4) * 2 + it % 2] }

    private companion object {
        val BOARD = floatArrayOf(0f, 0f, 0.165f, 0f, 0.165f, 0.055f, 0f, 0.055f)
        val FOCAL = floatArrayOf(520f, 520f)
        val PRINCIPAL = floatArrayOf(320f, 240f)
    }
}
