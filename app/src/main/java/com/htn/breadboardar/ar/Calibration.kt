package com.htn.breadboardar.ar

import com.google.ar.core.Pose
import kotlin.math.sqrt

data class BoardCalibration(
    val originMeters: FloatArray,
    val xAxis: FloatArray,
    val yAxis: FloatArray,
    val zAxis: FloatArray,
)

/**
 * Builds a right-handed breadboard frame from three taps:
 * origin, positive X reference, positive Y reference.
 */
class ThreePointCalibrator {
    private val points = mutableListOf<FloatArray>()

    val pointCount: Int
        get() = points.size

    fun reset() = points.clear()

    fun addPoint(pose: Pose): BoardCalibration? {
        require(points.size < 3) { "Calibration already has three points." }
        points += pose.translation.copyOf()
        return if (points.size == 3) buildCalibration() else null
    }

    private fun buildCalibration(): BoardCalibration {
        val origin = points[0]
        val xAxis = normalize(subtract(points[1], origin))
        val yReference = normalize(subtract(points[2], origin))
        val zAxis = normalize(cross(xAxis, yReference))

        require(length(zAxis) > 0.001f) {
            "Calibration points must not be collinear. Tap three separate board corners."
        }

        val yAxis = normalize(cross(zAxis, xAxis))
        return BoardCalibration(origin.copyOf(), xAxis, yAxis, zAxis)
    }

    private fun subtract(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] - b[0],
        a[1] - b[1],
        a[2] - b[2],
    )

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun length(a: FloatArray) = sqrt(a.sumOf { (it * it).toDouble() }).toFloat()

    private fun normalize(a: FloatArray): FloatArray {
        val magnitude = length(a)
        require(magnitude > 0.001f) {
            "Calibration points are too close together. Use the board corners."
        }
        return floatArrayOf(a[0] / magnitude, a[1] / magnitude, a[2] / magnitude)
    }
}

data class CameraState(
    val timestampNs: Long,
    val translationMeters: FloatArray,
    val rotationQuaternion: FloatArray,
    val focalLengthPixels: FloatArray,
    val principalPointPixels: FloatArray,
    val imageDimensions: IntArray,
    val viewportWidth: Int,
    val viewportHeight: Int,
)

