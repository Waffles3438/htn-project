package com.htn.breadboardar.render

import com.htn.breadboardar.circuit.BoardGeometry
import com.htn.breadboardar.circuit.CircuitComponent
import com.htn.breadboardar.circuit.CircuitDefinition
import com.htn.breadboardar.circuit.JumperWire
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the schematic circuit overlay that turns the bundled breadboard GLB into a
 * rendered copy of the learner's circuit: every component body, jumper wire, hole guide
 * and external device sits on the CAD model's actual hole positions.
 *
 * The overlay is a self-contained glTF 2.0 scene authored in the breadboard GLB's own
 * coordinate frame (glTF world axes after the FBX import: X along the 63 hole rows, Z
 * across the A-J columns, Y the board height). The grid constants below were measured
 * from the model's 830 painted hole quads and reproduce the placement contract exactly:
 * 63 rows at a fixed pitch, ten strip columns split by the center channel, and four rail
 * columns whose holes follow the documented five-per-group segments starting at rows 3
 * and 33 — identical to the runtime board map's rail model.
 *
 * The CAD model is authored hole-side down: its painted hole face is the -Y side, so all
 * overlay geometry extends toward -Y from the hole plane and the renderer must show the
 * -Y face to the camera.
 *
 * Row/column orientation follows the reading convention of the beside-board copy (row 1
 * at the model's -X end, column A on the model's -Z side next to the L rails). The
 * physical board's own orientation is whatever the tracker locked, so this is schematic
 * presentation, exactly like the beside-board mode described in UNITY_HANDOFF.md.
 */
internal class CircuitOverlayBuilder(private val board: BoardGeometry, private val circuit: CircuitDefinition) {

    /** Accumulates one renderable part; primitives are grouped per material. */
    private class Part(val name: String) {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val materialRanges = LinkedHashMap<String, IntArray>()

        fun vertex(point: FloatArray, normal: FloatArray): Int {
            positions.add(point[0]); positions.add(point[1]); positions.add(point[2])
            normals.add(normal[0]); normals.add(normal[1]); normals.add(normal[2])
            return indices.size
        }

        fun triangle(a: Int, b: Int, c: Int, materialKey: String) {
            val range = materialRanges.getOrPut(materialKey) { intArrayOf(indices.size, 0) }
            indices.add(a); indices.add(b); indices.add(c)
            range[1] += 3
        }

        fun isEmpty() = indices.isEmpty()
    }

    private val parts = ArrayList<Part>()
    private val guides = Part("hole-guides")
    private val guideKeys = HashSet<String>()

    /** Builds the overlay as a self-contained binary glTF 2.0 buffer. */
    fun buildGlb(): ByteBuffer {
        board.validate(circuit)
        for (component in circuit.components) addComponent(component)
        for (wire in circuit.jumperWires) addWire(wire)
        for (device in circuit.externalDevices) addExternalDevice(device)
        val meshes = (parts + guides).filterNot { it.isEmpty() }.map { part ->
            OverlayMesh(part.name, part.positions, part.normals, part.indices, part.materialRanges.map { it.key to it.value })
        }
        if (meshes.isEmpty()) throw IllegalArgumentException("The circuit has nothing to render.")
        return OverlayGlbWriter(meshes).build()
    }

    private fun addComponent(component: CircuitComponent) {
        val points = canonicalTerminals(component.type).map { holeAt(component.terminals[it], it, component.id) }
        val part = Part(component.id)
        when (component.type) {
            "led" -> addLed(part, points[0], points[1])
            "resistor" -> addResistor(part, points[0], points[1])
            "button" -> addButton(part, points)
            "power_supply" -> addPowerMarkers(part, points[0], points[1])
            else -> throw IllegalArgumentException("Unsupported component type: ${component.type}")
        }
        parts.add(part)
        points.forEach(::addGuide)
    }

    private fun addWire(wire: JumperWire) {
        val from = endpointAt(wire.from)
        val to = endpointAt(wire.to)
        val part = Part(wire.id)
        addTube(part, arcPoints(from, to), WIRE_RADIUS, wireColor(wire.color))
        parts.add(part)
        addGuide(from)
        addGuide(to)
    }

    private fun addExternalDevice(device: com.htn.breadboardar.circuit.ExternalDevice) {
        // Schematic Uno proxy lying on the table beside the board, mirroring
        // BoardGeometry's display-only D13/GND pin anchors (no Uno mesh exists).
        val part = Part(device.id)
        addBox(
            part,
            metersToModel(UNO_CENTER_X_M, UNO_TOP_Y_M, UNO_CENTER_Z_M),
            floatArrayOf(UNO_HALF_ROWS, UNO_HALF_THICK, UNO_HALF_ACROSS),
            "uno-teal",
        )
        parts.add(part)
        addGuide(endpointAt("${device.id}:D13"))
        addGuide(endpointAt("${device.id}:GND"))
    }

