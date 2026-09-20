package com.htn.breadboardar.ar

import android.graphics.PointF

/**
 * One circuit overlay feature already projected into view pixels, ready for
 * [com.htn.breadboardar.ui.CalibrationOverlayView] to draw over the camera image.
 *
 * The AR camera preview converts each board-plane feature from
 * [com.htn.breadboardar.circuit.CircuitOverlayGeometry] through the tracked board pose,
 * so components stay glued to the scanned photo of the breadboard as the phone moves.
 */
class CircuitOverlayFeature(
    val points: List<PointF>,
    /** Filled body when true; stroked wire or lead when false. */
    val closed: Boolean,
    val color: Int,
    val strokeWidthPx: Float,
    /** Optional text drawn centred on [points] first point, e.g. a hole reference. */
    val label: String? = null,
)
