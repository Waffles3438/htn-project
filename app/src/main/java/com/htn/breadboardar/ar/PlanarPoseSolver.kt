package com.htn.breadboardar.ar

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Recovers the 6-DoF pose of a known planar rectangle from its four image corners.
 *
 * Four coplanar correspondences plus the camera intrinsics determine a homography,
 * and for a plane that homography factors into rotation and translation. This is
 * the classic planar pose solve, written out longhand so the app needs no OpenCV.
 *
 * Note that a planar quad fixes pose only *up to scale*, which is why the caller
 * must supply the rectangle's real dimensions in metres. An error in those
 * dimensions scales the whole result.
 *
 * Results are returned in ARCore's camera convention (X right, Y up, Z out of the
 * screen), not the computer-vision convention the maths is done in, so the caller
 * can compose directly with `Camera.getPose()`.
 */
internal object PlanarPoseSolver {

    /** Board-to-camera transform in ARCore's camera frame. Quaternion is x, y, z, w. */
    class Result(val translation: FloatArray, val quaternion: FloatArray)

    /**
     * @param imageCorners four u, v pairs in image pixels
     * @param boardCorners four X, Y pairs in metres, in the same order
     * @param focalLength fx, fy in pixels
     * @param principalPoint cx, cy in pixels
     */
    fun solve(
        imageCorners: FloatArray,
        boardCorners: FloatArray,
        focalLength: FloatArray,
        principalPoint: FloatArray,
    ): Result? {
        if (imageCorners.size != 8 || boardCorners.size != 8) return null
        if (focalLength.size < 2 || principalPoint.size < 2) return null
        val fx = focalLength[0]
        val fy = focalLength[1]
        if (fx <= 0f || fy <= 0f) return null

        // Normalised camera coordinates remove the intrinsics, leaving a homography
        // that is purely [r1 r2 t] up to scale.
        val normalised = FloatArray(8)
        for (i in 0 until 4) {
            normalised[i * 2] = (imageCorners[i * 2] - principalPoint[0]) / fx
            normalised[i * 2 + 1] = (imageCorners[i * 2 + 1] - principalPoint[1]) / fy
        }

        val h = homography(boardCorners, normalised) ?: return null

        // Columns of the homography.
        var c0 = floatArrayOf(h[0], h[3], h[6])
        var c1 = floatArrayOf(h[1], h[4], h[7])
        var c2 = floatArrayOf(h[2], h[5], h[8])

        val norm0 = length(c0)
        val norm1 = length(c1)
        if (norm0 < 1e-9f || norm1 < 1e-9f) return null
        // Both columns should have unit norm after scaling; averaging splits the
        // difference when the corner estimates are slightly inconsistent.
        var scale = 2f / (norm0 + norm1)

        // Two solutions differ by sign. The physical one puts the board in front of
        // the camera, which in the CV convention means positive depth.
        if (c2[2] * scale < 0f) scale = -scale
        c0 = scaled(c0, scale)
        c1 = scaled(c1, scale)
        c2 = scaled(c2, scale)

        // Re-orthonormalise: the raw columns are only approximately a rotation.
        val r0 = normalise(c0) ?: return null
        val projection = dot(r0, c1)
        val r1 = normalise(
            floatArrayOf(
                c1[0] - projection * r0[0],
                c1[1] - projection * r0[1],
                c1[2] - projection * r0[2],
            ),
        ) ?: return null
        val r2 = cross(r0, r1)

        // Row-major board-to-camera rotation in the CV frame.
        val rotationCv = floatArrayOf(
            r0[0], r1[0], r2[0],
            r0[1], r1[1], r2[1],
            r0[2], r1[2], r2[2],
        )

        // CV to ARCore camera frame: Y and Z both flip, so negate rows 1 and 2.
        val rotationGl = floatArrayOf(
            rotationCv[0], rotationCv[1], rotationCv[2],
            -rotationCv[3], -rotationCv[4], -rotationCv[5],
            -rotationCv[6], -rotationCv[7], -rotationCv[8],
        )
        val translationGl = floatArrayOf(c2[0], -c2[1], -c2[2])

        return Result(translationGl, quaternionOf(rotationGl))
    }

    /**
     * Reprojects board points with a CV-frame pose. Exposed so callers and tests can
     * measure residuals, which is the cheapest way to tell a good solve from a bad one.
     */
    fun reprojectionErrorPx(
        imageCorners: FloatArray,
        boardCorners: FloatArray,
        focalLength: FloatArray,
        principalPoint: FloatArray,
        result: Result,
    ): Float {
        val projected = projectBoardCorners(boardCorners, focalLength, principalPoint, result)
            ?: return Float.MAX_VALUE

        var worst = 0f
        for (i in 0 until 4) {
            val dx = projected[i * 2] - imageCorners[i * 2]
            val dy = projected[i * 2 + 1] - imageCorners[i * 2 + 1]
            val error = sqrt(dx * dx + dy * dy)
            if (error > worst) worst = error
        }
        return worst
    }

