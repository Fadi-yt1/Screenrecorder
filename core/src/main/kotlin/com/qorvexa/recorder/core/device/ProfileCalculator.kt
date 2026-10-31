package com.qorvexa.recorder.core.device

import com.qorvexa.recorder.core.model.AudioBitrate
import com.qorvexa.recorder.core.model.BitrateOption
import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.FpsOption
import com.qorvexa.recorder.core.model.GamingPreset
import com.qorvexa.recorder.core.model.OptimizationMode
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.core.model.Tier
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.video.VideoConfigResolver

data class DeviceProfile(
    val tier: Tier,
    val score: Int,
    val reasons: List<String>,
)

/**
 * Scores the device from measured hardware (never from a model-name list) and
 * produces recommended settings for each optimisation mode.
 */
object ProfileCalculator {

    fun profile(caps: DeviceCapabilities): DeviceProfile {
        var score = 0
        val reasons = mutableListOf<String>()

        // RAM: 0..30
        val ram = caps.totalRamMb
        score += when {
            ram >= 8_000 -> 30
            ram >= 6_000 -> 24
            ram >= 4_000 -> 16
            ram >= 3_000 -> 10
            ram >= 2_000 -> 5
            else -> 0
        }
        reasons += "RAM %.1f GB".format(java.util.Locale.US, ram / 1024.0)

        // CPU: 0..25
        score += when {
            caps.cpuCores >= 8 && caps.cpuMaxFreqMhz >= 2800 -> 25
            caps.cpuCores >= 8 && caps.cpuMaxFreqMhz >= 2200 -> 18
            caps.cpuCores >= 8 -> 12
            caps.cpuCores >= 6 -> 9
            caps.cpuCores >= 4 -> 5
            else -> 0
        }
        reasons += "${caps.cpuCores} CPU cores" + if (caps.cpuMaxFreqMhz > 0) " @ ${caps.cpuMaxFreqMhz} MHz" else ""

        // Encoders: 0..30
        val hwAvc = caps.encodersFor(VideoCodec.H264).firstOrNull { it.hardware }
        val hwHevc = caps.hasHardware(VideoCodec.HEVC)
        val hwAv1 = caps.hasHardware(VideoCodec.AV1)
        if (hwAvc != null) {
            score += 8
            if (hwAvc.maxFpsAt1080 >= 60) score += 4
            if (hwAvc.maxFpsAt1080 >= 120) score += 4
            if (hwAvc.maxFpsAt2160 >= 30) score += 4
        } else reasons += "No hardware H.264 encoder"
        if (hwHevc) score += 5
        if (hwAv1) score += 5

        // Display: 0..15
        if (caps.maxRefreshRate >= 90f) score += 8
        if (caps.maxRefreshRate >= 120f) score += 3
        if (caps.displaySize.shortSide >= 1080) score += 4

        if (caps.lowRamDevice) {
            score = minOf(score, 30)
            reasons += "System reports a low-RAM device"
        }
        if (caps.apiLevel in 1..23) score = minOf(score, 45)

        val tier = when {
            score >= 78 -> Tier.HIGH
            score >= 40 -> Tier.MID
            else -> Tier.LOW
        }
        return DeviceProfile(tier, score.coerceIn(0, 100), reasons)
    }

