package com.htn.breadboardar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PoseSmootherTest {
    private val smoother = PoseSmoother()

    @Test
    fun `holds sub-millimetre and sub-degree stationary noise`() {
        val previous = pose(0f, 0f, -0.5f, 0f)
        val fresh = pose(0.0007f, -0.0004f, -0.4997f, 0.2f)

        val filtered = smoother.smooth(previous, fresh)

        assertPoseTranslationEquals(previous, filtered, 1e-6f)
        assertAngleEquals(0f, angleBetween(previous.quaternion, filtered.quaternion), 1e-5f)
        assertFalse("filtered pose must not share the previous translation array", previous.translation === filtered.translation)
        assertFalse("filtered pose must not share the previous quaternion array", previous.quaternion === filtered.quaternion)
    }

    @Test
    fun `damps a small stationary correction rather than following it raw`() {
        val previous = pose(0f, 0f, -0.5f, 0f)
        val fresh = pose(0.004f, 0f, -0.5f, 2f)

        val filtered = smoother.smooth(previous, fresh)

        assertTrue("4 mm static correction should be damped", filtered.translation[0] in 0.0007f..0.0014f)
        assertTrue(
            "2 degree static correction should be damped",
            angleBetween(previous.quaternion, filtered.quaternion) in degreesToRadians(0.4f)..degreesToRadians(0.9f),
        )
    }

    @Test
    fun `catches up quickly after genuine phone movement`() {
        var filtered = pose(0f, 0f, -0.5f, 0f)
        val moved = pose(0.03f, 0f, -0.5f, 12f)

        repeat(6) {
            filtered = smoother.smooth(filtered, moved)
        }

        assertTrue("translation should be within 5 percent after six movement passes", filtered.translation[0] > 0.0285f)
        assertTrue(
            "rotation should be within 5 percent after six movement passes",
            angleBetween(filtered.quaternion, moved.quaternion) < degreesToRadians(0.6f),
        )
    }

    @Test
    fun `treats opposite quaternion signs as the same rotation`() {
        val previous = pose(0f, 0f, -0.5f, 0f)
        val rawFresh = pose(0.02f, 0f, -0.5f, 20f)
        val oppositeSignFresh = PlanarPoseSolver.Result(
            rawFresh.translation.copyOf(),
            FloatArray(4) { index -> -rawFresh.quaternion[index] },
        )

        val fromRaw = smoother.smooth(previous, rawFresh)
        val fromOppositeSign = smoother.smooth(previous, oppositeSignFresh)

        assertPoseTranslationEquals(fromRaw, fromOppositeSign, 1e-6f)
        assertAngleEquals(0f, angleBetween(fromRaw.quaternion, fromOppositeSign.quaternion), 1e-5f)
    }

    @Test
    fun `returns a normalized quaternion`() {
        val previous = PlanarPoseSolver.Result(
            floatArrayOf(0f, 0f, -0.5f),
            floatArrayOf(0f, 0f, 0f, 2f),
        )
        val fresh = PlanarPoseSolver.Result(
            floatArrayOf(0.02f, 0f, -0.5f),
            floatArrayOf(0f, 0f, sin(degreesToRadians(10f)), cos(degreesToRadians(10f))),
        )

        val filtered = smoother.smooth(previous, fresh)

        val magnitude = sqrt(filtered.quaternion.sumOf { (it * it).toDouble() }.toFloat())
        assertEquals(1f, magnitude, 1e-5f)
    }

    private fun pose(x: Float, y: Float, z: Float, zRotationDegrees: Float): PlanarPoseSolver.Result {
        val halfAngle = degreesToRadians(zRotationDegrees) / 2f
        return PlanarPoseSolver.Result(
            floatArrayOf(x, y, z),
            floatArrayOf(0f, 0f, sin(halfAngle), cos(halfAngle)),
        )
    }

    private fun assertPoseTranslationEquals(
        expected: PlanarPoseSolver.Result,
        actual: PlanarPoseSolver.Result,
        tolerance: Float,
    ) {
        for (index in 0 until 3) assertEquals(expected.translation[index], actual.translation[index], tolerance)
    }

    private fun assertAngleEquals(expected: Float, actual: Float, tolerance: Float) {
        assertEquals(expected, actual, tolerance)
    }

    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (index in 0 until 4) dot += a[index] * b[index]
        return 2f * kotlin.math.acos(abs(dot).coerceIn(0f, 1f))
    }

    private fun degreesToRadians(degrees: Float): Float = degrees * Math.PI.toFloat() / 180f
}
