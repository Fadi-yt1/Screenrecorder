package com.qorvexa.recorder.core.video

import com.qorvexa.recorder.core.model.BitrateMode
import com.qorvexa.recorder.core.model.BitrateOption
import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EncoderInfo
import com.qorvexa.recorder.core.model.FpsOption
import com.qorvexa.recorder.core.model.OrientationMode
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.core.model.VideoCodec
import kotlin.math.roundToInt

/** Final, validated configuration handed to the encoder. */
data class ResolvedVideoConfig(
    val size: Size,
    val fps: Int,
    val bitrate: Int,
    val codec: VideoCodec,
    val encoderName: String?,
    val hardware: Boolean,
    val bitrateMode: BitrateMode,
    val keyframeIntervalSec: Int,
    val notes: List<String>,
)

/** Whether a choice can be used on this device, and why not when it cannot. */
data class Availability(val available: Boolean, val reason: String? = null) {
    companion object {
        val OK = Availability(true)
        fun no(reason: String) = Availability(false, reason)
    }
}

/**
 * Turns user settings plus detected hardware into an encoder configuration.
 * It never upscales beyond the physical display and never picks a frame rate
 * above what the display can deliver.
 */
object VideoConfigResolver {

    /**
     * Dimension alignment. 8 works on virtually all hardware encoders; the platform layer
     * retries with 16 if an old encoder rejects the size.
     */
    @Volatile var alignment: Int = 8

    /** Rounds down to [alignment] so we never upscale. */
    fun align(v: Int): Int = maxOf(alignment, (v / alignment) * alignment)

    /** Native display size in the orientation the recording will use. */
    fun orientedDisplay(caps: DeviceCapabilities, orientation: OrientationMode, currentlyLandscape: Boolean): Size {
        val d = caps.displaySize
        val landscape = when (orientation) {
            OrientationMode.AUTO -> currentlyLandscape
            OrientationMode.LANDSCAPE -> true
            OrientationMode.PORTRAIT -> false
        }
        return if (landscape) Size(d.longSide, d.shortSide) else Size(d.shortSide, d.longSide)
    }

    /**
     * Computes the target size for a preset, keeping the display's aspect ratio.
     * Returns null when the preset is bigger than the display (we never upscale).
     */
    fun sizeForPreset(preset: ResolutionPreset, display: Size, customW: Int = 0, customH: Int = 0): Size? {
        return when (preset) {
            ResolutionPreset.ORIGINAL -> Size(align(display.width), align(display.height))
            ResolutionPreset.CUSTOM -> {
                if (customW <= 0 || customH <= 0) return null
                // Fit the custom box to the display orientation without upscaling.
                val w = if (display.isLandscape) maxOf(customW, customH) else minOf(customW, customH)
                val h = if (display.isLandscape) minOf(customW, customH) else maxOf(customW, customH)
                if (w > display.width || h > display.height) null else Size(align(w), align(h))
            }
            else -> {
                if (preset.shortSide > display.shortSide) return null
                val scale = preset.shortSide.toDouble() / display.shortSide
                val long = align((display.longSide * scale).roundToInt())
                val short = align(preset.shortSide)
                if (display.isLandscape) Size(long, short) else Size(short, long)
            }
        }
    }

    fun resolutionAvailability(
        preset: ResolutionPreset,
        caps: DeviceCapabilities,
        codec: VideoCodec,
        settings: RecordingSettings,
    ): Availability {
        val display = orientedDisplay(caps, settings.orientation, caps.displaySize.isLandscape)
        val size = sizeForPreset(preset, display, settings.customWidth, settings.customHeight)
            ?: return Availability.no("Your screen is ${caps.displaySize.shortSide}p. Recording above native resolution would just be upscaling.")
        val encoder = caps.bestEncoder(codec, settings.preferHardwareEncoder)
            ?: return Availability.no("No ${codec.label} encoder on this device.")
        if (!caps.encodersFor(codec).any { it.supports(size.width, size.height, 30) }) {
            return Availability.no("${encoder.name} can't encode ${size} with ${codec.label}.")
        }
        return Availability.OK
    }

    fun fpsAvailability(
        fps: FpsOption,
        size: Size,
        caps: DeviceCapabilities,
        codec: VideoCodec,
    ): Availability {
        if (fps == FpsOption.AUTO) return Availability.OK
        val refresh = caps.maxRefreshRate.roundToInt()
        if (fps.fps > refresh + 1) {
            return Availability.no("Your display refreshes at up to $refresh Hz, so the screen never produces more than $refresh frames per second.")
        }
        if (caps.encodersFor(codec).none { it.supports(size.width, size.height, fps.fps) }) {
            return Availability.no("The ${codec.label} encoder can't sustain ${fps.fps} FPS at $size.")
        }
        return Availability.OK
    }

    fun codecAvailability(codec: VideoCodec, caps: DeviceCapabilities): Availability {
        // MediaMuxer can write HEVC to MP4 from API 24 and AV1 from API 34.
        val minApi = when (codec) {
            VideoCodec.H264 -> 21
            VideoCodec.HEVC -> 24
            VideoCodec.AV1 -> 34
        }
        if (caps.apiLevel in 1 until minApi) return Availability.no("Needs Android API $minApi or newer to save ${codec.label} in MP4.")
        if (caps.encodersFor(codec).isEmpty()) return Availability.no("No ${codec.label} encoder on this device.")
        if (!caps.hasHardware(codec)) return Availability(true, "Software encoder only: slower and uses more battery.")
        return Availability.OK
    }