    // --- Address resolution ---------------------------------------------------------------

    private fun holeAt(address: String?, terminal: String, componentId: String): FloatArray {
        if (address == null) throw IllegalArgumentException("Component $componentId is missing terminal $terminal.")
        if (board.hole(address) == null) throw IllegalArgumentException("Unknown breadboard hole: $address")
        return holePosition(address)
    }

    internal fun endpointAt(address: String): FloatArray {
        if (board.hole(address) != null) return holePosition(address)
        val id = address.substringBefore(':')
        val pin = address.substringAfter(':', "")
        circuit.components.find { it.id == id }?.let { component ->
            return holeAt(component.terminals[pin], pin, id)
        }
        if (circuit.externalDevices.any { it.id == id }) {
            // Schematic Uno pin anchors; must mirror BoardGeometry.endpoint.
            val (yMeters, zMeters) = when (pin) {
                "D13" -> UNO_PIN_Y_M to UNO_D13_Z_M
                "GND" -> UNO_PIN_Y_M to UNO_GND_Z_M
                else -> throw IllegalArgumentException("Unknown external pin: $address")
            }
            return metersToModel(UNO_PIN_X_M, yMeters, zMeters)
        }
        throw IllegalArgumentException("Unknown overlay endpoint: $address")
    }

    internal fun holePosition(address: String): FloatArray {
        val rest = if (address.startsWith("BB1:")) address.substring(4) else throw IllegalArgumentException("Not a board address: $address")
        if (rest.startsWith("RAIL:")) {
            val segments = rest.split(':')
            require(segments.size == 5) { "Malformed rail address: $address" }
            val index = segments[4].toIntOrNull() ?: throw IllegalArgumentException("Malformed rail address: $address")
            require(index in 1..25) { "Rail index out of range: $address" }
            require(segments[3] == "A" || segments[3] == "B") { "Unknown rail segment: $address" }
            val start = if (segments[3] == "A") 3 else 33
            val row = start + 6 * ((index - 1) / 5) + (index - 1) % 5
            val z = when (segments[1] + segments[2]) {
                "L+" -> RAIL_L_PLUS_Z; "L-" -> RAIL_L_MINUS_Z
                "R+" -> RAIL_R_PLUS_Z; "R-" -> RAIL_R_MINUS_Z
                else -> throw IllegalArgumentException("Unknown rail side: $address")
            }
            return floatArrayOf(rowX(row), HOLE_FACE_Y, z)
        }
        require(rest.length >= 2) { "Malformed hole: $address" }
        val letter = rest[0].uppercaseChar()
        val row = rest.substring(1).toIntOrNull() ?: throw IllegalArgumentException("Malformed hole: $address")
        require(row in 1..ROW_COUNT && letter in 'A'..'J') { "Unknown hole: $address" }
        return floatArrayOf(rowX(row), HOLE_FACE_Y, colZ(letter))
    }

    private fun rowX(row: Int) = ROW1_X + (row - 1) * ROW_PITCH

    private fun colZ(letter: Char): Float {
        val index = letter - 'A'
        return if (index < 5) COL_A_Z + index * COL_PITCH else COL_F_Z + (index - 5) * COL_PITCH
    }

    /**
     * Schematic meters (board-local, nominal 2.54 mm grid) to model units. Board-map +X
     * runs A toward J (model +Z), +Z runs along the rows (model +X), and +Y is up, which
     * is the model's -Y because the CAD model is authored hole-side down.
     */
    private fun metersToModel(xMeters: Float, yMeters: Float, zMeters: Float): FloatArray = floatArrayOf(
        ROW1_X + (zMeters / NOMINAL_PITCH_M) * ROW_PITCH,
        HOLE_FACE_Y - (yMeters / NOMINAL_PITCH_M) * ROW_PITCH,
        COL_A_Z + (xMeters / NOMINAL_PITCH_M) * COL_PITCH,
    )

    private fun canonicalTerminals(type: String): List<String> = when (type) {
        "led" -> listOf("anode", "cathode")
        "resistor" -> listOf("a", "b")
        "button" -> listOf("a1", "a2", "b1", "b2")
        "power_supply" -> listOf("positive", "negative")
        else -> throw IllegalArgumentException("Unsupported component type: $type")
    }

    // --- Geometry --------------------------------------------------------------------------

