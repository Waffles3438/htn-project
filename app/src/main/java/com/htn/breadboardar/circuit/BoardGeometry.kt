package com.htn.breadboardar.circuit

import org.json.JSONObject

data class BoardPoint(val x: Float, val y: Float, val z: Float)

/** Coordinates come only from the versioned map exported by circuit/board.py. */
class BoardGeometry(json: String) {
    val version: String
    val holes: Map<String, BoardPoint>
    /** Leftmost rail x, so overlays can shift the letter axis to start at zero. */
    val minX: Float
    init {
        val root = JSONObject(json)
        version = root.getString("holeMapVersion")
        val list = root.getJSONArray("holes")
        holes = (0 until list.length()).associate { i ->
            val hole = list.getJSONObject(i)
            val p = hole.getJSONObject("position")
            hole.getString("id") to BoardPoint(p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.getDouble("z").toFloat())
        }
        minX = holes.values.minOf { it.x }
    }

    fun hole(address: String): BoardPoint? {
        if (!address.startsWith("BB1:")) return null
        val id = if (address.startsWith("BB1:RAIL:")) {
            val parts = address.split(':')
            if (parts.size != 6) return null
            parts.drop(2).joinToString("")
        } else address.removePrefix("BB1:")
        return holes[id]
    }

    // The Uno outline and pin positions are schematic presentation geometry, not measured hardware.
    fun endpoint(address: String, circuit: CircuitDefinition): BoardPoint? {
        hole(address)?.let { return it }
        val id = address.substringBefore(':')
        val pin = address.substringAfter(':', "")
        circuit.components.find { it.id == id }?.terminals?.get(pin)?.let { return hole(it) }
        if (circuit.externalDevices.any { it.id == id && it.type == "arduino_uno" }) {
            return when (pin) {
                "D13" -> BoardPoint(-0.024f, 0.003f, 0.025f)
                "GND" -> BoardPoint(-0.024f, 0.003f, 0.033f)
                else -> null
            }
        }
        return null
    }

    fun validate(circuit: CircuitDefinition) {
        fun check(value: Boolean, message: String) { if (!value) throw CircuitParseException(message) }
        check(version == circuit.holeMapVersion, "The circuit uses a different breadboard map. Update the app or regenerate it.")
        val ids = mutableSetOf<String>()
        val occupied = mutableSetOf<String>()
        circuit.components.forEach { c ->
            check(c.id.isNotBlank() && ids.add(c.id), "Duplicate or missing component ID.")
            val assetIds = mapOf("led" to "led_red_v1", "resistor" to "resistor_220ohm_v1", "button" to "button_momentary_v1", "power_supply" to "power_supply_5v_v1")
            check(assetIds[c.type] == c.assetId && c.buildStep > 0, "Unsupported asset or build step.")
            val expected = when (c.type) {
                "led" -> setOf("anode", "cathode")
                "resistor" -> setOf("a", "b")
                "button" -> setOf("a1", "b1", "a2", "b2")
                "power_supply" -> setOf("positive", "negative")
                else -> throw CircuitParseException("Unsupported component: ${c.type}.")
            }
            check(c.mountType == "breadboard" && c.terminals.keys == expected, "Invalid terminal map for ${c.id}.")
            c.terminals.values.forEach {
                check(hole(it) != null, "Unknown breadboard hole: $it.")
                check(occupied.add(it), "More than one component lead occupies $it.")
            }
        }
        circuit.externalDevices.forEach {
            check(it.type == "arduino_uno" && it.assetId == "arduino_uno_r3_v1" && ids.add(it.id), "Unsupported external device.")
        }
        circuit.jumperWires.forEach {
            check(ids.add(it.id), "Duplicate wire ID.")
            check(endpoint(it.from, circuit) != null && endpoint(it.to, circuit) != null, "A wire has an unknown endpoint.")
        }
        check(circuit.instructions.isNotEmpty(), "Assembly instructions are missing.")
    }
}
