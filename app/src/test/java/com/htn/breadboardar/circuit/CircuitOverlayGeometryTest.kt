package com.htn.breadboardar.circuit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AR overlay is only trustworthy if its board-plane points really come from the
 * versioned board map: a mirrored or shifted mapping would draw components on the wrong
 * holes of the scanned photo while still looking plausible.
 */
class CircuitOverlayGeometryTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val board get() = BoardGeometry(resource("board-map.json"))

    @Test fun buildsConsistentFiniteFeaturesForEveryFixture() {
        listOf("led", "button_led", "arduino_led").forEach { name ->
            val geometry = CircuitOverlayGeometry.of(
                CircuitDefinition.parse(resource("$name.placement.json")),
                board,
            )
            assertTrue("no overlay features for $name", geometry.specs.isNotEmpty())
            assertEquals(
                "point array does not match the specs for $name",
                geometry.specs.sumOf { it.pointCount } * 2,
                geometry.points.size,
            )
            geometry.points.forEach { point -> assertTrue("non-finite overlay point", point.isFinite()) }
            geometry.specs.forEach { spec ->
                assertTrue(spec.pointCount > 0)
                assertTrue(spec.pointStart + spec.pointCount <= geometry.points.size / 2)
            }
        }
    }

    @Test fun mapsRowsToTheLengthAxisAndLettersToTheWidthAxis() {
        val geometry = CircuitOverlayGeometry.of(
            CircuitDefinition.parse(resource("button_led.placement.json")),
            board,
        )
        val a1 = geometry.specs.first { it.label == "A1" }
        val a1u = geometry.points[a1.pointStart * 2]
        val a1v = geometry.points[a1.pointStart * 2 + 1]
        // A1 sits at row 1 (u = 0) nudged toward the board centre, letter axis shifted
        // so the left rail is v = 0; A1's map x is exactly the left rail.
        assertEquals(0.007f, a1u, 0.0005f)
        assertEquals(-board.minX, a1v, 0.0005f)

        val a63 = geometry.specs.first { it.label == "A63" }
        val a63u = geometry.points[a63.pointStart * 2]
        assertEquals(0.15748f - 0.007f, a63u, 0.0005f)
    }

    @Test fun everyWireEndsOnABoardHole() {
        val geometry = CircuitOverlayGeometry.of(
            CircuitDefinition.parse(resource("button_led.placement.json")),
            board,
        )
        val wires = geometry.specs.filter { !it.closed && it.pointCount == 3 && it.label == null }
        assertTrue("button_led should draw jumper wires", wires.isNotEmpty())
        val rowRange = 0f..0.15748f
        val letterRange = -board.minX..(0.0381f - board.minX)
        wires.forEach { wire ->
            listOf(0, 2).forEach { index ->
                val u = geometry.points[(wire.pointStart + index) * 2]
                val v = geometry.points[(wire.pointStart + index) * 2 + 1]
                assertTrue("wire endpoint u=$u outside the hole rows", u in rowRange)
                assertTrue("wire endpoint v=$v outside the hole columns", v in letterRange)
            }
        }
    }
}
