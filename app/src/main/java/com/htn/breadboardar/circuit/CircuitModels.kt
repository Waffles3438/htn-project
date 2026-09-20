package com.htn.breadboardar.circuit

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Typed model for the Circuit API "placement v3" contract (circuit-api/schemas/placement.schema.json).
 *
 * The JSON is semantic: board addresses ("BB1:A15"), rail addresses ("BB1:RAIL:L:+:A:12") and
 * component terminals ("led_1:anode"). No XYZ coordinates ever cross this boundary — the XR
 * renderer derives geometry from the board map and its own anchors.
 */
class CircuitParseException(message: String) : Exception(message)

data class CircuitComponent(
    val id: String,
    val type: String,
    val value: String,
    val assetId: String,
    val buildStep: Int,
    val mountType: String,
    val terminals: Map<String, String>,
)

data class JumperWire(
    val id: String,
    val from: String,
    val to: String,
    val color: String,
    val buildStep: Int,
)

data class CircuitNet(val id: String, val members: List<String>)

data class BuildInstruction(val step: Int, val componentIds: List<String>, val text: String)

data class ExternalDevice(val id: String, val type: String, val model: String, val assetId: String)

data class CircuitDefinition(
    val rawJson: String,
    val version: Int,
    val sessionId: String,
    val breadboardModel: String,
    val title: String,
    val prompt: String,
    val source: String,
    val holeMapVersion: String,
    val physicalVerified: Boolean,
    val requiredParts: List<String>,
    val components: List<CircuitComponent>,
    val jumperWires: List<JumperWire>,
    val nets: List<CircuitNet>,
    val instructions: List<BuildInstruction>,
    val externalDevices: List<ExternalDevice>,
    val firmwareCode: String?,
    val warnings: List<String>,
) {
    val isExternalControllerCircuit: Boolean get() = externalDevices.isNotEmpty()

    companion object {
        const val SUPPORTED_VERSION = 3

        /** Parses a placement-v3 response body. Rejects other versions with a clear message. */
        fun parse(body: String): CircuitDefinition {
            val root = try {
                JSONObject(body)
            } catch (error: JSONException) {
                throw CircuitParseException("The backend response is not valid circuit JSON.")
            }
            val version = root.optInt("version", -1)
            if (version != SUPPORTED_VERSION) {
                throw CircuitParseException(
                    "Unsupported circuit format (version $version). Update the backend to placement v$SUPPORTED_VERSION.",
                )
            }
            val validation = root.optJSONObject("validation")
            val breadboard = root.optJSONObject("breadboard")
            if (validation?.optBoolean("valid") != true) {
                throw CircuitParseException("This circuit has not passed electrical validation.")
            }
            if (root.optString("breadboardModel") != "demo_breadboard_v1" ||
                breadboard?.optString("model") != "demo_breadboard_v1") {
                throw CircuitParseException("This breadboard model is not supported.")
            }
            if (root.optString("sessionId").isBlank() || (root.optJSONArray("components")?.length() ?: 0) == 0 ||
                root.optJSONArray("jumperWires") == null || root.optJSONArray("nets") == null) {
                throw CircuitParseException("The circuit response is incomplete.")
            }
            return CircuitDefinition(
                rawJson = body,
                version = version,
                sessionId = root.optString("sessionId"),
                breadboardModel = root.optString("breadboardModel"),
                title = root.optString("title", ""),
                prompt = root.optString("prompt", ""),
                source = root.optString("source", ""),
                holeMapVersion = breadboard?.optString("holeMapVersion") ?: "",
                physicalVerified = breadboard?.optBoolean("physicalVerified", false) ?: false,
                requiredParts = root.optJSONArray("requiredParts").mapObjects { part ->
                    "${part.optString("type")} ${part.optString("value")} ×${part.optInt("quantity")}"
                },
                components = root.optJSONArray("components").mapObjects { component ->
                    val terminals = LinkedHashMap<String, String>()
                    component.optJSONObject("mount")?.optJSONObject("terminals")?.let { map ->
                        map.keys().forEach { key -> terminals[key] = map.optString(key) }
                    }
                    CircuitComponent(
                        id = component.optString("id"),
                        type = component.optString("type"),
                        value = component.optString("value"),
                        assetId = component.optString("assetId"),
                        buildStep = component.optInt("buildStep", 0),
                        mountType = component.optJSONObject("mount")?.optString("type") ?: "",
                        terminals = terminals,
                    )
                },
                jumperWires = root.optJSONArray("jumperWires").mapObjects { wire ->
                    JumperWire(
                        id = wire.optString("id"),
                        from = wire.optString("from"),
                        to = wire.optString("to"),
                        color = wire.optString("color"),
                        buildStep = wire.optInt("buildStep", 0),
                    )
                },
                nets = root.optJSONArray("nets").mapObjects { net ->
                    CircuitNet(
                        id = net.optString("id"),
                        members = net.optJSONArray("members").mapStrings(),
                    )
                },
                instructions = root.optJSONArray("instructions").mapObjects { instruction ->
                    BuildInstruction(
                        step = instruction.optInt("step", 0),
                        componentIds = instruction.optJSONArray("componentIds").mapStrings(),
                        text = instruction.optString("text"),
                    )
                },
                externalDevices = root.optJSONArray("externalDevices").mapObjects { device ->
                    ExternalDevice(
                        id = device.optString("id"),
                        type = device.optString("type"),
                        model = device.optString("model"),
                        assetId = device.optString("assetId"),
                    )
                },
                firmwareCode = root.optJSONObject("firmware")?.optString("code"),
                warnings = validation?.optJSONArray("warnings").mapStrings(),
            )
        }

        private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            val result = ArrayList<T>(length())
            for (index in 0 until length()) {
                val element = optJSONObject(index) ?: throw CircuitParseException("Invalid circuit entry.")
                result.add(transform(element))
            }
            return result
        }

        private fun JSONArray?.mapStrings(): List<String> {
            if (this == null) return emptyList()
            val result = ArrayList<String>(length())
            for (index in 0 until length()) result.add(optString(index))
            return result
        }
    }
}
