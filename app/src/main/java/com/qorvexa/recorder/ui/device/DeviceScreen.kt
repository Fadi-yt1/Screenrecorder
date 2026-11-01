package com.qorvexa.recorder.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qorvexa.recorder.core.device.ProfileCalculator
import com.qorvexa.recorder.core.engine.EngineSelector
import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.FpsOption
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.SupportStatus
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.video.VideoConfigResolver
import com.qorvexa.recorder.permissions.Permissions
import com.qorvexa.recorder.ui.AppViewModel
import com.qorvexa.recorder.ui.components.InfoNote
import com.qorvexa.recorder.ui.components.KeyValueRow
import com.qorvexa.recorder.ui.components.QxCard
import com.qorvexa.recorder.ui.components.QxTopBar
import com.qorvexa.recorder.ui.components.SectionTitle
import com.qorvexa.recorder.ui.components.StatusChip
import com.qorvexa.recorder.ui.components.formatBytes
import com.qorvexa.recorder.ui.theme.LocalQx

/** Screen 6 / Part 11: hardware information and per-feature support status. */
@Composable
fun DeviceScreen(vm: AppViewModel, dvm: DeviceViewModel = hiltViewModel()) {
    val caps by vm.caps.collectAsStateWithLifecycle()
    val running by dvm.running.collectAsStateWithLifecycle()
    val progress by dvm.progress.collectAsStateWithLifecycle()
    val report by dvm.report.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val qx = LocalQx.current
    Column(Modifier.fillMaxSize()) {
        QxTopBar("Device Compatibility", actions = { IconButton(onClick = { vm.redetect() }) { Icon(Icons.Filled.Refresh, "Detect again") } })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val c = caps ?: run {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Detecting hardware…") }
            return@Column
        }
        val profile = ProfileCalculator.profile(c)
        val features = features(c, Permissions.micGranted(ctx), Permissions.canDrawOverlays(ctx))
        val allOk = features.all { it.second == SupportStatus.SUPPORTED || it.second == SupportStatus.PERMISSION_REQUIRED }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(if (allOk) qx.green else qx.amber), contentAlignment = Alignment.Center) {
                Icon(if (allOk) Icons.Filled.Check else Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(
                if (allOk) "Your device is ready" else "Ready, with some limits",
                style = MaterialTheme.typography.headlineSmall, color = if (allOk) qx.green else qx.amber, modifier = Modifier.padding(top = 8.dp),
            )
            Text("${profile.tier.label} profile · score ${profile.score}/100", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
        }
        Spacer(Modifier.height(12.dp))
        QxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${c.manufacturer} ${c.model}", style = MaterialTheme.typography.titleMedium)
                    Text("Android ${c.androidVersion} · API ${c.apiLevel}", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
                }
            }
            Spacer(Modifier.height(8.dp))
            KeyValueRow("CPU", listOf(c.socName, "${c.cpuCores} cores", c.cpuMaxFreqMhz.takeIf { it > 0 }?.let { "%.2f GHz".format(it / 1000.0) }, c.cpuAbi).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
            KeyValueRow("GPU", c.glRenderer.ifBlank { "Unknown" })
            KeyValueRow("RAM", "${formatBytes(c.totalRamMb * 1024 * 1024)} (${formatBytes(c.availRamMb * 1024 * 1024)} free)")
            KeyValueRow("Display", "${c.displayWidth} × ${c.displayHeight} · ${c.densityDpi} dpi")
            KeyValueRow("Refresh rate", c.supportedRefreshRates.ifEmpty { listOf(c.refreshRate) }.joinToString(" / ") { "${it.toInt()} Hz" })
            KeyValueRow("OpenGL ES", c.glEsVersion.ifBlank { "—" })
            KeyValueRow("Vulkan", if (c.vulkanSupported) c.vulkanVersion else "Not available")
            KeyValueRow("Storage", "${formatBytes(c.storageFreeBytes)} free of ${formatBytes(c.storageTotalBytes)}")
            KeyValueRow("Thermal", c.thermalStatus.label)
            KeyValueRow("Battery", if (c.batteryPercent >= 0) "${c.batteryPercent}%${if (c.charging) " · charging" else ""}" else "—")
        }

        SectionTitle("Supported features")
        QxCard {
            features.forEach { (name, st, note) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = qx.textSoft) }
                    }
                    StatusChip(st)
                }
            }
        }

        SectionTitle("Hardware encoders")
        QxCard {
            if (c.encoders.isEmpty()) Text("No video encoders reported.")
            c.encoders.forEach { e ->
                KeyValueRow(
                    "${e.codec.label}${if (e.hardware) "" else " (software)"}",
                    "${e.name} · ≤${e.maxWidth}×${e.maxHeight} · ${e.maxFpsAt1080} FPS@1080p" + if (e.maxFpsAt2160 > 0) " · ${e.maxFpsAt2160}@4K" else "",
                )
            }
        }

        SectionTitle("Audio")
        QxCard {
            KeyValueRow("Microphone", if (c.micAvailable) "Available" else "Not found")
            KeyValueRow("Internal audio capture", if (c.playbackCaptureSupported) "Android 10+ supported" else "Needs Android 10")
            KeyValueRow("Inputs", c.inputDevices.joinToString { it.name }.ifBlank { "Built-in" })
            KeyValueRow("Noise suppressor", if (c.effects.noiseSuppressor) "Yes" else "No")
            KeyValueRow("Echo canceller", if (c.effects.echoCanceler) "Yes" else "No")
            KeyValueRow("Auto gain", if (c.effects.autoGain) "Yes" else "No")
        }

        SectionTitle("Compatibility test")
        QxCard {
            Text("Runs a short encode at several settings, checks the microphone and measures storage speed.", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { dvm.runTest() }, enabled = !running, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress.ifBlank { "Testing…" })
                } else Text("Run Compatibility Test")
            }
            report?.let { r ->
                Spacer(Modifier.height(10.dp))
                r.encoderResults.forEach { e ->
                    KeyValueRow(
                        "${e.size.shortSide}p ${e.targetFps} FPS ${e.codec.label}",
                        e.error?.let { "Failed" } ?: "%.0f FPS %s".format(e.achievedFps, if (e.passed) "✓" else "✗"),
                    )
                }
                KeyValueRow("Microphone", r.micError ?: r.micPeakDb?.let { "%.0f dBFS peak".format(it) } ?: "—")
                KeyValueRow("Storage write", r.storageWriteMbPerSec?.let { "%.0f MB/s".format(it) } ?: "—")
                InfoNote(r.recommendation, color = qx.cyan)
                InfoNote("This is a short check. It shows what the hardware can do right now, not that it can sustain it for an hour-long recording.")
            }
        }
        Spacer(Modifier.height(24.dp))
        }
    }
}

