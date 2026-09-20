package com.htn.breadboardar.circuit

import kotlin.math.cos
import kotlin.math.sin

/**
 * Board-space drawing plan for the AR circuit overlay: the learner's actual components,
 * drawn over the scanned photo of their breadboard.
 *
 * Every feature is a small list of (u, v) points in metres on the tracked board plane
 * used by [com.htn.breadboardar.ar.BoardGeometry]: u runs along the 165 mm length,
 * v across the 55 mm width, origin at one long-edge corner. [com.htn.breadboardar.ar.ArCameraPreview]
 * projects these points with the same pose solve that draws the yellow outline, so the
 * parts follow the physical board under perspective instead of floating as a fixed
 * screen-space picture.
 *
 * Hole coordinates come only from the versioned board map through [BoardGeometry]:
 * u maps to the map's row axis (z) and v to its letter axis (x), shifted so the left
 * rail sits at v = 0. Wire shapes mirror the schematic painter ui/BreadboardView.kt, and
 * the A1 / J1 / A63 labels are drawn on the photo so the learner can verify orientation
 * against the printed board.
 */
class CircuitOverlayGeometry private constructor(
    val points: FloatArray,
    val specs: List<Spec>,
) {
    /** One drawable feature: [pointCount] points starting at [pointStart] in [points]. */
    data class Spec(
        val pointStart: Int,
        val pointCount: Int,
        val closed: Boolean,
        val color: Int,
        val strokeWidthPx: Float,
        val label: String? = null,
    )

    companion object {
        private const val WIRE_STROKE_PX = 8f
        private const val LEAD_STROKE_PX = 4f
        private const val NO_STROKE_PX = 0f

        /** Same bulge as the schematic painter: every wire bows 4 mm toward one side. */
        private const val WIRE_BULGE_METERS = 0.004f

        // Component body colours match the schematic exactly, so step-through
        // highlighting looks the same in 2-D review and in AR.
        private val wirePalette = mapOf(
            "red" to rgb(202, 72, 58),
            "yellow" to rgb(206, 157, 39),
            "blue" to rgb(68, 117, 176),
            "green" to rgb(57, 136, 94),
        )
        private val defaultWireColor = rgb(47, 57, 51)
        private val leadColor = rgb(134, 142, 134)
        private val ledColor = rgb(205, 76, 64)
        private val resistorColor = rgb(210, 176, 108)
        private val resistorStripeColor = rgb(134, 71, 47)
        private val buttonColor = rgb(51, 65, 58)
        private val supplyColor = rgb(181, 99, 63)
        private val unoColor = rgb(31, 105, 110)

        fun of(circuit: CircuitDefinition, board: BoardGeometry): CircuitOverlayGeometry {
            val points = ArrayList<Float>()
            val specs = ArrayList<Spec>()

            fun add(
                vararg coords: Pair<Float, Float>,
                closed: Boolean,
                color: Int,
                stroke: Float,
                label: String? = null,
            ) {
                specs += Spec(
                    pointStart = points.size / 2,
                    pointCount = coords.size,
                    closed = closed,
                    color = color,
                    strokeWidthPx = stroke,
                    label = label,
                )
                coords.forEach { (u, v) -> points += u; points += v }
            }

            fun uv(p: BoardPoint) = p.z to (p.x - board.minX)

            fun body(centerU: Float, centerV: Float, halfU: Float, halfV: Float) = arrayOf(
                centerU - halfU to centerV - halfV,
                centerU + halfU to centerV - halfV,
                centerU + halfU to centerV + halfV,
                centerU - halfU to centerV + halfV,
            )

            fun circle(centerU: Float, centerV: Float, radius: Float) = Array(8) { index ->
                val angle = Math.PI * 2.0 * index / 8.0
                centerU + (cos(angle) * radius).toFloat() to
                    centerV + (sin(angle) * radius).toFloat()
            }

            circuit.jumperWires.forEach { wire ->
                val a = board.endpoint(wire.from, circuit) ?: return@forEach
                val b = board.endpoint(wire.to, circuit) ?: return@forEach
                val (au, av) = uv(a)
                val (bu, bv) = uv(b)
                add(
                    au to av,
                    (au + bu) / 2f to (av + bv) / 2f + WIRE_BULGE_METERS,
                    bu to bv,
                    closed = false,
                    color = wirePalette[wire.color] ?: defaultWireColor,
                    stroke = WIRE_STROKE_PX,
                )
            }

            circuit.components.forEach { part ->
                val pins = part.terminals.values.mapNotNull(board::hole)
                if (pins.isEmpty()) return@forEach
                val centers = pins.map { uv(it) }
                val centerU = centers.map { it.first }.average().toFloat()
                val centerV = centers.map { it.second }.average().toFloat()

                centers.forEach { (pinU, pinV) ->
                    add(
                        pinU to pinV, centerU to centerV,
                        closed = false, color = leadColor, stroke = LEAD_STROKE_PX,
                    )
                    add(
                        *circle(pinU, pinV, 0.00085f),
                        closed = true, color = leadColor, stroke = NO_STROKE_PX,
                    )
                }
                when (part.type) {
                    "led" -> add(
                        *circle(centerU, centerV, 0.0021f),
                        closed = true, color = ledColor, stroke = NO_STROKE_PX,
                    )

                    "resistor" -> {
                        add(
                            *body(centerU, centerV, 0.003f, 0.0016f),
                            closed = true, color = resistorColor, stroke = NO_STROKE_PX,
                        )
                        for (offset in listOf(-0.0015f, 0f, 0.0015f)) {
                            add(
                                *body(centerU + offset, centerV, 0.00025f, 0.0016f),
                                closed = true, color = resistorStripeColor, stroke = NO_STROKE_PX,
                            )
                        }
                    }

                    "button" -> add(
                        *body(centerU, centerV, 0.003f, 0.003f),
                        closed = true, color = buttonColor, stroke = NO_STROKE_PX,
                    )

                    else -> pins.forEach { (pinU, pinV) ->
                        add(
                            *circle(pinU, pinV, 0.0015f),
                            closed = true, color = supplyColor, stroke = NO_STROKE_PX,
                        )
                    }
                }
            }

            if (circuit.externalDevices.isNotEmpty()) {
                // Schematic Uno outline, shared with ui/BreadboardView.kt. It sits left of
                // the board, so its v coordinates are legitimately negative.
                add(
                    -0.082f - board.minX to 0.014f, -0.023f - board.minX to 0.014f,
                    -0.023f - board.minX to 0.063f, -0.082f - board.minX to 0.063f,
                    closed = true, color = unoColor, stroke = NO_STROKE_PX,
                )
                add(
                    0.043f to -0.077f - board.minX,
                    closed = false, color = unoColor, stroke = NO_STROKE_PX, label = "UNO R3",
                )
                circuit.externalDevices.forEach { device ->
                    board.endpoint("${device.id}:D13", circuit)?.let {
                        val (u, v) = uv(it)
                        add(u to v, closed = false, color = leadColor, stroke = NO_STROKE_PX, label = "D13")
                    }
                    board.endpoint("${device.id}:GND", circuit)?.let {
                        val (u, v) = uv(it)
                        add(u to v, closed = false, color = leadColor, stroke = NO_STROKE_PX, label = "GND")
                    }
                }
            }

            // Corner labels let the learner check the overlay against the printed board.
            listOf("A1", "J1", "A63").forEach { address ->
                board.hole("BB1:$address")?.let { hole ->
                    val (u, v) = uv(hole)
                    val towardCenter = if (u < 0.08f) u + 0.007f else u - 0.007f
                    add(
                        towardCenter to v,
                        closed = false, color = leadColor, stroke = NO_STROKE_PX, label = address,
                    )
                }
            }

            return CircuitOverlayGeometry(points.toFloatArray(), specs)
        }

        /** Paint-style ARGB without android.graphics, so unit tests need no stubs. */
        private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
