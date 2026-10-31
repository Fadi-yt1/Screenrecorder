package com.qorvexa.recorder.core.device

import com.qorvexa.recorder.core.TestDevices
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.GamingPreset
import com.qorvexa.recorder.core.model.OptimizationMode
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.Tier
import com.qorvexa.recorder.core.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCalculatorTest {
    @Test fun tiersFromHardware() {
        assertEquals(Tier.LOW, ProfileCalculator.profile(TestDevices.lowEnd).tier)
        assertEquals(Tier.MID, ProfileCalculator.profile(TestDevices.midRange).tier)
        assertEquals(Tier.HIGH, ProfileCalculator.profile(TestDevices.flagship).tier)
    }

    @Test fun lowRamFlagCapsTier() {
        val caps = TestDevices.flagship.copy(lowRamDevice = true)
        assertEquals(Tier.LOW, ProfileCalculator.profile(caps).tier)
    }

    @Test fun automaticPicksPerTier() {
        val low = ProfileCalculator.recommend(OptimizationMode.AUTOMATIC, TestDevices.lowEnd, RecordingSettings())
        assertEquals(ResolutionPreset.P720, low.resolution)
        assertEquals(30, low.fps.fps)
        assertEquals(VideoCodec.H264, low.codec)
        assertEquals(EngineType.BASIC, low.engine)

        val mid = ProfileCalculator.recommend(OptimizationMode.AUTOMATIC, TestDevices.midRange, RecordingSettings())
        assertEquals(ResolutionPreset.P1080, mid.resolution)
        assertEquals(60, mid.fps.fps)

        val high = ProfileCalculator.recommend(OptimizationMode.AUTOMATIC, TestDevices.flagship, RecordingSettings())
        assertEquals(VideoCodec.HEVC, high.codec)
        assertEquals(120, high.fps.fps) // capped at display refresh, not 240
    }

    @Test fun maxQualityOnFlagshipUses4kWhenScreenAllows() {
        val s = ProfileCalculator.recommend(OptimizationMode.MAXIMUM_QUALITY, TestDevices.flagship, RecordingSettings())
        // 1440p display: 4K would be upscaling, so the best real option is 1440p.
        assertEquals(ResolutionPreset.P1440, s.resolution)
    }

    @Test fun gamingMaxFpsNeverExceedsRefresh() {
        val s = GamingPresets.apply(GamingPreset.MAX_FPS, TestDevices.midRange, RecordingSettings())
        assertTrue(s.fps.fps <= 90)
        assertTrue(s.gamingMode)
    }
}
