package com.htn.breadboardar.circuit

import com.htn.breadboardar.render.CircuitGlbBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class CircuitGlbTest {
    private fun bytes(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }
    private fun build(json: String): ByteBuffer = CircuitGlbBuilder(BoardGeometry(String(bytes("board-map.json"))), bytes("models/breadboard.glb"), String(bytes("models/components.json"))).build(CircuitDefinition.parse(json))
    private fun doc(buffer: ByteBuffer): JSONObject {
        buffer.position(12); val size=buffer.int; buffer.int
        return JSONObject(String(ByteArray(size).also { buffer.get(it) }).trim())
    }
    @Test fun allCircuitsIncludeTheirPartsAndWiresWithRealMeshGeometry() {
        for (name in listOf("led","button_led","arduino_led")) {
            val json=String(bytes("$name.placement.json")); val circuit=CircuitDefinition.parse(json)
            val buffer=build(json); val saved=ByteArray(buffer.remaining()).also { buffer.get(it) }; buffer.rewind()
            val root=doc(buffer); val nodes=root.getJSONArray("nodes")
            val names=(0 until nodes.length()).map { nodes.getJSONObject(it).optString("name") }
            circuit.components.filter { it.type != "power_supply" }.forEach { assertTrue("Missing ${it.id}", names.contains(it.id)) }
            circuit.jumperWires.forEach { w -> assertEquals(20, names.count { it.startsWith(w.id+":") }) }
            val meshes=root.getJSONArray("meshes")
            assertTrue((0 until meshes.length()).any { meshes.getJSONObject(it).optString("name") == "Unity led" })
            assertEquals(circuit.sessionId,root.getJSONObject("extras").getString("sessionId"))
            val out=File("build/circuit-previews/$name.glb");out.parentFile.mkdirs();out.writeBytes(saved)
        }
    }
    @Test fun aChangedPlacementMovesTheMeshInsteadOfReturningAStaticDemo() {
        val fixture=JSONObject(String(bytes("led.placement.json")))
        val components=fixture.getJSONArray("components")
        val led=(0 until components.length()).map { components.getJSONObject(it) }.first { it.getString("type")=="led" }
        fun position(root: JSONObject): Double {
            val nodes=root.getJSONArray("nodes"); val node=(0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.optString("name")==led.getString("id") }
            return node.getJSONArray("translation").getDouble(0)
        }
        val before=position(doc(build(fixture.toString())))
        led.getJSONObject("mount").getJSONObject("terminals").put("anode","BB1:A40").put("cathode","BB1:A41")
        val after=position(doc(build(fixture.toString())))
        assertTrue(after-before > .04)
    }
    @Test fun rejectedCircuitCannotProduceAnEmptyBoardFallback() {
        val invalid=JSONObject(String(bytes("button_led.placement.json")))
        invalid.getJSONObject("breadboard").put("holeMapVersion","unknown")
        assertThrows(CircuitParseException::class.java) { build(invalid.toString()) }
    }
}