    private fun addGuide(at: FloatArray) {
        val key = "${(at[0] * 2).toInt()},${(at[2] * 2).toInt()}"
        if (!guideKeys.add(key)) return
        addBox(
            guides,
            floatArrayOf(at[0], HOLE_FACE_Y - GUIDE_HALF, at[2]),
            floatArrayOf(GUIDE_HALF, GUIDE_HALF, GUIDE_HALF),
            "guide",
        )
    }

    /** Upright 5 mm LED with a dome and two leads into its holes. */
    private fun addLed(part: Part, anode: FloatArray, cathode: FloatArray) {
        val center = floatArrayOf(
            (anode[0] + cathode[0]) / 2f,
            HOLE_FACE_Y - LED_BODY_LIFT,
            (anode[2] + cathode[2]) / 2f,
        )
        addCylinder(part, floatArrayOf(center[0], HOLE_FACE_Y - 6f, center[2]), center, LED_RADIUS, "led-red")
        addDome(part, center, LED_RADIUS, "led-red")
        for (hole in listOf(anode, cathode)) {
            addCylinder(part, floatArrayOf(hole[0], HOLE_FACE_Y + 1f, hole[2]), floatArrayOf(hole[0], HOLE_FACE_Y - LED_BODY_LIFT + 6f, hole[2]), LEAD_RADIUS, "silver")
        }
    }

    /** Horizontal body with value bands and slanted leads into its holes. */
    private fun addResistor(part: Part, a: FloatArray, b: FloatArray) {
        val span = distance(a, b)
        val axis = normalize(floatArrayOf(b[0] - a[0], 0f, b[2] - a[2]))
        val center = floatArrayOf((a[0] + b[0]) / 2f, HOLE_FACE_Y - RESISTOR_BODY_LIFT, (a[2] + b[2]) / 2f)
        val bodyLength = span * 0.6f
        val endA = combine(center, axis, -bodyLength / 2f)
        val endB = combine(center, axis, bodyLength / 2f)
        addCylinder(part, endA, endB, RESISTOR_RADIUS, "resistor-body")
        val bandKeys = listOf("band-1", "band-2", "band-3", "band-4")
        val bandOffsets = listOf(-0.24f, -0.08f, 0.08f, 0.24f)
        bandKeys.forEachIndexed { i, key ->
            addCylinder(part, combine(center, axis, bodyLength * bandOffsets[i]), combine(center, axis, bodyLength * bandOffsets[i] + 4f), RESISTOR_RADIUS + 1.2f, key)
        }
        addCylinder(part, floatArrayOf(a[0], HOLE_FACE_Y + 1f, a[2]), endA, LEAD_RADIUS, "silver")
        addCylinder(part, floatArrayOf(b[0], HOLE_FACE_Y + 1f, b[2]), endB, LEAD_RADIUS, "silver")
    }

