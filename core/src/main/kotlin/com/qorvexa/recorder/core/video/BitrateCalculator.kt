package com.qorvexa.recorder.core.video

import com.qorvexa.recorder.core.model.VideoCodec
import kotlin.math.roundToInt

/**
 * Recommends a bitrate from pixel count, frame rate and codec efficiency.
 *
 * Uses a bits-per-pixel model (0.1 bpp for H.264 at 30 FPS, typical for screen content)
 * with sub-linear scaling for higher frame rates, because consecutive frames
 * are more similar at high FPS.
 */
object BitrateCalculator {
    private const val H264_BPP = 0.10

    fun codecEfficiency(codec: VideoCodec): Double = when (codec) {
        VideoCodec.H264 -> 1.0
        VideoCodec.HEVC -> 0.65
        VideoCodec.AV1 -> 0.55
    }

    /** Returns a recommended bitrate in bits per second, rounded to 0.5 Mbps, clamped to [1, 50] Mbps. */
    fun recommend(width: Int, height: Int, fps: Int, codec: VideoCodec): Int {
        require(width > 0 && height > 0) { "Size must be positive" }
        val safeFps = fps.coerceAtLeast(1)
        val fpsFactor = Math.pow(safeFps / 30.0, 0.75)
        val raw = width.toDouble() * height * 30 * H264_BPP * fpsFactor * codecEfficiency(codec)
        val halfMbps = (raw / 500_000).roundToInt().coerceAtLeast(2)
        return (halfMbps * 500_000).coerceIn(MIN_BPS, MAX_BPS)
    }

    /** Estimated output bytes per second, including audio. */
    fun bytesPerSecond(videoBps: Int, audioBps: Int): Long = (videoBps.toLong() + audioBps) / 8

    /** Estimated file size for [seconds] of recording, plus ~1% container overhead. */
    fun estimateBytes(videoBps: Int, audioBps: Int, seconds: Long): Long =
        (bytesPerSecond(videoBps, audioBps) * seconds * 101) / 100

    const val MIN_BPS = 1_000_000
    const val MAX_BPS = 50_000_000
}
