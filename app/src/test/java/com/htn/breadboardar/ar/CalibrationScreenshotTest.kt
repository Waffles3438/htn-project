package com.htn.breadboardar.ar

import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CalibrationScreenshotTest {
    @Test
    fun `board edges stay on the plastic instead of the carpet`() {
        val image = ImageIO.read(javaClass.getResource("/fixtures/calibration-board-on-carpet.png"))
        // Camera portion only, downsampled in memory. UI controls are not detector input.
        val width = image.width / 2
        val height = 1990 / 2
        val luma = ByteArray(width * height) { index ->
            val rgb = image.getRGB(index % width * 2, index / width * 2)
            val r = rgb shr 16 and 255
            val g = rgb shr 8 and 255
            val b = rgb and 255
            // Remove the app's cyan annotation, which is not in the camera Y plane.
            if (g > r + 25 && b > r + 25) 40.toByte()
            else (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
        }
        val expected = floatArrayOf(41f, 841f, 981f, 821f, 992f, 1130f, 45f, 1152f)
            .map { it / 2f }.toFloatArray()
        val candidates = RectangleDetector().detect(luma, width, height, width)
        val best = candidates.minByOrNull { candidate ->
            (0 until 4).sumOf { i ->
                (0 until 4).minOf { j ->
                    hypot((candidate.corners[j * 2] - expected[i * 2]).toDouble(),
                        (candidate.corners[j * 2 + 1] - expected[i * 2 + 1]).toDouble())
                }
            }
        }
        assertTrue("No board candidate", best != null)
        assertTrue("Calibration needs measured edges", best!!.hasMeasuredCorners)
        val worst = (0 until 4).maxOf { i ->
            (0 until 4).minOf { j ->
                hypot((best!!.corners[j * 2] - expected[i * 2]).toDouble(),
                    (best.corners[j * 2 + 1] - expected[i * 2 + 1]).toDouble())
            }
        }
        assertTrue("Corner miss $worst px: ${best!!.corners.contentToString()}", worst < 14.0)
        // Approximate intrinsics: a screenshot cannot supply the actual camera
        // calibration. This checks the formerly rejected near-head-on shape only;
        // exact poses and tilted views are covered with independent projections.
        val fit = BoardGeometry.fit(best.corners, floatArrayOf(600f, 600f),
            floatArrayOf(width / 2f, height / 2f))!!
        assertTrue("Fit miss ${fit.estimate.reprojectionErrorPx}", fit.estimate.reprojectionErrorPx < 8f)
    }
}