    /** Square body spanning its four holes, with a leg into each hole. */
    private fun addButton(part: Part, points: List<FloatArray>) {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        points.forEach { p ->
            minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0])
            minZ = minOf(minZ, p[2]); maxZ = maxOf(maxZ, p[2])
        }
        val center = floatArrayOf((minX + maxX) / 2f, HOLE_FACE_Y - BUTTON_LIFT - BUTTON_HEIGHT / 2f, (minZ + maxZ) / 2f)
        val half = floatArrayOf((maxX - minX) / 2f + BUTTON_PAD, BUTTON_HEIGHT / 2f, (maxZ - minZ) / 2f + BUTTON_PAD)
        addBox(part, center, half, "button-black")
        points.forEach { hole ->
            addCylinder(part, floatArrayOf(hole[0], HOLE_FACE_Y + 1f, hole[2]), floatArrayOf(hole[0], center[1] - half[1], hole[2]), LEAD_RADIUS, "silver")
        }
    }

    /** Connection markers for the external supply; Unity renders no supply body either. */
    private fun addPowerMarkers(part: Part, positive: FloatArray, negative: FloatArray) {
        for ((hole, key) in listOf(positive to "power-positive", negative to "power-negative")) {
            addBox(part, floatArrayOf(hole[0], HOLE_FACE_Y - POWER_MARKER_LIFT, hole[2]), floatArrayOf(GUIDE_HALF, GUIDE_HALF, GUIDE_HALF), key)
            addCylinder(part, floatArrayOf(hole[0], HOLE_FACE_Y + 1f, hole[2]), floatArrayOf(hole[0], HOLE_FACE_Y - POWER_MARKER_LIFT - GUIDE_HALF, hole[2]), LEAD_RADIUS, "silver")
        }
    }

    /** Hemisphere cap for the LED, rising toward -Y (the model's visible side). */
    private fun addDome(part: Part, center: FloatArray, radius: Float, materialKey: String) {
        val rings = 8
        val segments = 12
        for (ring in 0 until rings) {
            val phi0 = (ring.toFloat() / rings) * (Math.PI / 2).toFloat()
            val phi1 = ((ring + 1).toFloat() / rings) * (Math.PI / 2).toFloat()
            for (seg in 0 until segments) {
                val theta0 = (seg.toFloat() / segments) * 2f * Math.PI.toFloat()
                val theta1 = ((seg + 1).toFloat() / segments) * 2f * Math.PI.toFloat()
                val p00 = domePoint(center, radius, phi0, theta0)
                val p01 = domePoint(center, radius, phi0, theta1)
                val p10 = domePoint(center, radius, phi1, theta0)
                val p11 = domePoint(center, radius, phi1, theta1)
                val v00 = part.vertex(p00, domeNormal(p00, center))
                val v01 = part.vertex(p01, domeNormal(p01, center))
                val v10 = part.vertex(p10, domeNormal(p10, center))
                val v11 = part.vertex(p11, domeNormal(p11, center))
                part.triangle(v00, v10, v11, materialKey)
                part.triangle(v00, v11, v01, materialKey)
            }
        }
    }

    private fun domePoint(center: FloatArray, radius: Float, phi: Float, theta: Float) = floatArrayOf(
        center[0] + radius * sin(phi) * cos(theta),
        center[1] - radius * sin(phi),
        center[2] + radius * sin(phi) * sin(theta),
    )

    private fun domeNormal(point: FloatArray, center: FloatArray) =
        normalize(floatArrayOf(point[0] - center[0], point[1] - center[1], point[2] - center[2]))

    /** Axis-aligned box; half gives the positive extents per axis. */
    private fun addBox(part: Part, center: FloatArray, half: FloatArray, materialKey: String) {
        val x0 = center[0] - half[0]; val x1 = center[0] + half[0]
        val y0 = center[1] - half[1]; val y1 = center[1] + half[1]
        val z0 = center[2] - half[2]; val z1 = center[2] + half[2]
        val faces = arrayOf(
            floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y1, z0), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y0, z1), floatArrayOf(1f, 0f, 0f),
            floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1), floatArrayOf(0f, -1f, 0f),
            floatArrayOf(x0, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(x0, y0, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y0, z0), floatArrayOf(0f, 0f, -1f),
            floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1), floatArrayOf(0f, 0f, 1f),
        )
        for (face in 0 until faces.size / 5) {
            val o = face * 5
            val a = part.vertex(faces[o], faces[o + 4])
            val b = part.vertex(faces[o + 1], faces[o + 4])
            val c = part.vertex(faces[o + 2], faces[o + 4])
            val d = part.vertex(faces[o + 3], faces[o + 4])
            part.triangle(a, b, c, materialKey)
            part.triangle(a, c, d, materialKey)
        }
    }

    private fun addCylinder(part: Part, from: FloatArray, to: FloatArray, radius: Float, materialKey: String) {
        addTube(part, listOf(from, to), radius, materialKey)
    }

    /** Open tube along a polyline with parallel-transported ring frames. */
    private fun addTube(part: Part, path: List<FloatArray>, radius: Float, materialKey: String) {
        require(path.size >= 2) { "Tube path needs two points." }
        val samples = ArrayList<FloatArray>()
        if (path.size == 2) {
            for (i in 0..TUBE_STEPS) samples.add(lerp(path[0], path[1], i.toFloat() / TUBE_STEPS))
        } else {
            samples.addAll(path)
        }
        var normal = floatArrayOf(0f, 0f, 0f)
        var lastRing: IntArray? = null
        for (i in samples.indices) {
            val tangent = when {
                i == 0 -> subtract(samples[1], samples[0])
                i == samples.size - 1 -> subtract(samples[i], samples[i - 1])
                else -> subtract(samples[i + 1], samples[i - 1])
            }
            normalizeInPlace(tangent)
            normal = when {
                length(normal) == 0f -> normalize(cross(tangent, if (abs(tangent[0]) < 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)))
                // Parallel transport keeps the ring frame stable along bends.
                else -> run {
                    val projected = combine(normal, tangent, -dot(normal, tangent))
                    if (length(projected) > 1e-6f) normalize(projected) else normal
                }
            }
            val binormal = cross(tangent, normal)
            val ring = IntArray(TUBE_SIDES)
            for (s in 0 until TUBE_SIDES) {
                val theta = (s.toFloat() / TUBE_SIDES) * 2f * Math.PI.toFloat()
                val direction = floatArrayOf(
                    normal[0] * cos(theta) + binormal[0] * sin(theta),
                    normal[1] * cos(theta) + binormal[1] * sin(theta),
                    normal[2] * cos(theta) + binormal[2] * sin(theta),
                )
                ring[s] = part.vertex(combine(samples[i], direction, radius), direction)
            }
            lastRing?.let { previous ->
                for (s in 0 until TUBE_SIDES) {
                    val next = (s + 1) % TUBE_SIDES
                    part.triangle(previous[s], ring[s], ring[next], materialKey)
                    part.triangle(previous[s], ring[next], previous[next], materialKey)
                }
            }
            lastRing = ring
        }
    }

    /** Raised arc between two board points, in the model's visible (-Y) direction. */
    private fun arcPoints(from: FloatArray, to: FloatArray): List<FloatArray> {
        val peak = (distance(from, to) * 0.3f).coerceAtMost(60f) + 12f
        val mid = floatArrayOf(
            (from[0] + to[0]) / 2f,
            (from[1] + to[1]) / 2f - peak,
            (from[2] + to[2]) / 2f,
        )
        val points = ArrayList<FloatArray>(TUBE_STEPS + 1)
        for (i in 0..TUBE_STEPS) {
            val t = i.toFloat() / TUBE_STEPS
            val u = 1f - t
            points.add(
                floatArrayOf(
                    u * u * from[0] + 2f * u * t * mid[0] + t * t * to[0],
                    u * u * from[1] + 2f * u * t * mid[1] + t * t * to[1],
                    u * u * from[2] + 2f * u * t * mid[2] + t * t * to[2],
                ),
            )
        }
        return points
    }

    private fun wireColor(color: String): String = when (color.lowercase()) {
        "red" -> "wire-red"; "black" -> "wire-black"; "yellow" -> "wire-yellow"
        "blue" -> "wire-blue"; "green" -> "wire-green"; "white" -> "wire-white"
        "orange" -> "wire-orange"; "brown" -> "wire-brown"; "gray", "grey" -> "wire-gray"
        "purple", "violet" -> "wire-purple"
        else -> "wire-slate"
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun subtract(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun length(a: FloatArray) = sqrt(dot(a, a))
    private fun normalize(a: FloatArray): FloatArray {
        val l = length(a)
        return if (l < 1e-9f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
    }

    private fun normalizeInPlace(a: FloatArray) {
        val l = length(a)
        if (l > 1e-9f) { a[0] /= l; a[1] /= l; a[2] /= l }
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun combine(a: FloatArray, direction: FloatArray, scale: Float) = floatArrayOf(
        a[0] + direction[0] * scale, a[1] + direction[1] * scale, a[2] + direction[2] * scale,
    )

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(
        a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t,
    )

    private companion object {
        // Hole grid measured from the breadboard GLB's 830 painted hole quads.
        const val ROW1_X = -764.6f
        const val ROW_PITCH = 24.6645f
        const val ROW_COUNT = 63
        const val COL_A_Z = -138.0f
        const val COL_PITCH = 24.075f
        const val COL_F_Z = 41.7f
        const val RAIL_L_PLUS_Z = -243.6f
        const val RAIL_L_MINUS_Z = -219.9f
        const val RAIL_R_PLUS_Z = 219.9f
        const val RAIL_R_MINUS_Z = 243.6f
        const val HOLE_FACE_Y = -39.6f
        const val NOMINAL_PITCH_M = 0.00254f

        const val GUIDE_HALF = 6f
        const val LED_RADIUS = 24.5f
        const val LED_BODY_LIFT = 28f
        const val RESISTOR_RADIUS = 8f
        const val RESISTOR_BODY_LIFT = 10f
        const val BUTTON_HEIGHT = 34f
        const val BUTTON_LIFT = 4f
        const val BUTTON_PAD = 8f
        const val POWER_MARKER_LIFT = 8f
        const val LEAD_RADIUS = 2.5f
        const val WIRE_RADIUS = 4.5f
        const val TUBE_SIDES = 8
        const val TUBE_STEPS = 16

        // Schematic Uno proxy in meters, mirroring BoardGeometry's display-only anchors.
        const val UNO_CENTER_X_M = -0.0525f
        const val UNO_CENTER_Z_M = 0.0385f
        const val UNO_TOP_Y_M = 0.001f
        const val UNO_HALF_ROWS = 237.9f
        const val UNO_HALF_ACROSS = 279.6f
        const val UNO_HALF_THICK = 9.7f
        const val UNO_PIN_X_M = -0.024f
        const val UNO_PIN_Y_M = 0.003f
        const val UNO_D13_Z_M = 0.025f
        const val UNO_GND_Z_M = 0.033f
    }
}

/** One named mesh with per-material index ranges, ready for glTF emission. */
internal class OverlayMesh(
    val name: String,
    val positions: List<Float>,
    val normals: List<Float>,
    val indices: List<Int>,
    val materialRanges: List<Pair<String, IntArray>>,
)

/**
 * Emits the overlay parts as a self-contained binary glTF 2.0 asset. Vertex data is baked
 * in model coordinates, so every node carries an identity transform and the merged
 * breadboard+overlay asset can share one transform chain.
 */
private class OverlayGlbWriter(private val meshes: List<OverlayMesh>) {
    fun build(): ByteBuffer {
        val bin = ByteArrayOutputStream()
        val accessors = JSONArray()
        val bufferViews = JSONArray()
        val materials = JSONArray()
        val materialIndices = LinkedHashMap<String, Int>()
        val meshesJson = JSONArray()

        fun materialIndex(key: String): Int {
            materialIndices[key]?.let { return it }
            val rgba = rgba(key)
            val material = JSONObject()
                .put("name", key)
                .put(
                    "pbrMetallicRoughness",
                    JSONObject()
                        .put("baseColorFactor", JSONArray(rgba.map { rounded(it) }))
                        .put("metallicFactor", 0.0)
                        .put("roughnessFactor", if (key.startsWith("wire") || key == "led-red") 0.35 else 0.6),
                )
            if (rgba[3] < 1f) material.put("alphaMode", "BLEND")
            material.put("doubleSided", true)
            val index = materials.length()
            materials.put(material)
            materialIndices[key] = index
            return index
        }

        for (mesh in meshes) {
            require(mesh.positions.size == mesh.normals.size) { "Overlay mesh ${mesh.name} has mismatched normals." }
            require(mesh.positions.size % 3 == 0) { "Overlay mesh ${mesh.name} has malformed vertices." }
            // One shared POSITION/NORMAL pair per mesh; materials differ only by indices.
            val positionView = writeFloatView(bin, bufferViews, mesh.positions.toFloatArray())
            val normalView = writeFloatView(bin, bufferViews, mesh.normals.toFloatArray())
            val positionAccessor = vec3Accessor(accessors, positionView, mesh.positions.size / 3, mesh.positions.toFloatArray())
            val normalAccessor = vec3Accessor(accessors, normalView, mesh.normals.size / 3, mesh.normals.toFloatArray())
            val primitives = JSONArray()
            for ((key, range) in mesh.materialRanges) {
                val indexSlice = mesh.indices.subList(range[0], range[0] + range[1]).toIntArray()
                val indicesView = writeIntView(bin, bufferViews, indexSlice)
                val indicesAccessor = scalarAccessor(accessors, indicesView, range[1])
                primitives.put(
                    JSONObject()
                        .put("attributes", JSONObject().put("POSITION", positionAccessor).put("NORMAL", normalAccessor))
                        .put("indices", indicesAccessor)
                        .put("material", materialIndex(key))
                        .put("mode", 4),
                )
            }
            meshesJson.put(JSONObject().put("name", mesh.name).put("primitives", primitives))
        }

        val nodes = JSONArray()
        for (i in meshes.indices) nodes.put(JSONObject().put("name", meshes[i].name).put("mesh", i))
        val gltf = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "circuit-overlay"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray(meshes.indices.toList()))))
            .put("nodes", nodes)
            .put("meshes", meshesJson)
            .put("materials", materials)
            .put("accessors", accessors)
            .put("bufferViews", bufferViews)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
        return GlbCodec.encode(gltf, bin.toByteArray())
    }

    private fun writeFloatView(bin: ByteArrayOutputStream, bufferViews: JSONArray, values: FloatArray): Int {
        align4(bin)
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        return putView(bin, bufferViews, buffer.array())
    }

    private fun writeIntView(bin: ByteArrayOutputStream, bufferViews: JSONArray, values: IntArray): Int {
        align4(bin)
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putInt(value)
        return putView(bin, bufferViews, buffer.array())
    }

    private fun putView(bin: ByteArrayOutputStream, bufferViews: JSONArray, bytes: ByteArray): Int {
        val view = JSONObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", bytes.size)
        bin.write(bytes)
        bufferViews.put(view)
        return bufferViews.length() - 1
    }

    private fun align4(bin: ByteArrayOutputStream) {
        while (bin.size() % 4 != 0) bin.write(0)
    }

    /** glTF requires min/max on POSITION accessors; gltfio also reads asset bounds from them. */
    private fun vec3Accessor(accessors: JSONArray, view: Int, count: Int, values: FloatArray): Int {
        val min = JSONArray(); val max = JSONArray()
        for (axis in 0 until 3) {
            var low = Float.MAX_VALUE; var high = -Float.MAX_VALUE
            for (v in axis until values.size step 3) {
                low = minOf(low, values[v]); high = maxOf(high, values[v])
            }
            min.put(rounded(low)); max.put(rounded(high))
        }
        accessors.put(
            JSONObject()
                .put("bufferView", view).put("componentType", 5126).put("count", count)
                .put("type", "VEC3").put("min", min).put("max", max),
        )
        return accessors.length() - 1
    }

    private fun scalarAccessor(accessors: JSONArray, view: Int, count: Int): Int {
        accessors.put(JSONObject().put("bufferView", view).put("componentType", 5125).put("count", count).put("type", "SCALAR"))
        return accessors.length() - 1
    }

    private fun rounded(value: Float): Double = Math.round(value.toDouble() * 1e5) / 1e5

    private fun rgba(key: String): FloatArray = when (key) {
        "guide" -> floatArrayOf(0.20f, 0.75f, 0.35f, 1f)
        "silver" -> floatArrayOf(0.62f, 0.65f, 0.66f, 1f)
        "led-red" -> floatArrayOf(0.85f, 0.12f, 0.10f, 0.85f)
        "resistor-body" -> floatArrayOf(0.72f, 0.56f, 0.38f, 1f)
        "band-1", "band-2" -> floatArrayOf(0.70f, 0.10f, 0.10f, 1f)
        "band-3" -> floatArrayOf(0.45f, 0.25f, 0.10f, 1f)
        "band-4" -> floatArrayOf(0.80f, 0.65f, 0.20f, 1f)
        "button-black" -> floatArrayOf(0.08f, 0.08f, 0.08f, 1f)
        "power-positive" -> floatArrayOf(0.80f, 0.15f, 0.12f, 1f)
        "power-negative" -> floatArrayOf(0.10f, 0.10f, 0.12f, 1f)
        "uno-teal" -> floatArrayOf(0.06f, 0.40f, 0.43f, 1f)
        "wire-red" -> floatArrayOf(0.80f, 0.15f, 0.12f, 1f)
        "wire-black" -> floatArrayOf(0.10f, 0.10f, 0.10f, 1f)
        "wire-yellow" -> floatArrayOf(0.85f, 0.63f, 0.08f, 1f)
        "wire-blue" -> floatArrayOf(0.10f, 0.25f, 0.75f, 1f)
        "wire-green" -> floatArrayOf(0.18f, 0.50f, 0.30f, 1f)
        "wire-white" -> floatArrayOf(0.90f, 0.90f, 0.90f, 1f)
        "wire-orange" -> floatArrayOf(0.90f, 0.45f, 0.10f, 1f)
        "wire-brown" -> floatArrayOf(0.45f, 0.28f, 0.12f, 1f)
        "wire-gray" -> floatArrayOf(0.55f, 0.58f, 0.58f, 1f)
        "wire-purple" -> floatArrayOf(0.50f, 0.20f, 0.60f, 1f)
        else -> floatArrayOf(0.20f, 0.28f, 0.24f, 1f)
    }
}

