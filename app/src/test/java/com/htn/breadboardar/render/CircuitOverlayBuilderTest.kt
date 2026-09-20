package com.htn.breadboardar.render

import com.htn.breadboardar.circuit.BoardGeometry
import com.htn.breadboardar.circuit.CircuitDefinition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CircuitOverlayBuilderTest {
    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResource(name)!!.readBytes()

    private fun builder(fixture: String): CircuitOverlayBuilder =
        CircuitOverlayBuilder(
            BoardGeometry(resource("board-map.json").toString(Charsets.UTF_8)),
            CircuitDefinition.parse(resource("$fixture.placement.json").toString(Charsets.UTF_8)),
        )

    private fun modified(fixture: String, change: (JSONObject) -> Unit): String =
        JSONObject(resource("$fixture.placement.json").toString(Charsets.UTF_8)).also(change).toString()

    @Test fun stripHolesMapOntoTheMeasuredGrid() {
        val overlay = builder("led")
        val a1 = overlay.holePosition("BB1:A1")
        val a63 = overlay.holePosition("BB1:A63")
        val j1 = overlay.holePosition("BB1:J1")
        val rowPitch = (a63[0] - a1[0]) / 62f
        assertEquals(-764.6f, a1[0], 0.01f)
        assertEquals(-138.0f, a1[2], 0.01f)
        assertEquals(764.6f, a63[0], 0.01f)
        // 63 rows at the model's measured pitch of about 2.5 mm.
        assertEquals(24.66f, rowPitch, 0.01f)
        // Columns A..J cross the width; J is nine columns plus the center channel past A.
        assertEquals(276.0f, j1[2] - a1[2], 0.01f)
        // Every hole sits on the model's painted hole face.
        assertEquals(-39.6f, a1[1], 0.001f)
    }

    @Test fun railIndicesFollowTheFiveHoleGroupSegments() {
        val overlay = builder("led")
        val first = overlay.holePosition("BB1:RAIL:L:+:A:1")
        assertEquals(-764.6f + 2 * 24.6645f, first[0], 0.01f)  // segment A starts at row 3
        assertEquals(-243.6f, first[2], 0.01f)                 // L+ is the outer left column
        val last = overlay.holePosition("BB1:RAIL:R:-:B:25")
        assertEquals(-764.6f + 60 * 24.6645f, last[0], 0.01f)  // segment B ends at row 61
        assertEquals(243.6f, last[2], 0.01f)                   // R- is the outer right column
    }

    @Test fun unoPinsResolveBesideTheBoard() {
        val overlay = builder("arduino_led")
        val d13 = overlay.endpointAt("mcu_1:D13")
        val gnd = overlay.endpointAt("mcu_1:GND")
        // Schematic anchors sit left of column A, along the nominal 2.54 mm grid.
        assertEquals(-138.0f + (-0.024f / 0.00254f) * 24.075f, d13[2], 0.01f)
        assertEquals(-764.6f + (0.025f / 0.00254f) * 24.6645f, d13[0], 0.01f)
        assertTrue(gnd[0] > d13[0])
    }

    @Test fun overlayGlbIsStructurallyValid() {
        val glb = GlbCodec.decode(builder("led").buildGlb())
        val nodes = glb.json.getJSONArray("nodes")
        val names = (0 until nodes.length()).map { nodes.getJSONObject(it).optString("name") }
        assertTrue(names.containsAll(listOf("led_1", "resistor_1", "power_1", "wire_1", "wire_3", "hole-guides")))
        for (meshName in listOf("led_1", "wire_1")) {
            val mesh = glb.json.getJSONArray("meshes").let { meshes ->
                (0 until meshes.length()).map { meshes.getJSONObject(it) }.first { it.optString("name") == meshName }
            }
            val primitive = mesh.getJSONArray("primitives").getJSONObject(0)
            val position = glb.json.getJSONArray("accessors").getJSONObject(primitive.getJSONObject("attributes").getInt("POSITION"))
            assertEquals("VEC3", position.getString("type"))
            assertEquals(3, position.getJSONArray("min").length())
            assertEquals(3, position.getJSONArray("max").length())
            // Overlay geometry extends toward -Y from the hole face at -39.6.
            assertTrue(position.getJSONArray("min").getDouble(1) < -39.6)
        }
        // Every accessor's data lands inside the binary chunk.
        val views = glb.json.getJSONArray("bufferViews")
        for (i in 0 until views.length()) {
            val view = views.getJSONObject(i)
            assertEquals(0, view.getInt("buffer"))
            assertTrue(view.getInt("byteOffset") + view.getInt("byteLength") <= glb.bin.size)
        }
    }

    @Test fun mergeKeepsTheBreadboardIntactAndAppendsTheOverlay() {
        val base = GlbCodec.decode(java.nio.ByteBuffer.wrap(resource("models/breadboard.glb")))
        val overlay = GlbCodec.decode(builder("led").buildGlb())
        val merged = CircuitOverlayMerger.merge(
            java.nio.ByteBuffer.wrap(resource("models/breadboard.glb")),
            builder("led").buildGlb(),
        )
        val result = GlbCodec.decode(merged)
        assertEquals(base.json.getJSONArray("accessors").length() + overlay.json.getJSONArray("accessors").length(), result.json.getJSONArray("accessors").length())
        assertEquals(
            (baseGlbBinSize + 3) / 4 * 4 + overlay.bin.size,
            result.json.getJSONArray("buffers").getJSONObject(0).getLong("byteLength").toInt(),
        )
        // The binary chunk may carry up to three padding bytes beyond the buffer length.
        val bufferLength = result.json.getJSONArray("buffers").getJSONObject(0).getLong("byteLength").toInt()
        assertTrue(result.bin.size - bufferLength in 0..3)
        // The low-poly proxy keeps its name so the renderer can still hide it, and its
        // mesh reference is unchanged.
        val nodes = result.json.getJSONArray("nodes")
        val lp = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.optString("name") == "LP" }
        assertEquals(0, lp.getInt("mesh"))
        // Overlay nodes hang under FBX_Root so they inherit the board transform.
        val fbxRoot = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.optString("name") == "FBX_Root" }
        val children = fbxRoot.getJSONArray("children")
        val baseNodes = base.json.getJSONArray("nodes")
        val baseRoot = (0 until baseNodes.length()).map { baseNodes.getJSONObject(it) }.first { it.optString("name") == "FBX_Root" }
        assertEquals(baseRoot.getJSONArray("children").length() + overlay.json.getJSONArray("nodes").length(), children.length())
        // Base accessor data is byte-for-byte untouched.
        val firstView = result.json.getJSONArray("bufferViews").getJSONObject(0)
        assertEquals(base.json.getJSONArray("bufferViews").getJSONObject(0).getInt("byteOffset"), firstView.getInt("byteOffset"))
    }

    @Test fun everyFixtureBuildsAndMerges() {
        for (fixture in listOf("led", "button_led", "arduino_led")) {
            val overlay = builder(fixture).buildGlb()
            val merged = GlbCodec.decode(CircuitOverlayMerger.merge(java.nio.ByteBuffer.wrap(resource("models/breadboard.glb")), overlay))
            assertTrue(merged.bin.isNotEmpty())
        }
    }

    @Test fun rejectsMismatchedBoardMap() {
        try {
            builder("led").run {
                CircuitOverlayBuilder(
                    BoardGeometry(resource("board-map.json").toString(Charsets.UTF_8)),
                    CircuitDefinition.parse(
                        modified("led") { it.getJSONObject("breadboard").put("holeMapVersion", "old-map") },
                    ),
                )
            }.buildGlb()
            fail("A mismatched board map was accepted")
        } catch (_: com.htn.breadboardar.circuit.CircuitParseException) {
        }
    }

    private val baseGlbBinSize: Int
        get() = GlbCodec.decode(java.nio.ByteBuffer.wrap(resource("models/breadboard.glb"))).bin.size
}
