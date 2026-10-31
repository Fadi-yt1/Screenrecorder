package com.qorvexa.recorder.core.model

/** What one hardware or software encoder can do for a given codec. */
data class EncoderInfo(
    val name: String,
    val codec: VideoCodec,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Max frame rate the encoder reports at 1080p (or its max size if smaller). */
    val maxFpsAt1080: Int,
    /** Max frame rate the encoder reports at 2160p, 0 if 4K is not supported. */
    val maxFpsAt2160: Int,
    val maxBitrate: Int,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    /** Optional precise check supplied by the platform layer: (width, height, fps) -> supported. */
    val sizeRateCheck: ((Int, Int, Int) -> Boolean)? = null,
) {
    fun supports(width: Int, height: Int, fps: Int): Boolean {
        sizeRateCheck?.let { return it(width, height, fps) }
        val long = maxOf(width, height)
        val short = minOf(width, height)
        if (long > maxOf(maxWidth, maxHeight) || short > minOf(maxWidth, maxHeight)) return false
        val limit = if (short > 1440) maxFpsAt2160 else maxFpsAt1080
        return fps <= limit
    }
}

enum class Tier(val label: String) { LOW("Low-end"), MID("Mid-range"), HIGH("High-end") }

enum class SupportStatus(val label: String) {
    SUPPORTED("Supported"),
    PARTIAL("Partially Supported"),
    NOT_SUPPORTED("Not Supported"),
    PERMISSION_REQUIRED("Permission Required"),
}

data class DeviceCapabilities(
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val apiLevel: Int = 0,
    val cpuAbi: String = "",
    val cpuCores: Int = 1,
    val cpuMaxFreqMhz: Int = 0,
    val socName: String = "",
    val totalRamMb: Long = 0,
    val availRamMb: Long = 0,
    val lowRamDevice: Boolean = false,
    val glRenderer: String = "",
    val glVendor: String = "",
    val glEsVersion: String = "",
    val vulkanSupported: Boolean = false,
    val vulkanVersion: String = "",
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val densityDpi: Int = 0,
    val refreshRate: Float = 60f,
    val supportedRefreshRates: List<Float> = emptyList(),
    val encoders: List<EncoderInfo> = emptyList(),
    val playbackCaptureSupported: Boolean = false,
    val micAvailable: Boolean = false,
    val stereoMic: Boolean = false,
    val inputDevices: List<AudioInputDevice> = emptyList(),
    val effects: AudioEffectsSupport = AudioEffectsSupport(),
    val storageFreeBytes: Long = 0,
    val storageTotalBytes: Long = 0,
    val thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN,
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
) {
    val displaySize: Size get() = Size(displayWidth, displayHeight)
    val maxRefreshRate: Float get() = (supportedRefreshRates + refreshRate).maxOrNull() ?: 60f

    fun encodersFor(codec: VideoCodec): List<EncoderInfo> = encoders.filter { it.codec == codec }
    fun bestEncoder(codec: VideoCodec, preferHardware: Boolean = true): EncoderInfo? {
        val list = encodersFor(codec)
        return if (preferHardware) list.firstOrNull { it.hardware } ?: list.firstOrNull() else list.firstOrNull()
    }

    fun hasHardware(codec: VideoCodec): Boolean = encodersFor(codec).any { it.hardware }
}

data class AudioInputDevice(val id: Int, val name: String, val type: String, val stereo: Boolean)

data class AudioEffectsSupport(
    val noiseSuppressor: Boolean = false,
    val echoCanceler: Boolean = false,
    val autoGain: Boolean = false,
)

enum class ThermalStatus(val label: String, val level: Int) {
    UNKNOWN("Unknown", -1), NONE("Normal", 0), LIGHT("Light", 1), MODERATE("Moderate", 2),
    SEVERE("Severe", 3), CRITICAL("Critical", 4), EMERGENCY("Emergency", 5), SHUTDOWN("Shutdown", 6);

    val isHot: Boolean get() = level >= MODERATE.level

    companion object {
        fun fromPlatform(level: Int): ThermalStatus = entries.firstOrNull { it.level == level } ?: UNKNOWN
    }
}
