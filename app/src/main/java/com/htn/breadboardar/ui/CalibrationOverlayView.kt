package com.htn.breadboardar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CalibrationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onCalibrationTap(x: Float, y: Float)

        /** The learner tapped inside one of the detected rectangles. */
        fun onRectangleSelected(index: Int, corners: List<PointF>)
    }

    var listener: Listener? = null
    private var isCalibrating = false
    private val tapPoints = mutableListOf<PointF>()
    private var surfaceTargets: List<PointF> = emptyList()
    private var boardOutline: List<PointF> = emptyList()
    private var smoothedBoardOutline: FloatArray? = null
    private val boardOutlineSmoother = ScreenQuadSmoother()
    private var candidateRectangles: List<List<PointF>> = emptyList()
    private var selectedRectangle: List<PointF>? = null

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(128, 203, 196)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(5f, 0f, 1f, Color.BLACK)
    }
    private val detectedSurfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 210)
        style = Paint.Style.FILL
        alpha = 210
    }
    private val detectedSurfaceBoundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 210)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 180
    }
    private val boardOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 70)
        style = Paint.Style.STROKE
        // A narrow, low-shadow stroke makes the detected boundary readable without
        // visually adding a large margin around an otherwise accurate quad.
        strokeWidth = 5f
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 230, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        setShadowLayer(5f, 0f, 1f, Color.BLACK)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(130, 255, 150)
        style = Paint.Style.STROKE
        strokeWidth = 10f
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
    }

    fun beginCalibration() {
        isCalibrating = true
        tapPoints.clear()
        boardOutline = emptyList()
        smoothedBoardOutline = null
        candidateRectangles = emptyList()
        selectedRectangle = null
        invalidate()
    }

    fun endCalibration() {
        isCalibrating = false
        surfaceTargets = emptyList()
        invalidate()
    }

    fun resetCalibration() {
        isCalibrating = false
        tapPoints.clear()
        surfaceTargets = emptyList()
        boardOutline = emptyList()
        smoothedBoardOutline = null
        candidateRectangles = emptyList()
        selectedRectangle = null
        invalidate()
    }

    fun addTapMarker(x: Float, y: Float) {
        tapPoints += PointF(x, y)
        invalidate()
    }

    /** Cyan targets show screen positions where ARCore currently has a usable plane hit. */
    fun showSurfaceTargets(targets: List<PointF>) {
        surfaceTargets = targets
        invalidate()
    }

    /**
     * Shows the board outline with a screen-space adaptive filter. The input is a
     * direct detector result, so this removes fast corner shimmer without hiding
     * genuine movement of the board in the camera view.
     */
    fun showBoardOutline(points: List<PointF>, smoothDetection: Boolean = true) {
        if (points.size != OUTLINE_CORNERS) {
            boardOutline = emptyList()
            smoothedBoardOutline = null
            invalidate()
            return
        }

        val fresh = FloatArray(OUTLINE_CORNERS * 2) { index ->
            val point = points[index / 2]
            if (index % 2 == 0) point.x else point.y
        }
        // Anchor projections already contain ARCore's motion estimate. Filtering
        // them again in screen space makes the outline lag the live 3-D model.
        val filtered = if (smoothDetection) boardOutlineSmoother.smooth(smoothedBoardOutline, fresh) else fresh
        smoothedBoardOutline = filtered
        boardOutline = List(OUTLINE_CORNERS) { corner ->
            PointF(filtered[corner * 2], filtered[corner * 2 + 1])
        }
        invalidate()
    }

    /** Bright rectangles the learner can choose between. Ignored once one is locked in. */
    fun showCandidateRectangles(rectangles: List<List<PointF>>) {
        if (selectedRectangle != null) return
        candidateRectangles = rectangles
        invalidate()
    }

    fun lockSelectedRectangle(corners: List<PointF>) {
        selectedRectangle = corners
        candidateRectangles = emptyList()
        invalidate()
    }

    /** Dropped once the anchored board outline takes over, so the two do not overlap. */
    fun clearSelectedRectangle() {
        selectedRectangle = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isCalibrating && surfaceTargets.isNotEmpty()) {
            surfaceTargets.forEach { target ->
                canvas.drawCircle(target.x, target.y, 10f, detectedSurfacePaint)
            }
            val bounds = RectF(
                surfaceTargets.minOf { it.x },
                surfaceTargets.minOf { it.y },
                surfaceTargets.maxOf { it.x },
                surfaceTargets.maxOf { it.y },
            )
            if (bounds.width() > 40f && bounds.height() > 40f) {
                canvas.drawRoundRect(bounds, 16f, 16f, detectedSurfaceBoundsPaint)
            }
        }

        if (boardOutline.size == 4) {
            val path = Path().apply {
                moveTo(boardOutline[0].x, boardOutline[0].y)
                boardOutline.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            canvas.drawPath(path, boardOutlinePaint)
        }

        candidateRectangles.forEachIndexed { index, corners ->
            drawQuad(canvas, corners, candidatePaint)
            val centre = quadCentre(corners)
            canvas.drawText((index + 1).toString(), centre.x, centre.y, labelPaint)
        }
        selectedRectangle?.let { drawQuad(canvas, it, selectedPaint) }

        tapPoints.forEachIndexed { index, point ->
            canvas.drawCircle(point.x, point.y, 24f, pointPaint)
            canvas.drawText((index + 1).toString(), point.x + 32f, point.y + 12f, labelPaint)
        }
    }

    private fun drawQuad(canvas: Canvas, corners: List<PointF>, paint: Paint) {
        if (corners.size < 3) return
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun quadCentre(corners: List<PointF>): PointF = PointF(
        corners.map { it.x }.average().toFloat(),
        corners.map { it.y }.average().toFloat(),
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrating) return false
        if (event.action == MotionEvent.ACTION_UP) {
            // A tap inside a detected rectangle selects the board. Anywhere else
            // falls through to the three-point flow, so that path still works when
            // nothing is detected.
            val hitIndex = candidateRectangles.indexOfFirst { contains(it, event.x, event.y) }
            if (hitIndex >= 0) {
                listener?.onRectangleSelected(hitIndex, candidateRectangles[hitIndex])
            } else {
                listener?.onCalibrationTap(event.x, event.y)
            }
        }
        return true
    }

    /** Standard ray-cast point-in-polygon test. */
    private fun contains(corners: List<PointF>, x: Float, y: Float): Boolean {
        var inside = false
        var j = corners.size - 1
        for (i in corners.indices) {
            val xi = corners[i].x
            val yi = corners[i].y
            val xj = corners[j].x
            val yj = corners[j].y
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private companion object {
        const val OUTLINE_CORNERS = 4
    }
}
