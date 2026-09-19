package com.htn.breadboardar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RectangleDetectorTest {

    private val detector = RectangleDetector()

    @Test
    fun `finds a bright rectangle and reports its corners`() {
        val luma = syntheticFrame()
        fillRect(luma, left = 100, top = 150, right = 400, bottom = 260, value = 220)

        val candidates = detector.detect(luma, WIDTH, HEIGHT, WIDTH)

        assertEquals(1, candidates.size)
        val xs = candidates[0].corners.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = candidates[0].corners.filterIndexed { index, _ -> index % 2 == 1 }
        // Detection runs downscaled, so corners land on the sampling grid rather
        // than the exact pixel. Tolerance covers one downscale step either way.
        assertEquals(100f, xs.min(), CORNER_TOLERANCE)
        assertEquals(400f, xs.max(), CORNER_TOLERANCE)
        assertEquals(150f, ys.min(), CORNER_TOLERANCE)
        assertEquals(260f, ys.max(), CORNER_TOLERANCE)
    }

    @Test
    fun `rejects a bright triangle because it is not rectangular`() {
        val luma = syntheticFrame()
        for (y in 120 until 330) {
            val halfWidth = (y - 120) / 2
            for (x in (250 - halfWidth) until (250 + halfWidth)) {
                luma[y * WIDTH + x] = 220.toByte()
            }
        }

        assertTrue(detector.detect(luma, WIDTH, HEIGHT, WIDTH).isEmpty())
    }

    @Test
    fun `ignores a bright region smaller than the size floor`() {
        val luma = syntheticFrame()
        fillRect(luma, left = 300, top = 200, right = 320, bottom = 215, value = 230)

        assertTrue(detector.detect(luma, WIDTH, HEIGHT, WIDTH).isEmpty())
    }

    @Test
    fun `ignores a rectangle clipped by the frame edge`() {
        val luma = syntheticFrame()
        // Runs off the left edge, so the detected corners would not be the object's.
        fillRect(luma, left = 0, top = 150, right = 300, bottom = 260, value = 220)

        assertTrue(detector.detect(luma, WIDTH, HEIGHT, WIDTH).isEmpty())
    }

    @Test
    fun `keeps the largest candidates when several rectangles are present`() {
        val luma = syntheticFrame()
        fillRect(luma, left = 40, top = 40, right = 260, bottom = 170, value = 210)
        fillRect(luma, left = 330, top = 260, right = 600, bottom = 430, value = 230)

        val candidates = detector.detect(luma, WIDTH, HEIGHT, WIDTH)

        assertEquals(2, candidates.size)
        assertTrue("candidates must be ordered largest first", candidates[0].areaPx > candidates[1].areaPx)
    }

    @Test
    fun `fits a trapezoid rather than its enclosing rectangle`() {
        // A rectangle seen at an angle projects to a trapezoid. The enclosing
        // rectangle necessarily overshoots it, which biases the corners and makes a
        // rigid pose solve impossible, so refinement must recover the real shape.
        val luma = syntheticFrame()
        val topLeft = 180f
        val topRight = 460f
        val bottomLeft = 120f
        val bottomRight = 520f
        val top = 140
        val bottom = 380
        for (y in top until bottom) {
            val t = (y - top).toFloat() / (bottom - top)
            val left = (topLeft + (bottomLeft - topLeft) * t).toInt()
            val right = (topRight + (bottomRight - topRight) * t).toInt()
            for (x in left until right) {
                luma[y * WIDTH + x] = 220.toByte()
            }
        }

        val candidates = detector.detect(luma, WIDTH, HEIGHT, WIDTH)
        assertEquals(1, candidates.size)

        // The trapezoid's own area, which is strictly less than any enclosing rectangle.
        val trueArea = 0.5f * ((topRight - topLeft) + (bottomRight - bottomLeft)) * (bottom - top)
        val fittedArea = shoelaceArea(candidates[0].corners)
        assertEquals(
            "fitted quad area should match the trapezoid, not its bounding box",
            trueArea,
            fittedArea,
            trueArea * 0.06f,
        )
    }

    @Test
    fun `refines a non grid aligned trapezoid at source resolution`() {
        // 1280 pixels triggers a 5-pixel detection step. The board deliberately
        // avoids that grid, so this catches a regression where the outline remains
        // quantised after the fast downsampled pass.
        val width = 1280
        val height = 720
        val luma = ByteArray(width * height) { 30.toByte() }
        val expected = floatArrayOf(
            183f, 149f,
            803f, 195f,
            744f, 499f,
            126f, 443f,
        )
        fillConvexQuad(luma, width, height, expected, 220)

        val candidates = detector.detect(luma, width, height, width)

        assertEquals(1, candidates.size)
        assertEquals(5, candidates[0].stepPx)
        val worstCornerDistance = expected.indices
            .filter { it % 2 == 0 }
            .maxOf { expectedIndex ->
                val expectedX = expected[expectedIndex]
                val expectedY = expected[expectedIndex + 1]
                (0 until 4).minOf { detectedCorner ->
                    val dx = candidates[0].corners[detectedCorner * 2] - expectedX
                    val dy = candidates[0].corners[detectedCorner * 2 + 1] - expectedY
                    kotlin.math.sqrt(dx * dx + dy * dy)
                }
            }
        assertTrue(
            "worst source-resolution corner error was $worstCornerDistance px",
            worstCornerDistance <= 3f,
        )
    }

    private fun shoelaceArea(quad: FloatArray): Float {
        var total = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            total += quad[i * 2] * quad[j * 2 + 1] - quad[j * 2] * quad[i * 2 + 1]
        }
        return kotlin.math.abs(total) / 2f
    }

    private fun syntheticFrame(): ByteArray = ByteArray(WIDTH * HEIGHT) { 30.toByte() }

    private fun fillRect(luma: ByteArray, left: Int, top: Int, right: Int, bottom: Int, value: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                luma[y * WIDTH + x] = value.toByte()
            }
        }
    }

    private fun fillConvexQuad(
        luma: ByteArray,
        width: Int,
        height: Int,
        quad: FloatArray,
        value: Int,
    ) {
        val minX = quad.filterIndexed { index, _ -> index % 2 == 0 }.min().toInt()
        val maxX = quad.filterIndexed { index, _ -> index % 2 == 0 }.max().toInt()
        val minY = quad.filterIndexed { index, _ -> index % 2 == 1 }.min().toInt()
        val maxY = quad.filterIndexed { index, _ -> index % 2 == 1 }.max().toInt()
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (pointIsInsideConvexQuad(x + 0.5f, y + 0.5f, quad)) {
                    luma[y * width + x] = value.toByte()
                }
            }
        }
    }

    private fun pointIsInsideConvexQuad(x: Float, y: Float, quad: FloatArray): Boolean {
        var sign = 0
        for (corner in 0 until 4) {
            val next = (corner + 1) % 4
            val ax = quad[corner * 2]
            val ay = quad[corner * 2 + 1]
            val bx = quad[next * 2]
            val by = quad[next * 2 + 1]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            if (kotlin.math.abs(cross) < 1e-4f) continue
            val current = if (cross > 0f) 1 else -1
            if (sign == 0) sign = current else if (current != sign) return false
        }
        return sign != 0
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val CORNER_TOLERANCE = 6f
    }
}
