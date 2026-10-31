package com.qorvexa.recorder.core.model

/** Target resolution presets. [shortSide] is the short edge in pixels (e.g. 1080 for 1080p). */
enum class ResolutionPreset(val label: String, val shortSide: Int) {
    P480("480p", 480),
    P720("720p HD", 720),
    P1080("1080p Full HD", 1080),
    P1440("1440p QHD", 1440),
    P2160("2160p 4K UHD", 2160),
    ORIGINAL("Original", 0),
    CUSTOM("Custom", 0);

    companion object {
        val fixed = listOf(P480, P720, P1080, P1440, P2160)
    }
}

/** Frame-rate choices. [fps] = 0 means automatic. */
enum class FpsOption(val fps: Int) {
    AUTO(0), FPS_15(15), FPS_24(24), FPS_30(30), FPS_48(48), FPS_60(60), FPS_90(90),
    FPS_120(120), FPS_144(144), FPS_165(165), FPS_180(180), FPS_200(200), FPS_240(240);

    val label: String get() = if (this == AUTO) "Auto" else "$fps FPS"

    companion object {
        fun of(fps: Int): FpsOption = entries.firstOrNull { it.fps == fps } ?: AUTO
    }
}

/** Bitrate presets in bits per second. [bps] = 0 means custom / recommended. */
enum class BitrateOption(val bps: Int) {
    RECOMMENDED(0),
    MBPS_1(1_000_000), MBPS_2(2_000_000), MBPS_4(4_000_000), MBPS_6(6_000_000),
    MBPS_8(8_000_000), MBPS_10(10_000_000), MBPS_12(12_000_000), MBPS_16(16_000_000),
    MBPS_20(20_000_000), MBPS_30(30_000_000), MBPS_40(40_000_000), MBPS_50(50_000_000),
    CUSTOM(-1);

    val label: String
        get() = when (this) {
            RECOMMENDED -> "Recommended"
            CUSTOM -> "Custom"
            else -> "${bps / 1_000_000} Mbps"
        }
}

enum class VideoCodec(val mime: String, val label: String) {
    H264("video/avc", "H.264 (AVC)"),
    HEVC("video/hevc", "H.265 (HEVC)"),
    AV1("video/av01", "AV1"),
}

enum class BitrateMode(val label: String) { VBR("Variable (VBR)"), CBR("Constant (CBR)") }

enum class OrientationMode(val label: String) { AUTO("Automatic"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }

data class Size(val width: Int, val height: Int) {
    val shortSide: Int get() = minOf(width, height)
    val longSide: Int get() = maxOf(width, height)
    val isLandscape: Boolean get() = width > height
    val pixels: Long get() = width.toLong() * height
    override fun toString(): String = "${width}x$height"
}
