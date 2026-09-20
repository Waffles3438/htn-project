package com.htn.breadboardar.ar

import kotlin.math.hypot

/** Independently checks an AR anchor against image edges, never moves the anchor. */
internal class AnchorAlignmentGate {
    var verified = false
        private set
    var failed = false
        private set
    private var latestComparisonAgreed = false
    val canRender: Boolean get() = verified && !failed && latestComparisonAgreed
    private var agreeingSince = 0L
    private var disagreeingSince = 0L
    private var agreements = 0
    private var disagreements = 0
    private var lastTimestamp = 0L

    fun reset() {
        verified = false
        failed = false
        latestComparisonAgreed = false
        agreeingSince = 0L
        disagreeingSince = 0L
        agreements = 0
        disagreements = 0
        lastTimestamp = 0L
    }

    fun observe(expected: FloatArray, measured: FloatArray?, timestampNs: Long) {
        if (failed || timestampNs <= lastTimestamp) return
        val gap = timestampNs - lastTimestamp
        lastTimestamp = timestampNs
        // Missing a contour during an orbit is not evidence that a verified anchor
        // moved. During startup, however, absence cannot count as verification.
        if (measured == null) {
            agreements = 0
            agreeingSince = 0L
            disagreements = 0
            return
        }
        if (gap > 300_000_000L) {
            agreements = 0
            disagreements = 0
        }
        if (agrees(expected, measured)) {
            latestComparisonAgreed = true
            if (agreements == 0) agreeingSince = timestampNs
            agreements++
            disagreements = 0
            if (agreements >= 6 && timestampNs - agreeingSince >= 500_000_000L) verified = true
        } else {
            latestComparisonAgreed = false
            agreements = 0
            if (disagreements == 0) disagreeingSince = timestampNs
            disagreements++
            if (verified && disagreements >= 3 && timestampNs - disagreeingSince >= 120_000_000L) failed = true
        }
    }

    companion object {
        fun matchingOutline(expected: FloatArray, candidates: List<FloatArray>): FloatArray? {
            if (expected.size != 8) return null
            fun area(q: FloatArray) = kotlin.math.abs((0..3).sumOf { i ->
                val j = (i + 1) % 4
                q[i * 2].toDouble() * q[j * 2 + 1] - q[j * 2].toDouble() * q[i * 2 + 1]
            }) / 2.0
            val expectedArea = area(expected)
            val radius = kotlin.math.sqrt(expectedArea).toFloat() * 0.5f
            val cx = (0..3).sumOf { expected[it * 2].toDouble() } / 4
            val cy = (0..3).sumOf { expected[it * 2 + 1].toDouble() } / 4
            return candidates.filter { q ->
                q.size == 8 && q.all { it.isFinite() } && area(q) / expectedArea in 0.6..1.6 &&
                    hypot((0..3).sumOf { q[it * 2].toDouble() } / 4 - cx,
                        (0..3).sumOf { q[it * 2 + 1].toDouble() } / 4 - cy) <= radius
            }.minByOrNull { cornerError(expected, it) }
        }

        fun cornerError(expected: FloatArray, measured: FloatArray): Float {
            if (expected.size != 8 || measured.size != 8 ||
                !expected.all { it.isFinite() } || !measured.all { it.isFinite() }) return Float.POSITIVE_INFINITY
            // Contours can start at any vertex. Never reverse winding or relabel
            // the actual calibrated board; this permutation is for comparison only.
            return (0..3).minOf { shift -> (0..3).maxOf { i ->
                val j = (i + shift) % 4
                hypot(expected[i * 2] - measured[j * 2], expected[i * 2 + 1] - measured[j * 2 + 1])
            } }
        }

        private fun agrees(expected: FloatArray, measured: FloatArray): Boolean {
            if (expected.size != 8) return false
            val shortSide = (0..3).minOf { i ->
                val j = (i + 1) % 4
                hypot(expected[i * 2] - expected[j * 2], expected[i * 2 + 1] - expected[j * 2 + 1])
            }
            return cornerError(expected, measured) <= maxOf(4f, shortSide * 0.08f)
        }
    }
}
