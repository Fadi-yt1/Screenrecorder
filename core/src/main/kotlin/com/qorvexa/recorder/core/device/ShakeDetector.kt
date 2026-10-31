package com.qorvexa.recorder.core.device

import kotlin.math.sqrt

/**
 * Detects a deliberate shake: [requiredPeaks] acceleration peaks above [thresholdG]
 * within [windowMs]. Fed raw accelerometer values (m/s²).
 */
class ShakeDetector(
    private val thresholdG: Float = 2.5f,
    private val requiredPeaks: Int = 3,
    private val windowMs: Long = 1_200,
    private val cooldownMs: Long = 3_000,
) {
    private val peaks = ArrayDeque<Long>()
    private var lastTrigger = -cooldownMs
    private var aboveThreshold = false

    /** Returns true when a shake gesture is recognised. */
    fun onSample(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        val above = g > thresholdG
        if (above && !aboveThreshold) {
            peaks.addLast(timeMs)
        }
        aboveThreshold = above
        while (peaks.isNotEmpty() && timeMs - peaks.first() > windowMs) peaks.removeFirst()
        if (peaks.size >= requiredPeaks && timeMs - lastTrigger >= cooldownMs) {
            peaks.clear()
            lastTrigger = timeMs
            return true
        }
        return false
    }

    companion object {
        const val GRAVITY = 9.80665f
    }
}
