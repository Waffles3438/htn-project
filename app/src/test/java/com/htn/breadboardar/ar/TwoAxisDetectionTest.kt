package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

class TwoAxisDetectionTest {
    @Test
    fun `detected board recovers tilt on both camera axes`() {
        for (rollDegrees in listOf(0, 25, 90)) for (axis in 0..1) for (degrees in listOf(-60, -45, -25, 25, 45, 60)) {
            val a = Math.toRadians(degrees.toDouble())
            val roll = Math.toRadians(rollDegrees.toDouble())
            val geometry = BoardGeometry.STANDARD
            val board = geometry.corners()
            val corners = FloatArray(8)
            for (i in 0..3) {
                val x = board[i * 2] - geometry.lengthMeters / 2
                val y = board[i * 2 + 1] - geometry.widthMeters / 2
                val cx = if (axis == 0) x.toDouble() else cos(a) * x
                val cy = if (axis == 0) cos(a) * y else y.toDouble()
                val cz = 0.28 + if (axis == 0) sin(a) * y else -sin(a) * x
                corners[i * 2] = (700 * (cos(roll) * cx - sin(roll) * cy) / cz + 320).toFloat()
                corners[i * 2 + 1] = (700 * (sin(roll) * cx + cos(roll) * cy) / cz + 240).toFloat()
            }
            val luma = ByteArray(640 * 480) { 30 }
            for (y in 0 until 480) for (x in 0 until 640) {
                val inside = (0..3).all { i ->
                    val j = (i + 1) % 4
                    (corners[j * 2] - corners[i * 2]) * (y + .5f - corners[i * 2 + 1]) -
                        (corners[j * 2 + 1] - corners[i * 2 + 1]) * (x + .5f - corners[i * 2]) >= 0
                }
                if (inside) luma[y * 640 + x] = 220.toByte()
            }
            val detected = RectangleDetector().detect(luma, 640, 480, 640, lenient = true)
            assertTrue("axis=$axis angle=$degrees no outline", detected.isNotEmpty())
            assertTrue("axis=$axis angle=$degrees must have measured corners", detected.first().hasMeasuredCorners)
            val fit = BoardGeometry.fit(detected.first().corners, floatArrayOf(700f, 700f),
                floatArrayOf(320f, 240f), geometry)!!
            val normal = Pose(fit.estimate.result.translation, fit.estimate.result.quaternion)
                .rotateVector(floatArrayOf(0f, 0f, -1f))
            val nx = if (axis == 0) 0.0 else -sin(a)
            val ny = if (axis == 0) sin(a) else 0.0
            val expected = floatArrayOf((cos(roll) * nx - sin(roll) * ny).toFloat(),
                -(sin(roll) * nx + cos(roll) * ny).toFloat(), cos(a).toFloat())
            val alignment = (0..2).sumOf { (normal[it] * expected[it]).toDouble() }.coerceIn(-1.0, 1.0)
            val miss = Math.toDegrees(acos(alignment))
            assertTrue("roll=$rollDegrees axis=$axis angle=$degrees normal miss=$miss corners=${detected.first().corners.contentToString()}", miss < 5)
        }
    }
}
