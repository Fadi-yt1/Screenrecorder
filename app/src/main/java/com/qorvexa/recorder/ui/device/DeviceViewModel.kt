package com.qorvexa.recorder.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.video.VideoConfigResolver
import com.qorvexa.recorder.device.DeviceRepository
import com.qorvexa.recorder.device.DeviceTestReport
import com.qorvexa.recorder.device.DeviceTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val repo: DeviceRepository,
    private val tester: DeviceTester,
) : ViewModel() {
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val report = MutableStateFlow<DeviceTestReport?>(null)

    fun runTest() {
        if (running.value) return
        viewModelScope.launch {
            running.value = true
            report.value = null
            try {
                val caps = repo.refresh()
                val display = caps.displaySize
                fun sz(p: ResolutionPreset): Size? = VideoConfigResolver.sizeForPreset(p, Size(display.shortSide, display.longSide))
                val tests = buildList {
                    sz(ResolutionPreset.P720)?.let { add(Triple(VideoCodec.H264, it, 30)) }
                    sz(ResolutionPreset.P1080)?.let { s ->
                        add(Triple(VideoCodec.H264, s, 60))
                        if (caps.hasHardware(VideoCodec.HEVC)) add(Triple(VideoCodec.HEVC, s, 60))
                        val maxFps = VideoConfigResolver.maxSupportedFps(s, caps, VideoCodec.H264)
                        if (maxFps > 60) add(Triple(VideoCodec.H264, s, maxFps))
                    }
                    if (display.shortSide >= 2160) sz(ResolutionPreset.P2160)?.let { add(Triple(VideoCodec.H264, it, 30)) }
                    else if (display.shortSide >= 1440) sz(ResolutionPreset.P1440)?.let { add(Triple(VideoCodec.H264, it, 60)) }
                }
                report.value = tester.run(tests) { progress.value = it }
            } finally {
                running.value = false
                progress.value = ""
            }
        }
    }
}