/** Minimal binary glTF encoder/decoder used by the overlay and the merge step. */
internal object GlbCodec {
    const val MAGIC = 0x46546C67
    const val JSON_CHUNK = 0x4E4F534A
    const val BIN_CHUNK = 0x004E4942

    class Glb(val json: JSONObject, val bin: ByteArray)

    fun encode(json: JSONObject, bin: ByteArray): ByteBuffer {
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonPadded = (jsonBytes.size + 3) / 4 * 4
        val binPadded = (bin.size + 3) / 4 * 4
        val total = 12 + 8 + jsonPadded + 8 + binPadded
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(2).putInt(total)
        out.putInt(jsonPadded).putInt(JSON_CHUNK).put(jsonBytes)
        repeat(jsonPadded - jsonBytes.size) { out.put(0x20.toByte()) }
        out.putInt(binPadded).putInt(BIN_CHUNK).put(bin)
        repeat(binPadded - bin.size) { out.put(0.toByte()) }
        out.flip()
        return out
    }

    fun decode(buffer: ByteBuffer): Glb {
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() >= 20) { "This GLB is too short to be valid." }
        require(input.int == MAGIC) { "Not a binary glTF file." }
        input.int // version
        val total = input.int
        require(total <= input.capacity()) { "GLB header length exceeds its buffer." }
        val jsonLength = input.int
        require(input.int == JSON_CHUNK) { "GLB JSON chunk missing." }
        val jsonBytes = ByteArray(jsonLength)
        input.get(jsonBytes)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8).trim())
        var bin = ByteArray(0)
        if (input.remaining() >= 8) {
            val binLength = input.int
            require(input.int == BIN_CHUNK) { "GLB binary chunk missing." }
            require(binLength <= input.remaining()) { "GLB binary chunk exceeds its buffer." }
            bin = ByteArray(binLength)
            input.get(bin)
        }
        return Glb(json, bin)
    }
}

