package com.htn.breadboardar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CalibrationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onCalibrationTap(x: Float, y: Float)
    }

    var listener: Listener? = null
    private var isCalibrating = false
    private val tapPoints = mutableListOf<PointF>()
    private var remoteOverlay: Bitmap? = null

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
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun beginCalibration() {
        isCalibrating = true
        tapPoints.clear()
        invalidate()
    }

    fun endCalibration() {
        isCalibrating = false
        invalidate()
    }

    fun resetCalibration() {
        isCalibrating = false
        tapPoints.clear()
        remoteOverlay = null
        invalidate()
    }

    fun addTapMarker(x: Float, y: Float) {
        tapPoints += PointF(x, y)
        invalidate()
    }

    /** Unity sends a PNG with transparent background, rendered for this phone's camera pose. */
    fun showRemoteOverlay(bitmap: Bitmap) {
        remoteOverlay = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        remoteOverlay?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), overlayPaint)
        }

        tapPoints.forEachIndexed { index, point ->
            canvas.drawCircle(point.x, point.y, 24f, pointPaint)
            canvas.drawText((index + 1).toString(), point.x + 32f, point.y + 12f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrating) return false
        if (event.action == MotionEvent.ACTION_UP) {
            listener?.onCalibrationTap(event.x, event.y)
        }
        return true
    }
}

