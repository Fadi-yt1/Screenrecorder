package com.qorvexa.recorder.core.recording

/**
 * Shared timeline for audio and video samples.
 *
 * Encoders stamp buffers with system monotonic time. This clock rebases them so the
 * file starts at 0, removes time spent paused, and guarantees strictly increasing
 * timestamps per track (MediaMuxer rejects out-of-order samples).
 */
class PresentationClock {
    private var startUs = -1L
    private var pausedTotalUs = 0L
    private var pauseStartedUs = -1L
    private val lastPts = HashMap<Int, Long>()

    @get:Synchronized
    val isPaused: Boolean get() = pauseStartedUs >= 0

    @Synchronized
    fun start(nowUs: Long) {
        if (startUs < 0) startUs = nowUs
    }

    @Synchronized
    fun pause(nowUs: Long) {
        if (pauseStartedUs < 0) pauseStartedUs = nowUs
    }

    @Synchronized
    fun resume(nowUs: Long) {
        if (pauseStartedUs >= 0) {
            pausedTotalUs += (nowUs - pauseStartedUs).coerceAtLeast(0)
            pauseStartedUs = -1
        }
    }

    /** Recording time excluding pauses. */
    @Synchronized
    fun elapsedUs(nowUs: Long): Long {
        if (startUs < 0) return 0
        val pausedNow = if (pauseStartedUs >= 0) nowUs - pauseStartedUs else 0
        return (nowUs - startUs - pausedTotalUs - pausedNow).coerceAtLeast(0)
    }

    /**
     * Maps an encoder timestamp to a file timestamp for [track].
     * Returns -1 when the sample should be dropped (captured while paused or before start).
     */
    @Synchronized
    fun map(track: Int, rawUs: Long): Long {
        if (startUs < 0) start(rawUs)
        if (pauseStartedUs in 0..rawUs) return -1
        var pts = rawUs - startUs - pausedTotalUs
        if (pts < 0) return -1
        val prev = lastPts[track]
        if (prev != null && pts <= prev) pts = prev + 1
        lastPts[track] = pts
        return pts
    }
}

/** Builds monotonically increasing audio timestamps from a running sample count. */
class SampleClock(private val sampleRate: Int) {
    private var framesWritten = 0L
    private var baseUs = -1L

    fun ptsFor(frames: Int, nowUs: Long): Long {
        if (baseUs < 0) baseUs = nowUs
        val pts = baseUs + framesWritten * 1_000_000L / sampleRate
        framesWritten += frames
        return pts
    }

    /** Re-anchors after a pause so sample-derived time keeps tracking wall time. */
    fun reanchor(nowUs: Long) {
        baseUs = nowUs
        framesWritten = 0
    }
}