private fun features(c: DeviceCapabilities, mic: Boolean, overlay: Boolean): List<Triple<String, SupportStatus, String?>> {
    val display = c.displaySize
    val res4k = VideoConfigResolver.sizeForPreset(ResolutionPreset.P2160, display)
    val fourK = when {
        res4k == null -> Triple("4K recording", SupportStatus.NOT_SUPPORTED, "Screen is ${display.shortSide}p; 4K would be upscaled")
        c.encoders.any { it.supports(res4k.width, res4k.height, 30) } -> Triple("4K recording", SupportStatus.SUPPORTED, null)
        else -> Triple("4K recording", SupportStatus.NOT_SUPPORTED, "Encoder can't handle 4K")
    }
    val size1080 = VideoConfigResolver.sizeForPreset(ResolutionPreset.P1080, display) ?: display
    val maxFps = VideoConfigResolver.maxSupportedFps(size1080, c, VideoCodec.H264)
    val fps = FpsOption.entries.filter { it != FpsOption.AUTO && it.fps <= maxFps }.maxOfOrNull { it.fps } ?: 30
    val engines = EngineSelector.compatibility(c)
    return listOf(
        fourK,
        Triple("High frame rate", if (fps > 60) SupportStatus.SUPPORTED else SupportStatus.PARTIAL, "Up to $fps FPS at ${size1080.shortSide}p (display ${c.maxRefreshRate.toInt()} Hz)"),
        Triple("H.264 hardware", if (c.hasHardware(VideoCodec.H264)) SupportStatus.SUPPORTED else SupportStatus.PARTIAL, null),
        Triple("HEVC", when { c.hasHardware(VideoCodec.HEVC) && c.apiLevel >= 24 -> SupportStatus.SUPPORTED; c.encodersFor(VideoCodec.HEVC).isNotEmpty() -> SupportStatus.PARTIAL; else -> SupportStatus.NOT_SUPPORTED }, null),
        Triple("AV1", VideoConfigResolver.codecAvailability(VideoCodec.AV1, c).let { if (it.available) SupportStatus.SUPPORTED else SupportStatus.NOT_SUPPORTED }, VideoConfigResolver.codecAvailability(VideoCodec.AV1, c).reason),
        Triple("OpenGL engine", engines.first { it.engine == EngineType.OPENGL }.status, null),
        Triple("Vulkan engine", engines.first { it.engine == EngineType.VULKAN }.status, engines.first { it.engine == EngineType.VULKAN }.detail),
        Triple("Microphone", if (!c.micAvailable) SupportStatus.NOT_SUPPORTED else Permissions.status(mic), null),
        Triple("Internal audio", when { !c.playbackCaptureSupported -> SupportStatus.NOT_SUPPORTED; !mic -> SupportStatus.PERMISSION_REQUIRED; else -> SupportStatus.SUPPORTED }, if (!c.playbackCaptureSupported) "Needs Android 10" else null),
        Triple("Stereo audio", if (c.playbackCaptureSupported || c.stereoMic) SupportStatus.SUPPORTED else SupportStatus.PARTIAL, if (!c.playbackCaptureSupported && !c.stereoMic) "Mono microphone only" else null),
        Triple("Floating overlay", Permissions.status(overlay), null),
        Triple("HDR capture", SupportStatus.NOT_SUPPORTED, "Android screen capture is SDR only"),
    )
}
