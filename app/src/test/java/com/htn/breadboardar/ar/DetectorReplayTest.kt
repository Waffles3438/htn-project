package com.htn.breadboardar.ar

import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class DetectorReplayTest {
    @Test fun `repeated tracking frames preserve measured corners`() {
        val image = ImageIO.read(javaClass.getResource("/fixtures/calibration-world-map-rejected.png"))
        val width = image.width / 2
        val height = (image.height * 0.83).toInt() / 2
        val luma = ByteArray(width * height) { i ->
            val rgb = image.getRGB(i % width * 2, i / width * 2)
            (0.299 * (rgb shr 16 and 255) + 0.587 * (rgb shr 8 and 255) + 0.114 * (rgb and 255)).toInt().toByte()
        }
        val detector = RectangleDetector()
        val expected = detector.detect(luma, width, height, width, lenient = true)
        assertTrue(expected.any { it.hasMeasuredCorners })
        repeat(8) { detector.detect(luma, width, height, width, lenient = true) }
        val samples = LongArray(21) {
            val start = System.nanoTime()
            val actual = detector.detect(luma, width, height, width, lenient = true)
            val duration = System.nanoTime() - start
            assertEquals(expected.size, actual.size)
            for (i in actual.indices) {
                assertArrayEquals(expected[i].corners, actual[i].corners, 0f)
                assertEquals(expected[i].hasMeasuredCorners, actual[i].hasMeasuredCorners)
            }
            duration
        }.sorted()
        println("Detector replay median ms=${samples[10] / 1e6}; p95 ms=${samples[19] / 1e6}")
    }
}
