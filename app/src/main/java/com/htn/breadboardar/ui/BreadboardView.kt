package com.htn.breadboardar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.htn.breadboardar.circuit.BoardGeometry
import com.htn.breadboardar.circuit.CircuitDefinition
import kotlin.math.min

/** Uniform map projection with pinch zoom and pan; body sizes are schematic. */
class BreadboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var board: BoardGeometry? = null
    var circuit: CircuitDefinition? = null
        set(value) { field = value; fit(); contentDescription = value?.let { "Breadboard schematic for ${it.title}. Pinch to zoom. Assembly instructions below list every connection." } }
    var highlightedIds: Set<String> = emptySet()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoom = 1f
    private var dx = 0f
    private var dy = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            invalidate()
            return true
        }
    })
    fun fit() { zoom = 1f; dx = 0f; dy = 0f; invalidate() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { if (!scaler.isInProgress) { dx += event.x - lastX; dy += event.y - lastY; invalidate() }; lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geometry = board ?: return
        canvas.drawColor(Color.rgb(238, 239, 230))
        val external = circuit?.externalDevices?.isNotEmpty() == true
        val minX = if (external) -0.09f else -0.018f
        val maxX = 0.05f
        val scale = min(width / (maxX - minX), height / 0.182f) * .90f
        canvas.save()
        canvas.translate(width / 2f + dx, height / 2f + dy)
        canvas.scale(zoom, zoom)
        canvas.scale(scale, scale)
        canvas.translate(-(minX + maxX) / 2f, -0.07874f)
        paint.color = Color.rgb(253, 252, 246); paint.style = Paint.Style.FILL
        canvas.drawRoundRect(-.014f, -.007f, .042f, .1645f, .003f, .003f, paint)
        paint.color = Color.rgb(225, 225, 213)
        canvas.drawRoundRect(.0116f, -.003f, .0163f, .1605f, .001f, .001f, paint)
        geometry.holes.forEach { (id, p) ->
            paint.color = if (id.startsWith("L") || id.startsWith("R")) {
                if (id.contains('+')) Color.rgb(213, 143, 129) else Color.rgb(135, 162, 188)
            } else Color.rgb(158, 163, 149)
            canvas.drawCircle(p.x, p.z, .00056f, paint)
        }
        paint.color = Color.rgb(100, 110, 94); paint.textSize = .0019f
        for (letter in 'A'..'J') {
            val p = geometry.holes["${letter}1"]!!
            label(canvas, letter.toString(), p.x - .0006f, -.0026f, .0019f)
        }
        for (row in listOf(1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 63)) {
            val p = geometry.holes["A$row"]!!
            label(canvas, row.toString(), -.0055f, p.z + .0006f, .0019f)
        }
        val model = circuit
        if (model != null) {
            if (external) {
                paint.color = Color.rgb(31, 105, 110)
                canvas.drawRoundRect(-.082f, .014f, -.023f, .063f, .003f, .003f, paint)
                paint.color = Color.WHITE; paint.textSize = .004f
                label(canvas, "UNO R3", -.077f, .043f, .004f)
                paint.textSize = .0018f
                label(canvas, "D13", -.031f, .025f, .0018f); label(canvas, "GND", -.031f, .034f, .0018f)
            }
            model.jumperWires.forEach { wire ->
                val a = geometry.endpoint(wire.from, model) ?: return@forEach
                val b = geometry.endpoint(wire.to, model) ?: return@forEach
                paint.color = wireColor(wire.color); paint.alpha = alpha(wire.id)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = .0011f; paint.strokeCap = Paint.Cap.ROUND
                val path = Path().apply { moveTo(a.x, a.z); quadTo((a.x + b.x) / 2 + .004f, (a.z + b.z) / 2, b.x, b.z) }
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
                canvas.drawCircle(a.x, a.z, .001f, paint); canvas.drawCircle(b.x, b.z, .001f, paint)
            }
            model.components.forEach { part ->
                val pins = part.terminals.values.mapNotNull(geometry::hole)
                if (pins.isEmpty()) return@forEach
                val x = pins.map { it.x }.average().toFloat(); val z = pins.map { it.z }.average().toFloat()
                paint.color = Color.rgb(134, 142, 134); paint.alpha = alpha(part.id); paint.strokeWidth = .00065f
                pins.forEach { canvas.drawLine(it.x, it.z, x, z, paint); canvas.drawCircle(it.x, it.z, .00085f, paint) }
                paint.color = when(part.type) { "led" -> Color.rgb(205, 76, 64); "resistor" -> Color.rgb(210, 176, 108); "button" -> Color.rgb(51, 65, 58); else -> Color.rgb(181, 99, 63) }
                paint.alpha = alpha(part.id)
                when (part.type) {
                    "led" -> canvas.drawCircle(x, z, .0021f, paint)
                    "resistor" -> {
                        canvas.drawRoundRect(x - .0016f, z - .003f, x + .0016f, z + .003f, .0008f, .0008f, paint)
                        paint.color = Color.rgb(134, 71, 47); paint.alpha = alpha(part.id)
                        for (offset in listOf(-.0015f, 0f, .0015f)) canvas.drawRect(x - .0016f, z + offset - .00025f, x + .0016f, z + offset + .00025f, paint)
                    }
                    "button" -> canvas.drawRoundRect(x - .003f, z - .003f, x + .003f, z + .003f, .0008f, .0008f, paint)
                    else -> pins.forEach { canvas.drawCircle(it.x, it.z, .0015f, paint) }
                }
            }
        }
        paint.alpha = 255
        canvas.restore()
    }
    private fun label(canvas: Canvas, text: String, x: Float, z: Float, size: Float) {
        canvas.save(); canvas.translate(x, z); canvas.scale(.0001f, .0001f)
        paint.textSize = size * 10000
        canvas.drawText(text, 0f, 0f, paint)
        canvas.restore()
    }
    private fun alpha(id: String) = if (highlightedIds.isEmpty() || id in highlightedIds) 255 else 60
    private fun wireColor(name: String) = when (name) {
        "red" -> Color.rgb(202, 72, 58); "yellow" -> Color.rgb(206, 157, 39)
        "blue" -> Color.rgb(68, 117, 176); "green" -> Color.rgb(57, 136, 94)
        else -> Color.rgb(47, 57, 51)
    }
}
