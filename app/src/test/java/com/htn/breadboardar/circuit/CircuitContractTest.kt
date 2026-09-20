package com.htn.breadboardar.circuit

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CircuitContractTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val board get() = BoardGeometry(resource("board-map.json"))
    private fun modified(change: (JSONObject) -> Unit): String = JSONObject(resource("led.placement.json")).also(change).toString()
    private fun rejected(json: String) {
        try { board.validate(CircuitDefinition.parse(json)); fail("Invalid circuit was accepted") }
        catch (_: CircuitParseException) { }
    }
    @Test fun allSupportedFixturesResolveEveryEndpoint() {
        listOf("led", "button_led", "arduino_led").forEach { board.validate(CircuitDefinition.parse(resource("$it.placement.json"))) }
    }
    @Test fun requiresPositiveValidation() {
        rejected(modified { it.remove("validation") })
        rejected(modified { it.getJSONObject("validation").put("valid", false) })
    }
    @Test fun rejectsOtherContractVersions() { rejected(modified { it.put("version", 2) }) }
    @Test fun rejectsMismatchedBoardMap() { rejected(modified { it.getJSONObject("breadboard").put("holeMapVersion", "old-map") }) }
    @Test fun rejectsUnknownWireEndpoint() { rejected(modified { it.getJSONArray("jumperWires").getJSONObject(0).put("to", "BB1:A64") }) }
    @Test fun rejectsMissingAndDuplicatePins() {
        rejected(modified { it.getJSONArray("components").getJSONObject(0).getJSONObject("mount").getJSONObject("terminals").remove("anode") })
        rejected(modified { it.getJSONArray("components").getJSONObject(0).getJSONObject("mount").getJSONObject("terminals").put("anode", "BB1:A16") })
    }
    @Test fun rejectsNullComponents() { rejected(modified { it.put("components", JSONObject.NULL) }) }
    @Test fun resolvesRailExtremesButNotSegmentNets() {
        assertNotNull(board.hole("BB1:RAIL:L:+:A:1"))
        assertNotNull(board.hole("BB1:RAIL:R:-:B:25"))
        assertNull(board.hole("BB1:RAIL:R:-:B"))
        assertNull(board.hole("BB1:RAIL:R:-:B:26"))
        assertEquals(-.01016f, board.hole("BB1:RAIL:L:+:A:1")!!.x, .000001f)
    }
}
