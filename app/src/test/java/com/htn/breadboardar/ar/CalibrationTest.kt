package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationTest {
    @Test
    fun createsRightHandedBoardFrame() {
        val calibrator = ThreePointCalibrator()
        calibrator.addPoint(Pose.makeTranslation(1f, 2f, 3f))
        calibrator.addPoint(Pose.makeTranslation(2f, 2f, 3f))
        val calibration = calibrator.addPoint(Pose.makeTranslation(1f, 3f, 3f))!!

        assertArrayEquals(floatArrayOf(1f, 2f, 3f), calibration.originMeters, 0.0001f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), calibration.xAxis, 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), calibration.yAxis, 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), calibration.zAxis, 0.0001f)
        assertEquals(3, calibrator.pointCount)
    }
}

