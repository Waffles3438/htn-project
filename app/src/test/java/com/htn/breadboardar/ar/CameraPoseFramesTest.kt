package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.hypot

class CameraPoseFramesTest {
    @Test
    fun `fixed world board changes perspective as camera orbits through 45 and 65 degrees`() {
        val worldBoard = uprightWorldBoard()
        var previousProjectedWidth = Float.POSITIVE_INFINITY
        for (degrees in floatArrayOf(0f, 45f, 65f)) {
            val radians = degrees * Math.PI.toFloat() / 180f
            val camera = orbitCamera(radians)
            val result = CameraPoseFrames.boardInCamera(worldBoard, camera)

            // Independent analytic orbit expectation: the board stays at the
            // origin and the optical axis continues to point at its centre.
            assertArrayEquals(
                floatArrayOf(-LENGTH / 2f * cos(radians), WIDTH / 2f, -DISTANCE - LENGTH / 2f * sin(radians)),
                result.translation,
                1e-5f,
            )
            assertArrayEquals(
                floatArrayOf(-sin(radians), 0f, cos(radians)),
                topNormal(result),
                1e-5f,
            )
            assertTopFacesCamera(result)

            val projected = PlanarPoseSolver.projectBoardCorners(BOARD, FOCAL, PRINCIPAL, result)
            assertNotNull(projected)
            val corners = projected!!
            val projectedWidth = sideLength(corners, 0)
            assertTrue("long edge must foreshorten at $degrees degrees", projectedWidth < previousProjectedWidth)
            previousProjectedWidth = projectedWidth
            if (degrees > 0f) {
                assertTrue(
                    "near and far short edges must differ under perspective at $degrees degrees",
                    abs(sideLength(corners, 1) - sideLength(corners, 3)) > 1f,
                )
            }

            // Reconstructing from each camera must recover the same world board.
            val reconstructed = CameraPoseFrames.boardInWorld(result, camera)
            assertSameWorldFrame(worldBoard, reconstructed)
        }
    }

    @Test
    fun `camera translation moves the view without moving the anchored board`() {
        val worldBoard = uprightWorldBoard()
        val movedCamera = Pose.makeTranslation(0.12f, -0.07f, 0.55f)

        val result = CameraPoseFrames.boardInCamera(worldBoard, movedCamera)

        assertArrayEquals(
            floatArrayOf(-LENGTH / 2f - 0.12f, WIDTH / 2f + 0.07f, -0.55f),
            result.translation,
            1e-6f,
        )
        assertTopFacesCamera(result)
        assertSameWorldFrame(worldBoard, CameraPoseFrames.boardInWorld(result, movedCamera))
    }

    @Test
    fun `orbiting portrait camera uses display axes without turning the model upside down`() {
        val worldBoard = uprightWorldBoard()
        val roll = Pose.makeRotation(0f, 0f, sin(Math.PI.toFloat() / 4f), cos(Math.PI.toFloat() / 4f))
        for (degrees in floatArrayOf(0f, 45f, 65f)) {
            val physicalCamera = orbitCamera(degrees * Math.PI.toFloat() / 180f)
            val displayCamera = physicalCamera.compose(roll)
            val physicalResult = CameraPoseFrames.boardInCamera(worldBoard, physicalCamera)
            val displayResult = CameraPoseFrames.boardInCamera(worldBoard, displayCamera)
            val converted = CameraPoseFrames.boardInDisplayCamera(
                physicalResult,
                CameraPoseFrames.physicalToDisplayCamera(physicalCamera, displayCamera),
            )

            assertArrayEquals(
                floatArrayOf(physicalResult.translation[1], -physicalResult.translation[0], physicalResult.translation[2]),
                displayResult.translation,
                1e-5f,
            )
            assertSameWorldFrame(
                Pose(converted.translation, converted.quaternion),
                Pose(displayResult.translation, displayResult.quaternion),
            )
            assertTopFacesCamera(displayResult)
        }
    }

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

    private fun uprightWorldBoard(): Pose = Pose(
        floatArrayOf(-LENGTH / 2f, WIDTH / 2f, 0f),
        // Solver board +Y runs down the printed face, and -Z is its top normal.
        floatArrayOf(1f, 0f, 0f, 0f),
    )

    private fun orbitCamera(radians: Float): Pose = Pose(
        floatArrayOf(DISTANCE * sin(radians), 0f, DISTANCE * cos(radians)),
        floatArrayOf(0f, sin(radians / 2f), 0f, cos(radians / 2f)),
    )

    private fun topNormal(result: PlanarPoseSolver.Result): FloatArray =
        Pose(result.translation, result.quaternion).rotateVector(floatArrayOf(0f, 0f, -1f))

    private fun assertTopFacesCamera(result: PlanarPoseSolver.Result) {
        val top = topNormal(result)
        val towardCamera = -top.indices.sumOf { (top[it] * result.translation[it]).toDouble() }
        assertTrue("printed top normal must face the camera, got $towardCamera", towardCamera > 0.0)
    }

    private fun assertSameWorldFrame(expected: Pose, actual: Pose) {
        // Compare transformed geometry, not quaternion sign: q and -q are equal rotations.
        for (point in arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(LENGTH, WIDTH, -0.01f))) {
            assertArrayEquals(expected.transformPoint(point), actual.transformPoint(point), 1e-5f)
        }
        assertArrayEquals(expected.xAxis, actual.xAxis, 1e-5f)
        assertArrayEquals(expected.yAxis, actual.yAxis, 1e-5f)
        assertArrayEquals(expected.zAxis, actual.zAxis, 1e-5f)
    }

    private fun sideLength(corners: FloatArray, side: Int): Float {
        val next = (side + 1) % 4
        return hypot(corners[next * 2] - corners[side * 2], corners[next * 2 + 1] - corners[side * 2 + 1])
    }

    private companion object {
        const val LENGTH = 0.165f
        const val WIDTH = 0.065f
        const val DISTANCE = 0.4f
        val BOARD = floatArrayOf(0f, 0f, LENGTH, 0f, LENGTH, WIDTH, 0f, WIDTH)
        val FOCAL = floatArrayOf(520f, 520f)
        val PRINCIPAL = floatArrayOf(320f, 240f)
    }
}