    /** Highest FPS that the display and at least one encoder can deliver at [size]. */
    fun maxSupportedFps(size: Size, caps: DeviceCapabilities, codec: VideoCodec): Int {
        val refresh = caps.maxRefreshRate.roundToInt()
        return FpsOption.entries
            .filter { it != FpsOption.AUTO && it.fps <= refresh + 1 }
            .map { it.fps }
            .filter { f -> caps.encodersFor(codec).any { it.supports(size.width, size.height, f) } }
            .maxOrNull() ?: 30
    }

    /**
     * Resolves the final config. Unsupported requests are stepped down to the closest
     * supported value and explained in [ResolvedVideoConfig.notes]; the UI shows those
     * notes before recording starts so nothing changes silently.
     */
    fun resolve(settings: RecordingSettings, caps: DeviceCapabilities, currentlyLandscape: Boolean): ResolvedVideoConfig {
        val notes = mutableListOf<String>()

        // 1. Codec
        var codec = settings.codec
        if (!codecAvailability(codec, caps).available) {
            notes += "${codec.label} is not available here; using H.264."
            codec = VideoCodec.H264
        }

        // 2. Size
        val display = orientedDisplay(caps, settings.orientation, currentlyLandscape)
        var size = sizeForPreset(settings.resolution, display, settings.customWidth, settings.customHeight)
        if (size == null) {
            val fallback = ResolutionPreset.fixed.lastOrNull { sizeForPreset(it, display) != null }
            size = fallback?.let { sizeForPreset(it, display) } ?: sizeForPreset(ResolutionPreset.ORIGINAL, display)!!
            notes += "${settings.resolution.label} is above your screen's native resolution; recording at $size."
        }

        // 3. Encoder that supports the size; step resolution down until one does.
        var encoder = pickEncoder(caps, codec, size, 30, settings.preferHardwareEncoder)
        if (encoder == null) {
            val smaller = ResolutionPreset.fixed.reversed()
                .mapNotNull { sizeForPreset(it, display) }
                .firstOrNull { it.pixels < size!!.pixels && pickEncoder(caps, codec, it, 30, settings.preferHardwareEncoder) != null }
            if (smaller != null) {
                notes += "The encoder can't handle $size; recording at $smaller."
                size = smaller
                encoder = pickEncoder(caps, codec, size, 30, settings.preferHardwareEncoder)
            }
        }
        val finalSize = size!!

        // 4. FPS
        val maxFps = if (caps.encoders.isEmpty()) caps.maxRefreshRate.roundToInt() else maxSupportedFps(finalSize, caps, codec)
        val requested = if (settings.fps == FpsOption.AUTO) maxFps.coerceAtMost(60) else settings.fps.fps
        val fps = if (requested > maxFps) {
            notes += "${requested} FPS isn't achievable here; using $maxFps FPS."
            maxFps
        } else requested
        if (encoder != null && !encoder.supports(finalSize.width, finalSize.height, fps)) {
            encoder = pickEncoder(caps, codec, finalSize, fps, settings.preferHardwareEncoder) ?: encoder
        }

        // 5. Bitrate
        val recommended = BitrateCalculator.recommend(finalSize.width, finalSize.height, fps, codec)
        var bitrate = when (settings.bitrate) {
            BitrateOption.RECOMMENDED -> recommended
            BitrateOption.CUSTOM -> (settings.customBitrateMbps.coerceIn(1, 200)) * 1_000_000
            else -> settings.bitrate.bps
        }
        if (encoder != null && encoder.maxBitrate in 1 until bitrate) {
            notes += "Encoder limit is ${encoder.maxBitrate / 1_000_000} Mbps; bitrate capped."
            bitrate = encoder.maxBitrate
        }

        // 6. Bitrate mode
        var mode = settings.bitrateMode
        if (encoder != null) {
            if (mode == BitrateMode.CBR && !encoder.supportsCbr) {
                notes += "Constant bitrate isn't supported by ${encoder.name}; using variable bitrate."
                mode = BitrateMode.VBR
            }
        }

        return ResolvedVideoConfig(
            size = finalSize,
            fps = fps,
            bitrate = bitrate,
            codec = codec,
            encoderName = encoder?.name,
            hardware = encoder?.hardware ?: false,
            bitrateMode = mode,
            keyframeIntervalSec = settings.keyframeIntervalSec.coerceIn(1, 10),
            notes = notes,
        )
    }

    private fun pickEncoder(caps: DeviceCapabilities, codec: VideoCodec, size: Size, fps: Int, preferHw: Boolean): EncoderInfo? {
        val candidates = caps.encodersFor(codec).filter { it.supports(size.width, size.height, fps) }
        return if (preferHw) candidates.firstOrNull { it.hardware } ?: candidates.firstOrNull() else candidates.firstOrNull()
    }
}