    /**
     * Returns [base] with video/engine fields set for [mode]. Every value is then
     * validated against hardware by [VideoConfigResolver] at record time.
     */
    fun recommend(mode: OptimizationMode, caps: DeviceCapabilities, base: RecordingSettings): RecordingSettings {
        val tier = profile(caps).tier
        val effectiveMode = if (mode == OptimizationMode.AUTOMATIC) {
            when (tier) {
                Tier.LOW -> OptimizationMode.BATTERY_SAVER
                Tier.MID -> OptimizationMode.BALANCED
                Tier.HIGH -> OptimizationMode.HIGH_PERFORMANCE
            }
        } else mode

        val bestCodec = when {
            caps.hasHardware(VideoCodec.HEVC) && (caps.apiLevel == 0 || caps.apiLevel >= 24) -> VideoCodec.HEVC
            else -> VideoCodec.H264
        }
        val display = caps.displaySize
        fun clampRes(want: ResolutionPreset): ResolutionPreset {
            val ok = ResolutionPreset.fixed.filter { it.shortSide <= want.shortSide && VideoConfigResolver.sizeForPreset(it, display) != null }
            return ok.lastOrNull() ?: ResolutionPreset.ORIGINAL
        }
        fun clampFps(want: Int, res: ResolutionPreset, codec: VideoCodec): FpsOption {
            val size = VideoConfigResolver.sizeForPreset(res, display) ?: display
            val max = if (caps.encoders.isEmpty()) caps.maxRefreshRate.toInt() else VideoConfigResolver.maxSupportedFps(size, caps, codec)
            val target = minOf(want, max)
            return FpsOption.entries.filter { it != FpsOption.AUTO && it.fps <= target }.maxByOrNull { it.fps } ?: FpsOption.FPS_30
        }

        return when (effectiveMode) {
            OptimizationMode.BATTERY_SAVER -> {
                val res = clampRes(ResolutionPreset.P720)
                base.copy(
                    resolution = res, fps = clampFps(30, res, VideoCodec.H264), codec = VideoCodec.H264,
                    bitrate = BitrateOption.MBPS_4, engine = EngineType.BASIC, audioBitrate = AudioBitrate.K128,
                    optimization = mode,
                )
            }
            OptimizationMode.BALANCED -> {
                val res = clampRes(ResolutionPreset.P1080)
                val fps = if (tier == Tier.LOW) 30 else 60
                base.copy(
                    resolution = res, fps = clampFps(fps, res, VideoCodec.H264), codec = VideoCodec.H264,
                    bitrate = BitrateOption.RECOMMENDED, engine = EngineType.ADVANCED, audioBitrate = AudioBitrate.K128,
                    optimization = mode,
                )
            }
            OptimizationMode.HIGH_PERFORMANCE -> {
                val res = clampRes(ResolutionPreset.P1080)
                base.copy(
                    resolution = res, fps = clampFps(120, res, bestCodec), codec = bestCodec,
                    bitrate = BitrateOption.RECOMMENDED, engine = EngineType.ADVANCED, audioBitrate = AudioBitrate.K192,
                    optimization = mode,
                )
            }
            OptimizationMode.MAXIMUM_QUALITY -> {
                val res = clampRes(if (tier == Tier.HIGH) ResolutionPreset.P2160 else ResolutionPreset.P1440)
                base.copy(
                    resolution = res, fps = clampFps(60, res, bestCodec), codec = bestCodec,
                    bitrate = if (tier == Tier.HIGH) BitrateOption.MBPS_40 else BitrateOption.MBPS_20,
                    engine = EngineType.ADVANCED, audioBitrate = AudioBitrate.K256, optimization = mode,
                )
            }
            OptimizationMode.GAMING -> GamingPresets.apply(base.gamingPreset, caps, base).copy(optimization = mode)
            OptimizationMode.CUSTOM, OptimizationMode.AUTOMATIC -> base.copy(optimization = mode)
        }
    }
}

object GamingPresets {
    fun apply(preset: GamingPreset, caps: DeviceCapabilities, base: RecordingSettings): RecordingSettings {
        val display = caps.displaySize
        val hevc = caps.hasHardware(VideoCodec.HEVC) && (caps.apiLevel == 0 || caps.apiLevel >= 24)
        fun res(want: ResolutionPreset): ResolutionPreset =
            ResolutionPreset.fixed.filter { it.shortSide <= want.shortSide && VideoConfigResolver.sizeForPreset(it, display) != null }
                .lastOrNull() ?: ResolutionPreset.ORIGINAL
        fun fps(want: Int, r: ResolutionPreset, codec: VideoCodec): FpsOption {
            val size: Size = VideoConfigResolver.sizeForPreset(r, display) ?: display
            val max = if (caps.encoders.isEmpty()) caps.maxRefreshRate.toInt() else VideoConfigResolver.maxSupportedFps(size, caps, codec)
            val t = minOf(want, max)
            return FpsOption.entries.filter { it != FpsOption.AUTO && it.fps <= t }.maxByOrNull { it.fps } ?: FpsOption.FPS_30
        }
        val codec = if (hevc && preset != GamingPreset.SMOOTH) VideoCodec.HEVC else VideoCodec.H264
        val r = when (preset) {
            GamingPreset.SMOOTH -> res(ResolutionPreset.P720)
            else -> res(ResolutionPreset.P1080)
        }
        val f = when (preset) {
            GamingPreset.SMOOTH -> fps(60, r, codec)
            GamingPreset.BALANCED -> fps(60, r, codec)
            GamingPreset.HIGH_QUALITY -> fps(120, r, codec)
            GamingPreset.COMPETITIVE -> fps(144, r, codec)
            GamingPreset.MAX_FPS -> fps(240, r, codec)
        }
        return base.copy(
            resolution = r,
            fps = f,
            codec = codec,
            bitrate = if (preset == GamingPreset.HIGH_QUALITY) BitrateOption.MBPS_20 else BitrateOption.RECOMMENDED,
            engine = EngineType.ADVANCED,
            gamingMode = true,
            gamingPreset = preset,
            audioSource = if (caps.playbackCaptureSupported) com.qorvexa.recorder.core.model.AudioSource.INTERNAL_AND_MIC else base.audioSource,
            keyframeIntervalSec = 2,
        )
    }
}
