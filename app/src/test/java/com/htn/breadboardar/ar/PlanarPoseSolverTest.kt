package com.htn.breadboardar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthesises a known board pose, projects its corners through a pinhole model, then
 * checks the solver recovers that pose. A round trip is the only practical way to
 * catch sign and convention mistakes in this maths, which otherwise produce poses
 * that look plausible but sit mirrored or behind the camera.
 */
class PlanarPoseSolverTest {

    @Test
    fun `recovers distance to a board viewed head on`() {
        val distance = 0.35f
        val corners = project(rotationAboutX(0f), floatArrayOf(0f, 0f, distance))

        val result = PlanarPoseSolver.solve(corners, BOARD, FOCAL, PRINCIPAL)

        assertNotNull(result)
        // ARCore's camera looks down -Z, so a board in front has negative Z.
        assertEquals(-distance, result!!.translation[2], 0.002f)
    }

    @Test
    fun `reprojects a tilted board onto its original corners`() {
        val corners = project(rotationAboutX(0.45f), floatArrayOf(0.03f, -0.02f, 0.4f))

        val result = PlanarPoseSolver.solve(corners, BOARD, FOCAL, PRINCIPAL)

        assertNotNull(result)
        val error = PlanarPoseSolver.reprojectionErrorPx(corners, BOARD, FOCAL, PRINCIPAL, result!!)
        assertTrue("worst corner reprojection error was $error px", error < 0.5f)
    }

    @Test
    fun `reprojects a board at a 45 degree viewing angle`() {
        // The app must be able to retain a valid solve while the learner tilts the
        // phone. This verifies the calibrated planar solve itself has no 45-degree
        // limitation; real-camera loss at that angle must therefore be handled by
        // candidate selection or the AR anchor fallback, not by accepting bad poses.
        val corners = project(
            rotationAboutX((Math.PI / 4.0).toFloat()),
            floatArrayOf(0.025f, -0.018f, 0.42f),
        )

        val result = PlanarPoseSolver.solve(corners, BOARD, FOCAL, PRINCIPAL)

        assertNotNull(result)
        val error = PlanarPoseSolver.reprojectionErrorPx(corners, BOARD, FOCAL, PRINCIPAL, result!!)
        assertTrue("45-degree reprojection error was $error px", error < 0.5f)
    }

    @Test
    fun `reprojects a board rotated in its own plane`() {
        val corners = project(rotationAboutZ(0.6f), floatArrayOf(-0.01f, 0.015f, 0.3f))

        val result = PlanarPoseSolver.solve(corners, BOARD, FOCAL, PRINCIPAL)

        assertNotNull(result)
        val error = PlanarPoseSolver.reprojectionErrorPx(corners, BOARD, FOCAL, PRINCIPAL, result!!)
        assertTrue("worst corner reprojection error was $error px", error < 0.5f)
    }

    @Test
    fun `recovered rotation is orthonormal`() {
        val corners = project(rotationAboutX(0.3f), floatArrayOf(0f, 0f, 0.28f))

        val quaternion = PlanarPoseSolver.solve(corners, BOARD, FOCAL, PRINCIPAL)!!.quaternion

        val magnitude = sqrt(
            quaternion.fold(0f) { total, component -> total + component * component },
        )
        assertEquals(1f, magnitude, 1e-3f)
    }

    @Test
    fun `scaling the board dimensions scales the recovered distance`() {
        val corners = project(rotationAboutX(0f), floatArrayOf(0f, 0f, 0.35f))
        val doubled = FloatArray(BOARD.size) { BOARD[it] * 2f }

        val single = PlanarPoseSolver.solve(corners, BOARD, FOCAL, PRINCIPAL)!!
        val double = PlanarPoseSolver.solve(corners, doubled, FOCAL, PRINCIPAL)!!

        // Confirms the dimensions really do set metric scale: a board twice as big
        // producing the same image must be twice as far away.
        assertEquals(2f, double.translation[2] / single.translation[2], 0.02f)
    }

    @Test
    fun `rejects degenerate input`() {
        val collapsed = FloatArray(8) { 100f }
        assertNull(PlanarPoseSolver.solve(collapsed, BOARD, FOCAL, PRINCIPAL))
    }

    /** Projects the board corners using the CV convention: Y down, Z forward. */
    private fun project(rotation: FloatArray, translation: FloatArray): FloatArray {
        val corners = FloatArray(8)
        for (i in 0 until 4) {
            val x = BOARD[i * 2]
            val y = BOARD[i * 2 + 1]
            val cameraX = rotation[0] * x + rotation[1] * y + translation[0]
            val cameraY = rotation[3] * x + rotation[4] * y + translation[1]
            val cameraZ = rotation[6] * x + rotation[7] * y + translation[2]
            corners[i * 2] = FOCAL[0] * cameraX / cameraZ + PRINCIPAL[0]
            corners[i * 2 + 1] = FOCAL[1] * cameraY / cameraZ + PRINCIPAL[1]
        }
        return corners
    }

    private fun rotationAboutX(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(
            1f, 0f, 0f,
            0f, c, -s,
            0f, s, c,
        )
    }

    private fun rotationAboutZ(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(
            c, -s, 0f,
            s, c, 0f,
            0f, 0f, 1f,
        )
    }

    private companion object {
        /** Full-size breadboard, origin at one corner, long axis along X. */
        val BOARD = floatArrayOf(
            0f, 0f,
            0.165f, 0f,
            0.165f, 0.055f,
            0f, 0.055f,
        )
        val FOCAL = floatArrayOf(520f, 520f)
        val PRINCIPAL = floatArrayOf(320f, 240f)
    }
}
