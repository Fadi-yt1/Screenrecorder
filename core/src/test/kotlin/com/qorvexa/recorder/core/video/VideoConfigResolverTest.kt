package com.qorvexa.recorder.core.video

import com.qorvexa.recorder.core.TestDevices
import com.qorvexa.recorder.core.model.BitrateMode
import com.qorvexa.recorder.core.model.FpsOption
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.core.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoConfigResolverTest {

    @Test fun presetKeepsAspectAndAligns() {
        val s = VideoConfigResolver.sizeForPreset(ResolutionPreset.P720, Size(1080, 2400))!!
        assertEquals(720, s.width)
        assertEquals(1600, s.height)
        assertEquals(0, s.width % 16)
        assertEquals(0, s.height % 16)
    }

    @Test fun neverUpscalesAboveDisplay() {
        assertNull(VideoConfigResolver.sizeForPreset(ResolutionPreset.P2160, Size(1080, 2400)))
        assertNull(VideoConfigResolver.sizeForPreset(ResolutionPreset.P1080, Size(720, 1520)))
    }

    @Test fun fourKUnavailableOn1080Display() {
        val a = VideoConfigResolver.resolutionAvailability(ResolutionPreset.P2160, TestDevices.midRange, VideoCodec.H264, RecordingSettings())
        assertFalse(a.available)
        assertTrue(a.reason!!.contains("upscaling"))
    }

    @Test fun fpsAboveRefreshRateIsRejected() {
        val a = VideoConfigResolver.fpsAvailability(FpsOption.FPS_240, Size(1080, 2400), TestDevices.flagship, VideoCodec.H264)
        assertFalse(a.available)
        assertTrue(a.reason!!.contains("120 Hz"))
        assertTrue(VideoConfigResolver.fpsAvailability(FpsOption.FPS_120, Size(1088, 2400), TestDevices.flagship, VideoCodec.H264).available)
    }

    @Test fun resolveStepsDownAndExplains() {
        val settings = RecordingSettings(resolution = ResolutionPreset.P1440, fps = FpsOption.FPS_120)
        val r = VideoConfigResolver.resolve(settings, TestDevices.lowEnd, currentlyLandscape = false)
        assertTrue(r.size.shortSide <= 720)
        assertEquals(30, r.fps)
        assertTrue(r.notes.isNotEmpty())
    }

    @Test fun flagshipGetsNative4kAt60() {
        val settings = RecordingSettings(resolution = ResolutionPreset.ORIGINAL, fps = FpsOption.FPS_60, codec = VideoCodec.HEVC)
        val r = VideoConfigResolver.resolve(settings, TestDevices.flagship, currentlyLandscape = false)
        assertEquals(VideoCodec.HEVC, r.codec)
        assertEquals(60, r.fps)
        assertTrue(r.hardware)
        assertEquals(1440, r.size.width)
    }

    @Test fun cbrFallsBackWhenUnsupported() {
        val settings = RecordingSettings(codec = VideoCodec.HEVC, bitrateMode = BitrateMode.CBR, resolution = ResolutionPreset.P1080)
        val r = VideoConfigResolver.resolve(settings, TestDevices.flagship, false)
        assertEquals(BitrateMode.VBR, r.bitrateMode)
    }

    @Test fun landscapeOrientationSwapsSize() {
        val settings = RecordingSettings(resolution = ResolutionPreset.P720, orientation = com.qorvexa.recorder.core.model.OrientationMode.LANDSCAPE)
        val r = VideoConfigResolver.resolve(settings, TestDevices.midRange, currentlyLandscape = false)
        assertTrue(r.size.isLandscape)
        assertEquals(720, r.size.height)
    }

    @Test fun av1NeedsApi34() {
        assertFalse(VideoConfigResolver.codecAvailability(VideoCodec.AV1, TestDevices.midRange).available)
    }

    @Test fun bitrateRecommendationScales() {
        val b720 = BitrateCalculator.recommend(720, 1600, 30, VideoCodec.H264)
        val b1080 = BitrateCalculator.recommend(1080, 2400, 30, VideoCodec.H264)
        val b1080_60 = BitrateCalculator.recommend(1080, 2400, 60, VideoCodec.H264)
        val hevc = BitrateCalculator.recommend(1080, 2400, 60, VideoCodec.HEVC)
        assertTrue(b720 < b1080)
        assertTrue(b1080 < b1080_60)
        assertTrue(hevc < b1080_60)
        assertTrue(b1080 in 4_000_000..12_000_000)
        assertEquals(0, b1080 % 500_000)
    }
}
