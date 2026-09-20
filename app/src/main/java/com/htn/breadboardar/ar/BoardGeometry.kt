package com.htn.breadboardar.ar

/** Full-size board profiles. Select once at calibration; never resize with camera tilt. */
internal data class BoardGeometry(val lengthMeters: Float, val widthMeters: Float) {
    fun corners() = floatArrayOf(0f, 0f, lengthMeters, 0f,
        lengthMeters, widthMeters, 0f, widthMeters)

    data class Fit(val geometry: BoardGeometry, val estimate: BoardPoseEstimator.Estimate)

    companion object {
        val STANDARD = BoardGeometry(0.165f, 0.055f)
        val WIDE = BoardGeometry(0.165f, 0.065f)

        fun fit(
            imageCorners: FloatArray, focal: FloatArray, principal: FloatArray,
            lockedGeometry: BoardGeometry? = null, previousCorners: FloatArray? = null,
        ): Fit? = (lockedGeometry?.let { listOf(it) } ?: listOf(STANDARD, WIDE))
            .mapNotNull { geometry ->
                BoardPoseEstimator.solve(imageCorners, geometry.corners(), focal, principal,
                    previousCorners)?.let { Fit(geometry, it) }
            }.minByOrNull { it.estimate.reprojectionErrorPx }
    }
}
