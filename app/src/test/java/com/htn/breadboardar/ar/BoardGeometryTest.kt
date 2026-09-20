package com.htn.breadboardar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class BoardGeometryTest {
    private val focal = floatArrayOf(700f, 700f)
    private val principal = floatArrayOf(320f, 240f)

    @Test
    fun `calibration fits both full size board profiles without loosening residual limits`() {
        for (geometry in listOf(BoardGeometry.STANDARD, BoardGeometry.WIDE)) {
            val fit = BoardGeometry.fit(project(geometry, 0), focal, principal)!!
            assertEquals(geometry, fit.geometry)
            assertTrue(fit.estimate.reprojectionErrorPx < 0.05f)
        }
    }

    @Test
    fun `locked profile retains perspective quadrilaterals through camera tilt`() {
        for (geometry in listOf(BoardGeometry.STANDARD, BoardGeometry.WIDE)) {
            var previous = project(geometry, 0)
            val locked = BoardGeometry.fit(previous, focal, principal)!!.geometry
            for (degrees in 0..65 step 5) {
                val corners = project(geometry, degrees)
                val fit = BoardGeometry.fit(corners, focal, principal, locked, previous)!!
                assertEquals(geometry, fit.geometry)
                assertTrue("$degrees degrees", fit.estimate.reprojectionErrorPx < 0.05f)
                if (degrees >= 45) {
                    // Perspective foreshortens the far edge. It must not be replaced
                    // with an enclosing selection rectangle after calibration.
                    val q = fit.estimate.orderedCorners
                    val a = kotlin.math.hypot(q[2] - q[0], q[3] - q[1])
                    val b = kotlin.math.hypot(q[6] - q[4], q[7] - q[5])
                    assertTrue(kotlin.math.abs(a - b) > 5f)
                }
                previous = fit.estimate.orderedCorners
            }
        }
    }

    private fun project(geometry: BoardGeometry, degrees: Int): FloatArray {
        val board = geometry.corners()
        val angle = Math.toRadians(degrees.toDouble())
        return FloatArray(8).also { result ->
            for (i in 0 until 4) {
                val z = 0.4 + sin(angle) * board[i * 2 + 1]
                result[i * 2] = (focal[0] * (board[i * 2] - 0.08) / z + principal[0]).toFloat()
                result[i * 2 + 1] = (focal[1] * (cos(angle) * board[i * 2 + 1] - 0.025) / z + principal[1]).toFloat()
            }
        }
    }
}
