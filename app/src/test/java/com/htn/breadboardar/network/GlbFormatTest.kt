package com.htn.breadboardar.network

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GlbFormatTest {

    @Test
    fun `accepts a minimal glb version two JSON chunk`() {
        val glb = glb(version = 2, declaredSize = 20)

        GlbFormat.requireValid(glb)
    }

    @Test
    fun `rejects a non glb response`() {
        val html = "<html>not a model</html>".encodeToByteArray()

        assertRejected(html, "missing glTF header")
    }

    @Test
    fun `rejects an incorrect declared length`() {
        val glb = glb(version = 2, declaredSize = 24)

        assertRejected(glb, "length")
    }

    @Test
    fun `rejects glb version one`() {
        val glb = glb(version = 1, declaredSize = 20)

        assertRejected(glb, "2.0")
    }

    @Test
    fun `rejects a glb with no JSON first chunk`() {
        val glb = glb(version = 2, declaredSize = 20).apply {
            this[16] = 'B'.code.toByte()
            this[17] = 'I'.code.toByte()
            this[18] = 'N'.code.toByte()
            this[19] = 0
        }

        assertRejected(glb, "first GLB chunk")
    }

    private fun glb(version: Int, declaredSize: Int): ByteArray = byteArrayOf(
        'g'.code.toByte(), 'l'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte(),
        version.toByte(), (version ushr 8).toByte(), (version ushr 16).toByte(), (version ushr 24).toByte(),
        declaredSize.toByte(), (declaredSize ushr 8).toByte(),
        (declaredSize ushr 16).toByte(), (declaredSize ushr 24).toByte(),
        0, 0, 0, 0, // Empty JSON chunk payload.
        'J'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(),
    )

    private fun assertRejected(bytes: ByteArray, expectedText: String) {
        try {
            GlbFormat.requireValid(bytes)
            fail("Expected GLB validation to fail")
        } catch (error: IOException) {
            assertEquals(true, error.message?.contains(expectedText) == true)
        }
    }
}