/**
 * Appends the overlay's meshes, materials, accessors and bufferViews to the bundled
 * breadboard GLB, keeping every original buffer offset intact. The overlay nodes become
 * additional children of the model's FBX_Root, so the merged asset renders the breadboard
 * and the circuit with one transform chain.
 */
internal object CircuitOverlayMerger {
    fun merge(base: ByteBuffer, overlay: ByteBuffer): ByteBuffer {
        val baseGlb = GlbCodec.decode(base)
        val overlayGlb = GlbCodec.decode(overlay)
        val root = baseGlb.json
        for (key in listOf("nodes", "meshes", "materials", "accessors", "bufferViews", "buffers")) {
            require(root.has(key)) { "The breadboard GLB is missing '$key'." }
        }
        val alignedBaseBin = (baseGlb.bin.size + 3) / 4 * 4

        val meshOffset = root.getJSONArray("meshes").length()
        val materialOffset = root.getJSONArray("materials").length()
        val accessorOffset = root.getJSONArray("accessors").length()
        val bufferViewOffset = root.getJSONArray("bufferViews").length()
        val nodeOffset = root.getJSONArray("nodes").length()

        val overlayJson = overlayGlb.json
        for (i in 0 until overlayJson.getJSONArray("nodes").length()) {
            overlayJson.getJSONArray("nodes").getJSONObject(i).put("mesh", overlayJson.getJSONArray("nodes").getJSONObject(i).optInt("mesh") + meshOffset)
        }
        for (i in 0 until overlayJson.getJSONArray("meshes").length()) {
            val mesh = overlayJson.getJSONArray("meshes").getJSONObject(i)
            val primitives = mesh.getJSONArray("primitives")
            for (p in 0 until primitives.length()) {
                val primitive = primitives.getJSONObject(p)
                val attributes = primitive.getJSONObject("attributes")
                for (attribute in attributes.keySet()) attributes.put(attribute, attributes.getInt(attribute) + accessorOffset)
                primitive.put("indices", primitive.getInt("indices") + accessorOffset)
                primitive.put("material", primitive.getInt("material") + materialOffset)
            }
        }
        for (i in 0 until overlayJson.getJSONArray("accessors").length()) {
            val accessor = overlayJson.getJSONArray("accessors").getJSONObject(i)
            if (accessor.has("bufferView")) accessor.put("bufferView", accessor.getInt("bufferView") + bufferViewOffset)
        }
        for (i in 0 until overlayJson.getJSONArray("bufferViews").length()) {
            val view = overlayJson.getJSONArray("bufferViews").getJSONObject(i)
            require(view.optInt("buffer") == 0) { "The overlay must use a single embedded buffer." }
            view.put("byteOffset", view.getInt("byteOffset") + alignedBaseBin)
        }

        val fbxRoot = findFbxRoot(root) ?: throw IllegalArgumentException("The breadboard GLB has no FBX_Root node.")
        val children = fbxRoot.optJSONArray("children") ?: JSONArray()
        for (i in 0 until overlayJson.getJSONArray("nodes").length()) children.put(nodeOffset + i)
        fbxRoot.put("children", children)
        appendAll(root.getJSONArray("nodes"), overlayJson.getJSONArray("nodes"))
        appendAll(root.getJSONArray("meshes"), overlayJson.getJSONArray("meshes"))
        appendAll(root.getJSONArray("materials"), overlayJson.getJSONArray("materials"))
        appendAll(root.getJSONArray("accessors"), overlayJson.getJSONArray("accessors"))
        appendAll(root.getJSONArray("bufferViews"), overlayJson.getJSONArray("bufferViews"))
        root.getJSONArray("buffers").getJSONObject(0).put("byteLength", alignedBaseBin + overlayGlb.bin.size)

        val bin = ByteArray(alignedBaseBin + overlayGlb.bin.size)
        baseGlb.bin.copyInto(bin)
        overlayGlb.bin.copyInto(bin, alignedBaseBin)
        return GlbCodec.encode(root, bin)
    }

    private fun findFbxRoot(root: JSONObject): JSONObject? {
        val nodes = root.getJSONArray("nodes")
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optString("name") == "FBX_Root") return node
        }
        return null
    }

    private fun appendAll(target: JSONArray, source: JSONArray) {
        for (i in 0 until source.length()) target.put(source.get(i))
    }
}
