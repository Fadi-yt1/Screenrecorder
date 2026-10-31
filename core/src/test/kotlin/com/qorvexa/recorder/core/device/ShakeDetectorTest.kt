package com.qorvexa.recorder.core.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    @Test fun threePeaksTrigger() {
        val d = ShakeDetector()
        var t = 0L
        var fired = false
        repeat(3) {
            fired = d.onSample(0f, 0f, 35f, t) || fired; t += 50
            fired = d.onSample(0f, 0f, 9.8f, t) || fired; t += 50
        }
        assertTrue(fired)
    }

    @Test fun restingDoesNotTrigger() {
        val d = ShakeDetector()
        var fired = false
        for (i in 0 until 100) fired = d.onSample(0f, 0f, 9.8f, i * 20L) || fired
        assertFalse(fired)
    }
}
