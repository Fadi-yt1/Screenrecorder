package com.qorvexa.recorder.core.model

enum class AudioSource(val label: String, val usesMic: Boolean, val usesInternal: Boolean) {
    NONE("No audio", false, false),
    MIC("Microphone only", true, false),
    INTERNAL("Internal audio only", false, true),
    INTERNAL_AND_MIC("Internal + Microphone", true, true),
}

enum class AudioBitrate(val bps: Int) {
    K64(64_000), K96(96_000), K128(128_000), K160(160_000), K192(192_000), K256(256_000), K320(320_000);

    val label: String get() = "${bps / 1000} kbps"
}

enum class SampleRate(val hz: Int) {
    HZ_22050(22_050), HZ_32000(32_000), HZ_44100(44_100), HZ_48000(48_000);

    val label: String get() = "%,d Hz".format(hz)
}

enum class ChannelMode(val label: String) { AUTO("Automatic"), MONO("Mono"), STEREO("Stereo") }

/** Microphone presets map to a set of processing toggles. */
enum class MicPreset(val label: String) { CUSTOM("Custom"), GAMING_COMMENTARY("Gaming commentary"), STUDIO_VOICE("Studio voice") }

data class MicProcessing(
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = false,
    val autoGain: Boolean = false,
    val voiceEnhancement: Boolean = false,
) {
    companion object {
        fun forPreset(preset: MicPreset, current: MicProcessing): MicProcessing = when (preset) {
            MicPreset.CUSTOM -> current
            // Game audio is usually coming out of the speaker, so echo cancellation matters.
            MicPreset.GAMING_COMMENTARY -> MicProcessing(noiseSuppression = true, echoCancellation = true, autoGain = true, voiceEnhancement = true)
            // Studio voice keeps the natural dynamics and avoids pumping from AGC.
            MicPreset.STUDIO_VOICE -> MicProcessing(noiseSuppression = true, echoCancellation = false, autoGain = false, voiceEnhancement = true)
        }
    }
}