    /**
     * Projects board points into image pixels through a solved pose. Used both to
     * measure residuals and to draw the outline straight from the pose, which needs
     * no world tracking and therefore cannot drift.
     */
    fun projectBoardCorners(
        boardCorners: FloatArray,
        focalLength: FloatArray,
        principalPoint: FloatArray,
        result: Result,
    ): FloatArray? {
        val rotationGl = rotationOf(result.quaternion)
        // Back to the CV frame to apply the pinhole model.
        val rotationCv = floatArrayOf(
            rotationGl[0], rotationGl[1], rotationGl[2],
            -rotationGl[3], -rotationGl[4], -rotationGl[5],
            -rotationGl[6], -rotationGl[7], -rotationGl[8],
        )
        val translationCv = floatArrayOf(
            result.translation[0],
            -result.translation[1],
            -result.translation[2],
        )

        val count = boardCorners.size / 2
        val projected = FloatArray(count * 2)
        for (i in 0 until count) {
            val x = boardCorners[i * 2]
            val y = boardCorners[i * 2 + 1]
            val cameraX = rotationCv[0] * x + rotationCv[1] * y + translationCv[0]
            val cameraY = rotationCv[3] * x + rotationCv[4] * y + translationCv[1]
            val cameraZ = rotationCv[6] * x + rotationCv[7] * y + translationCv[2]
            if (cameraZ <= 1e-6f) return null
            projected[i * 2] = focalLength[0] * cameraX / cameraZ + principalPoint[0]
            projected[i * 2 + 1] = focalLength[1] * cameraY / cameraZ + principalPoint[1]
        }
        return projected
    }

    /**
     * Direct linear transform for a homography from exactly four correspondences.
     * Fixing h8 = 1 leaves eight unknowns and eight equations, so this is an exact
     * solve rather than a least-squares fit.
     */
    private fun homography(source: FloatArray, target: FloatArray): FloatArray? {
        val a = Array(8) { FloatArray(9) }
        for (i in 0 until 4) {
            val x = source[i * 2]
            val y = source[i * 2 + 1]
            val u = target[i * 2]
            val v = target[i * 2 + 1]

            val row = i * 2
            a[row][0] = x; a[row][1] = y; a[row][2] = 1f
            a[row][6] = -u * x; a[row][7] = -u * y; a[row][8] = u

            a[row + 1][3] = x; a[row + 1][4] = y; a[row + 1][5] = 1f
            a[row + 1][6] = -v * x; a[row + 1][7] = -v * y; a[row + 1][8] = v
        }

        val solution = solveLinearSystem(a) ?: return null
        return floatArrayOf(
            solution[0], solution[1], solution[2],
            solution[3], solution[4], solution[5],
            solution[6], solution[7], 1f,
        )
    }

    /** Gaussian elimination with partial pivoting on an augmented 8x9 matrix. */
    private fun solveLinearSystem(matrix: Array<FloatArray>): FloatArray? {
        val size = matrix.size
        for (column in 0 until size) {
            var pivot = column
            for (row in column + 1 until size) {
                if (abs(matrix[row][column]) > abs(matrix[pivot][column])) pivot = row
            }
            if (abs(matrix[pivot][column]) < 1e-9f) return null
            val swap = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = swap

            val diagonal = matrix[column][column]
            for (k in column..size) matrix[column][k] /= diagonal

            for (row in 0 until size) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (factor == 0f) continue
                for (k in column..size) matrix[row][k] -= factor * matrix[column][k]
            }
        }
        return FloatArray(size) { matrix[it][size] }
    }

    private fun quaternionOf(rotation: FloatArray): FloatArray {
        val m00 = rotation[0]; val m01 = rotation[1]; val m02 = rotation[2]
        val m10 = rotation[3]; val m11 = rotation[4]; val m12 = rotation[5]
        val m20 = rotation[6]; val m21 = rotation[7]; val m22 = rotation[8]
        val trace = m00 + m11 + m22

        return when {
            trace > 0f -> {
                val s = 0.5f / sqrt(trace + 1f)
                floatArrayOf((m21 - m12) * s, (m02 - m20) * s, (m10 - m01) * s, 0.25f / s)
            }

            m00 > m11 && m00 > m22 -> {
                val s = 2f * sqrt(1f + m00 - m11 - m22)
                floatArrayOf(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            }

            m11 > m22 -> {
                val s = 2f * sqrt(1f + m11 - m00 - m22)
                floatArrayOf((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            }

            else -> {
                val s = 2f * sqrt(1f + m22 - m00 - m11)
                floatArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }
        }
    }

    private fun rotationOf(quaternion: FloatArray): FloatArray {
        val x = quaternion[0]; val y = quaternion[1]; val z = quaternion[2]; val w = quaternion[3]
        return floatArrayOf(
            1f - 2f * (y * y + z * z), 2f * (x * y - z * w), 2f * (x * z + y * w),
            2f * (x * y + z * w), 1f - 2f * (x * x + z * z), 2f * (y * z - x * w),
            2f * (x * z - y * w), 2f * (y * z + x * w), 1f - 2f * (x * x + y * y),
        )
    }

    private fun length(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun scaled(v: FloatArray, factor: Float): FloatArray =
        floatArrayOf(v[0] * factor, v[1] * factor, v[2] * factor)

    private fun normalise(v: FloatArray): FloatArray? {
        val magnitude = length(v)
        if (magnitude < 1e-9f) return null
        return floatArrayOf(v[0] / magnitude, v[1] / magnitude, v[2] / magnitude)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}
