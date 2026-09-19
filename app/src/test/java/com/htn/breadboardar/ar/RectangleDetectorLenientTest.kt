package com.htn.breadboardar.ar

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lenient mode exists so a board already being tracked survives motion blur. These
 * cases pin down that it accepts a degraded shape the scanning gates reject, without
 * becoming so permissive that obvious non-rectangles get through.
 */
class RectangleDetectorLenientTest {

    private val detector = RectangleDetector()

    @Test
    fun `accepts a ragged blob at least as readily as strict mode`() {
        val luma = frame()
        // A rectangle with chunks bitten out of its edges, standing in for the soft,
        // broken outline that fast movement produces.
        fillRect(luma, 150, 160, 430, 280, 220)
        for (x in 190 until 240) {
            for (y in 160 until 186) luma[y * WIDTH + x] = 30
        }
        for (x in 330 until 390) {
            for (y in 256 until 280) luma[y * WIDTH + x] = 30
        }

        val strict = detector.detect(luma, WIDTH, HEIGHT, WIDTH, lenient = false)
        val lenient = detector.detect(luma, WIDTH, HEIGHT, WIDTH, lenient = true)

        assertTrue("lenient mode should hold on to the degraded board", lenient.isNotEmpty())
        assertTrue("strict mode should be at least as fussy", strict.size <= lenient.size)
    }

    @Test
    fun `accepts a smaller blob in lenient mode than strict mode allows`() {
        val luma = frame()
        // Sized to fall between the strict and lenient area floors, which is what a
        // board looks like as the phone pulls back from it. Detection downscales by 2
        // here, so 80x25 full-res is 480 downscaled pixels: above the lenient floor of
        // 307, below the strict floor of 768.
        fillRect(luma, 270, 215, 350, 240, 225)

        val strict = detector.detect(luma, WIDTH, HEIGHT, WIDTH, lenient = false)
        val lenient = detector.detect(luma, WIDTH, HEIGHT, WIDTH, lenient = true)

        assertTrue("strict mode rejects it on size", strict.isEmpty())
        assertTrue("lenient mode keeps it", lenient.isNotEmpty())
    }

    @Test
    fun `still rejects a triangle in lenient mode`() {
        val luma = frame()
        for (y in 120 until 340) {
            val half = (y - 120) / 2
            for (x in (250 - half) until (250 + half)) luma[y * WIDTH + x] = 220.toByte()
        }

        assertTrue(detector.detect(luma, WIDTH, HEIGHT, WIDTH, lenient = true).isEmpty())
    }

    private fun frame(): ByteArray = ByteArray(WIDTH * HEIGHT) { 30 }

    private fun fillRect(luma: ByteArray, left: Int, top: Int, right: Int, bottom: Int, value: Int) {
        for (y in top until bottom) {
            for (x in left until right) luma[y * WIDTH + x] = value.toByte()
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}
