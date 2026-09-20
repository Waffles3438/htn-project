package com.htn.breadboardar.ar

import javax.imageio.ImageIO
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Test

class RecordingStabilityTest {
    @Test
    fun `recorded tilted board keeps measured perspective over a brighter fallback box`() {
        val image = ImageIO.read(javaClass.getResource("/fixtures/recording-uncertain-edges.png"))
        val luma = ByteArray(image.width * image.height) { pixel ->
            val rgb = image.getRGB(pixel % image.width, pixel / image.width)
            val r = rgb shr 16 and 255
            val g = rgb shr 8 and 255
            val b = rgb and 255
            // Screen recordings include the app's yellow line over the actual
            // edge. Removing it leaves missing evidence, unlike raw camera input.
            // This checks uncertain-edge handling, not sensor pose recovery.
            if (r > b + 70 && g > b + 45) 40.toByte()
            else (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
        }
        val candidate = RectangleDetector().detect(luma, image.width, image.height,
            image.width, lenient = true).firstOrNull()
        assertNotNull("A selectable enclosing box should still be available", candidate)
        assertTrue("Keep measured edges when the brighter pass only finds a box", candidate!!.hasMeasuredCorners)
        val q = candidate.corners
        val sides = FloatArray(4) { i ->
            val j = (i + 1) % 4
            hypot(q[j * 2] - q[i * 2], q[j * 2 + 1] - q[i * 2 + 1])
        }
        val taper = maxOf(abs(sides[0] - sides[2]), abs(sides[1] - sides[3]))
        assertTrue("Recorded outline must retain visible perspective: ${q.contentToString()}", taper > 8f)
    }
}
