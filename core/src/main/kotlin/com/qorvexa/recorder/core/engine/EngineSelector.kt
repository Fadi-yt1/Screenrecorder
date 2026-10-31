package com.qorvexa.recorder.core.engine

import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.model.SupportStatus
import com.qorvexa.recorder.core.model.Tier
import com.qorvexa.recorder.core.device.ProfileCalculator

data class EngineCompatibility(
    val engine: EngineType,
    val status: SupportStatus,
    val detail: String,
    val selectable: Boolean,
)

object EngineSelector {

    /** Minimum GLES version for the OpenGL engine: 2.0 plus the external-texture extension (API 21+ always has it). */
    private fun glMajor(caps: DeviceCapabilities): Int =
        caps.glEsVersion.substringBefore('.').filter { it.isDigit() }.toIntOrNull() ?: 0

    fun compatibility(caps: DeviceCapabilities): List<EngineCompatibility> {
        val gl = glMajor(caps)
        return listOf(
            EngineCompatibility(EngineType.AUTO, SupportStatus.SUPPORTED, "Chooses per recording based on your settings and hardware.", true),
            EngineCompatibility(EngineType.BASIC, SupportStatus.SUPPORTED, "Works on every Android 5.0+ device.", true),
            EngineCompatibility(EngineType.ADVANCED, SupportStatus.SUPPORTED, "Works on every Android 5.0+ device.", true),
            if (gl >= 2) {
                EngineCompatibility(EngineType.OPENGL, SupportStatus.SUPPORTED, "OpenGL ES ${caps.glEsVersion} · ${caps.glRenderer}".trim(), true)
            } else {
                EngineCompatibility(EngineType.OPENGL, SupportStatus.NOT_SUPPORTED, "OpenGL ES 2.0 not detected.", false)
            },
            // Honest status: the Vulkan compositor is not part of this build. We report
            // whether the device has Vulkan so the option can be enabled in a future release.
            EngineCompatibility(
                EngineType.VULKAN,
                if (caps.vulkanSupported) SupportStatus.PARTIAL else SupportStatus.NOT_SUPPORTED,
                if (caps.vulkanSupported) {
                    "Vulkan ${caps.vulkanVersion} detected, but this build has no Vulkan compositor yet. " +
                        "The OpenGL engine gives the same GPU composition features."
                } else "Vulkan not available on this device.",
                false,
            ),
        )
    }

    /** Settings that need GPU composition (only the OpenGL engine can draw into the video). */
    fun needsComposition(settings: RecordingSettings): Boolean = settings.watermark

    /**
     * Picks the concrete engine for a session.
     * - Composition features force OpenGL (if available).
     * - Low-end devices get Basic: fewest threads and buffers.
     * - Otherwise Advanced.
     */
    fun select(settings: RecordingSettings, caps: DeviceCapabilities): EngineType {
        val glOk = glMajor(caps) >= 2
        val requested = settings.engine
        if (requested == EngineType.OPENGL && glOk) return EngineType.OPENGL
        if (requested == EngineType.BASIC || requested == EngineType.ADVANCED) {
            return if (needsComposition(settings) && glOk) EngineType.OPENGL else requested
        }
        // AUTO, or an unavailable choice (Vulkan / OpenGL without GL).
        if (needsComposition(settings) && glOk) return EngineType.OPENGL
        return when (ProfileCalculator.profile(caps).tier) {
            Tier.LOW -> EngineType.BASIC
            else -> EngineType.ADVANCED
        }
    }

    /** Fallback chain used when an engine fails to start. */
    fun fallbackFor(engine: EngineType): EngineType? = when (engine) {
        EngineType.OPENGL, EngineType.VULKAN -> EngineType.ADVANCED
        EngineType.ADVANCED -> EngineType.BASIC
        EngineType.AUTO -> EngineType.BASIC
        EngineType.BASIC -> null
    }
}
