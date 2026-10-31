package com.qorvexa.recorder.core.recording

import com.qorvexa.recorder.core.model.RecordingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationClockTest {
    @Test fun startsAtZeroAndRemovesPauses() {
        val c = PresentationClock()
        c.start(1_000_000)
        assertEquals(0, c.map(0, 1_000_000))
        assertEquals(500_000, c.map(0, 1_500_000))
        c.pause(2_000_000)
        assertEquals(-1, c.map(0, 2_500_000)) // captured during pause: dropped
        c.resume(5_000_000)
        assertEquals(1_000_001, c.map(0, 5_000_000).coerceAtLeast(1_000_001))
        assertEquals(1_500_000, c.map(0, 5_500_000))
        assertEquals(1_500_000, c.elapsedUs(5_500_000))
    }

    @Test fun ptsStrictlyIncreasingPerTrack() {
        val c = PresentationClock()
        c.start(0)
        assertEquals(100, c.map(1, 100))
        assertEquals(101, c.map(1, 100))
        assertEquals(100, c.map(2, 100)) // other track independent
    }

    @Test fun sampleClockAdvancesBySamples() {
        val s = SampleClock(48_000)
        assertEquals(10_000, s.ptsFor(480, 10_000))
        assertEquals(20_000, s.ptsFor(480, 99_999))
    }

    @Test fun guardStopsOnLimits() {
        val g = RecordingGuard(RecordingSettings(maxDurationMin = 1, maxFileSizeMb = 10, lowStorageProtection = true, minFreeStorageMb = 500, batteryProtection = true, batteryStopPercent = 10))
        val ok = GuardSnapshot(1000, 1000, 10L * 1024 * 1024 * 1024, 80, false)
        assertNull(g.check(ok))
        assertEquals(StopReason.MAX_DURATION, g.check(ok.copy(elapsedMs = 60_000)))
        assertEquals(StopReason.MAX_FILE_SIZE, g.check(ok.copy(bytesWritten = 10 * RecordingGuard.MB)))
        assertEquals(StopReason.LOW_STORAGE, g.check(ok.copy(freeBytes = 100 * RecordingGuard.MB)))
        assertEquals(StopReason.LOW_BATTERY, g.check(ok.copy(batteryPercent = 9)))
        assertNull(g.check(ok.copy(batteryPercent = 9, charging = true)))
        assertTrue(g.canStart(10L * 1024 * 1024 * 1024, 1_000_000))
    }
}
