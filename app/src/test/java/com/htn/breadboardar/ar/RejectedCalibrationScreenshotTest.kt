package com.htn.breadboardar.ar

import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RejectedCalibrationScreenshotTest {
    @Test
    fun `visible plastic edges in rejected calibration screenshot produce a usable fit`() {
        checkImage("calibration-edges-rejected.png", floatArrayOf(225f, 1142f, 970f, 1120f, 977f, 1375f, 229f, 1387f))
    }

    @Test
    fun `board rejected by world tracking still has usable visual calibration`() {
        checkImage("calibration-world-map-rejected.png", floatArrayOf(54f, 843f, 1018f, 807f, 1033f, 1127f, 60f, 1165f))
    }

    private fun checkImage(fixture: String, expectedCorners: FloatArray) {
        val image = ImageIO.read(javaClass.getResource("/fixtures/$fixture"))
        for (divisor in listOf(1, 2)) {
            val width = image.width / divisor
            val height = (image.height * 0.83).toInt() / divisor
            val luma = ByteArray(width * height) { i ->
                val rgb = image.getRGB(i % width * divisor, i / width * divisor)
                val r = rgb shr 16 and 255
                val g = rgb shr 8 and 255
                val b = rgb and 255
                if (g > r + 25 && b > r + 25) 40.toByte()
                else (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
            }
            val expected = expectedCorners.map { it / divisor }.toFloatArray()
            val candidates = RectangleDetector().detect(luma, width, height, width)
            val board = candidates.minByOrNull { candidate ->
                (0..3).sumOf { i -> (0..3).minOf { j ->
                    hypot((candidate.corners[j * 2] - expected[i * 2]).toDouble(),
                        (candidate.corners[j * 2 + 1] - expected[i * 2 + 1]).toDouble())
                } }
            }
            assertTrue("No candidate at divisor $divisor", board != null)
            val fit = BoardGeometry.fit(board!!.corners, floatArrayOf(1200f / divisor, 1200f / divisor),
                floatArrayOf(width / 2f, height / 2f))
            assertTrue("No measured corners: ${board.corners.contentToString()}", board.hasMeasuredCorners)
            val worst = (0..3).maxOf { i -> (0..3).minOf { j ->
                hypot((board.corners[j * 2] - expected[i * 2]).toDouble(),
                    (board.corners[j * 2 + 1] - expected[i * 2 + 1]).toDouble())
            } }
            assertTrue("Edges must stay on the plastic, miss=$worst", worst < 10.0 / divisor)
            // Screenshot intrinsics are approximate. Live-device calibration is
            // still required; this guards measured edges and pose-fit feasibility.
            assertTrue("Pose fit rejected: $fit", fit != null && fit.estimate.reprojectionErrorPx < 8f)
        }
    }
}
