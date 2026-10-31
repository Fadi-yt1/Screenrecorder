package com.qorvexa.recorder.core.model

enum class EngineType(val label: String, val description: String) {
    AUTO("Auto Select", "Picks the lightest engine that supports your settings."),
    BASIC("Basic", "Screen goes straight into the hardware encoder. Lowest CPU and memory use."),
    ADVANCED("Advanced", "Asynchronous encoder pipeline on a dedicated thread with paced frame delivery."),
    OPENGL("OpenGL ES", "GPU composition: exact FPS cap, watermark, drawings and rotation-aware letterboxing."),
    VULKAN("Vulkan", "Vulkan compositor."),
}

enum class OptimizationMode(val label: String) {
    AUTOMATIC("Automatic"),
    BATTERY_SAVER("Battery Saver"),
    BALANCED("Balanced"),
    HIGH_PERFORMANCE("High Performance"),
    MAXIMUM_QUALITY("Maximum Quality"),
    GAMING("Gaming Mode"),
    CUSTOM("Custom"),
}

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

/**
 * Every user-facing recording option. All of these are read by the recording engine
 * when a session starts; nothing here is cosmetic.
 */
data class RecordingSettings(
    // Video
    val resolution: ResolutionPreset = ResolutionPreset.P1080,
    val customWidth: Int = 1920,
    val customHeight: Int = 1080,
    val fps: FpsOption = FpsOption.FPS_30,
    val bitrate: BitrateOption = BitrateOption.RECOMMENDED,
    val customBitrateMbps: Int = 8,
    val codec: VideoCodec = VideoCodec.H264,
    val bitrateMode: BitrateMode = BitrateMode.VBR,
    val keyframeIntervalSec: Int = 1,
    val orientation: OrientationMode = OrientationMode.AUTO,
    val preferHardwareEncoder: Boolean = true,
    // Audio
    val audioSource: AudioSource = AudioSource.MIC,
    val audioBitrate: AudioBitrate = AudioBitrate.K128,
    val sampleRate: SampleRate = SampleRate.HZ_44100,
    val channels: ChannelMode = ChannelMode.AUTO,
    val micPreset: MicPreset = MicPreset.CUSTOM,
    val micProcessing: MicProcessing = MicProcessing(),
    val micGainPercent: Int = 100,
    val internalVolumePercent: Int = 100,
    val micDeviceId: Int = 0, // 0 = system default
    // Engine / optimisation
    val engine: EngineType = EngineType.AUTO,
    val optimization: OptimizationMode = OptimizationMode.AUTOMATIC,
    // Behaviour
    val countdownSec: Int = 3,
    val showTouchesHint: Boolean = false,
    val watermark: Boolean = false,
    val maxDurationMin: Int = 0, // 0 = unlimited
    val maxFileSizeMb: Int = 0,  // 0 = unlimited
    val lowStorageProtection: Boolean = true,
    val minFreeStorageMb: Int = 500,
    val batteryProtection: Boolean = false,
    val batteryStopPercent: Int = 10,
    val keepScreenOn: Boolean = true,
    val hideOverlayWhileRecording: Boolean = false,
    val shakeToStart: Boolean = false,
    val fileNamePattern: String = FileNamePattern.DEFAULT,
    val rememberLastConfig: Boolean = true,
    val notificationControls: Boolean = true,
    val gamingMode: Boolean = false,
    val gamingPreset: GamingPreset = GamingPreset.BALANCED,
    val theme: ThemeMode = ThemeMode.DARK,
)

object FileNamePattern {
    const val DEFAULT = "QORVEXA_Recording_{n}"
    val TOKENS = listOf("{n}", "{date}", "{time}", "{res}", "{fps}")
}

enum class GamingPreset(val label: String, val description: String) {
    SMOOTH("Smooth Gameplay", "720p · 60 FPS · light on the GPU"),
    BALANCED("Balanced Gameplay", "1080p · 60 FPS"),
    HIGH_QUALITY("High Quality Gameplay", "1080p · up to 120 FPS · higher bitrate"),
    COMPETITIVE("Competitive Gameplay", "1080p · up to 144 FPS · low latency"),
    MAX_FPS("Maximum Supported FPS", "Highest frame rate your display and encoder can actually deliver"),
}
