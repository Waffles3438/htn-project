package com.htn.breadboardar.ar

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Matches a detected outline to the board without reflecting its visible face.
 *
 * Board XY increases right/down on its top face, so its visible surface normal is
 * local -Z. Positive image winding (clockwise in image pixels) preserves that
 * face. Reversing the correspondence gives an equally good planar fit, but turns
 * the model upside down; it must never be a pose-selection hypothesis.
 */
internal object BoardPoseEstimator {
    data class Estimate(
        val orderedCorners: FloatArray,
        val result: PlanarPoseSolver.Result,
        val reprojectionErrorPx: Float,
        val cornerContinuityPx: Float,
    )

    /** [boardCorners] must describe a convex, positive-winding board in local XY. */
    fun solve(
        imageCorners: FloatArray,
        boardCorners: FloatArray,
        focalLength: FloatArray,
        principalPoint: FloatArray,
        previousOrderedCorners: FloatArray? = null,
    ): Estimate? {
        val imageWinding = convexWinding(imageCorners) ?: return null
        if (convexWinding(boardCorners) != 1) return null
        if (focalLength.size < 2 || principalPoint.size < 2) return null
        if (!focalLength.take(2).all { it.isFinite() && it > 0f } ||
            !principalPoint.take(2).all { it.isFinite() }
        ) return null
        if (previousOrderedCorners != null &&
            (previousOrderedCorners.size != 8 || !previousOrderedCorners.all { it.isFinite() })
        ) return null

        // Canonical start makes input enumeration deterministic even if a contour
        // detector starts at another vertex. Only the initial solve uses this tie
        // breaker; subsequent solves preserve physical corner correspondence.
        val first = (0 until 4).minWithOrNull(
            compareBy<Int> { imageCorners[it * 2 + 1] }.thenBy { imageCorners[it * 2] },
        ) ?: return null
        val clockwise = reorder(imageCorners, first, imageWinding)
        val candidates = ArrayList<Estimate>(4)
        for (start in 0 until 4) {
            val corners = reorder(clockwise, start, 1)
            val pose = PlanarPoseSolver.solve(corners, boardCorners, focalLength, principalPoint)
                ?: continue
            if (!pose.translation.all { it.isFinite() } || !pose.quaternion.all { it.isFinite() }) continue
            if (!topFacesCamera(pose, boardCorners)) continue
            val error = PlanarPoseSolver.reprojectionErrorPx(
                corners, boardCorners, focalLength, principalPoint, pose,
            )
            if (!error.isFinite() || error == Float.MAX_VALUE) continue
            candidates += Estimate(
                orderedCorners = corners,
                result = pose,
                reprojectionErrorPx = error,
                cornerContinuityPx = previousOrderedCorners?.let { meanDistance(corners, it) } ?: 0f,
            )
        }

        // Once selected, corner identity is a tracking constraint, not a tie-break
        // after reprojection error. Noise can make the opposite end's residual a
        // pixel better without the physical board actually turning 180 degrees.
        // Let the caller's fit-quality gate reject a poor fit; never relabel it.
        if (previousOrderedCorners != null) return candidates.minByOrNull { it.cornerContinuityPx }

        // Compare every candidate to the best residual. Pairwise epsilon sorting
        // is not transitive and can otherwise let successive ties pick a bad fit.
        val bestError = candidates.minOfOrNull { it.reprojectionErrorPx } ?: return null
        return candidates.filter { it.reprojectionErrorPx <= bestError + RESIDUAL_TIE_PX }
            .minByOrNull { it.cornerContinuityPx }
    }

    private fun topFacesCamera(pose: PlanarPoseSolver.Result, board: FloatArray): Boolean {
        val x = pose.quaternion[0]
        val y = pose.quaternion[1]
        val z = pose.quaternion[2]
        val w = pose.quaternion[3]
        val centerX = (0 until 4).sumOf { board[it * 2].toDouble() }.toFloat() / 4f
        val centerY = (0 until 4).sumOf { board[it * 2 + 1].toDouble() }.toFloat() / 4f
        val cameraCenter = floatArrayOf(
            (1f - 2f * (y * y + z * z)) * centerX + 2f * (x * y - z * w) * centerY + pose.translation[0],
            2f * (x * y + z * w) * centerX + (1f - 2f * (x * x + z * z)) * centerY + pose.translation[1],
            2f * (x * z - y * w) * centerX + 2f * (y * z + x * w) * centerY + pose.translation[2],
        )
        // R * (0, 0, -1) dotted with (camera origin - board center).
        val normal = floatArrayOf(-2f * (x * z + y * w), -2f * (y * z - x * w), -(1f - 2f * (x * x + y * y)))
        val facing = -(0 until 3).sumOf { normal[it].toDouble() * cameraCenter[it] }
        return facing.isFinite() && facing > 1e-6
    }

    /** Returns the perimeter winding, rejecting collinear, crossed or concave quads. */
    private fun convexWinding(corners: FloatArray): Int? {
        if (corners.size != 8 || !corners.all { it.isFinite() }) return null
        val width = (0 until 4).maxOf { corners[it * 2].toDouble() } -
            (0 until 4).minOf { corners[it * 2].toDouble() }
        val height = (0 until 4).maxOf { corners[it * 2 + 1].toDouble() } -
            (0 until 4).minOf { corners[it * 2 + 1].toDouble() }
        val scaleSquared = width * width + height * height
        if (scaleSquared <= 0.0) return null
        var sign = 0
        for (i in 0 until 4) {
            val next = (i + 1) % 4
            val after = (i + 2) % 4
            val ax = corners[next * 2].toDouble() - corners[i * 2]
            val ay = corners[next * 2 + 1].toDouble() - corners[i * 2 + 1]
            val bx = corners[after * 2].toDouble() - corners[next * 2]
            val by = corners[after * 2 + 1].toDouble() - corners[next * 2 + 1]
            val cross = ax * by - ay * bx
            if (abs(cross) <= scaleSquared * 1e-6) return null
            val current = if (cross > 0.0) 1 else -1
            if (sign != 0 && sign != current) return null
            sign = current
        }
        return sign
    }

    private fun reorder(corners: FloatArray, start: Int, direction: Int): FloatArray =
        FloatArray(8) { index -> corners[((start + direction * (index / 2) + 4) % 4) * 2 + index % 2] }

    private fun meanDistance(first: FloatArray, second: FloatArray): Float =
        (0 until 4).sumOf {
            hypot((first[it * 2] - second[it * 2]).toDouble(), (first[it * 2 + 1] - second[it * 2 + 1]).toDouble())
        }.toFloat() / 4f

    private const val RESIDUAL_TIE_PX = 1f
}
