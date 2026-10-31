package com.qorvexa.recorder.core

import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EncoderInfo
import com.qorvexa.recorder.core.model.VideoCodec

/** Synthetic devices for tests. These are test fixtures only; the app reads real hardware. */
object TestDevices {
    fun avc(hw: Boolean = true, fps1080: Int = 60, fps2160: Int = 0, maxW: Int = 1920, maxH: Int = 1088) =
        EncoderInfo("enc.avc.${if (hw) "hw" else "sw"}", VideoCodec.H264, hw, maxW, maxH, fps1080, fps2160, 40_000_000, supportsCbr = true, supportsVbr = true)

    fun hevc(fps1080: Int = 120, fps2160: Int = 60) =
        EncoderInfo("enc.hevc.hw", VideoCodec.HEVC, true, 3840, 2176, fps1080, fps2160, 100_000_000, supportsCbr = false, supportsVbr = true)

    val lowEnd = DeviceCapabilities(
        apiLevel = 26, cpuCores = 4, cpuMaxFreqMhz = 1800, totalRamMb = 2048, glEsVersion = "3.0",
        displayWidth = 720, displayHeight = 1520, refreshRate = 60f,
        encoders = listOf(avc(fps1080 = 30), avc(hw = false, fps1080 = 30)),
    )

    val midRange = DeviceCapabilities(
        apiLevel = 31, cpuCores = 8, cpuMaxFreqMhz = 2400, totalRamMb = 6144, glEsVersion = "3.2",
        displayWidth = 1080, displayHeight = 2400, refreshRate = 90f, supportedRefreshRates = listOf(60f, 90f),
        encoders = listOf(avc(fps1080 = 60, fps2160 = 30, maxW = 4096, maxH = 2176), hevc(fps1080 = 60, fps2160 = 0)),
    )

    val flagship = DeviceCapabilities(
        apiLevel = 35, cpuCores = 8, cpuMaxFreqMhz = 3300, totalRamMb = 12288, glEsVersion = "3.2", vulkanSupported = true,
        displayWidth = 1440, displayHeight = 3120, refreshRate = 120f, supportedRefreshRates = listOf(60f, 120f),
        encoders = listOf(avc(fps1080 = 240, fps2160 = 60, maxW = 4096, maxH = 2176), hevc(fps1080 = 240, fps2160 = 60)),
        playbackCaptureSupported = true,
    )
}
