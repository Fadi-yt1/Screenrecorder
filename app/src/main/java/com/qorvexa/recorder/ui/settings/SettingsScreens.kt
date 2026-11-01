package com.qorvexa.recorder.ui.settings

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.qorvexa.recorder.BuildConfig
import com.qorvexa.recorder.R
import com.qorvexa.recorder.core.model.AudioBitrate
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.model.BitrateMode
import com.qorvexa.recorder.core.model.BitrateOption
import com.qorvexa.recorder.core.model.ChannelMode
import com.qorvexa.recorder.core.model.FpsOption
import com.qorvexa.recorder.core.model.MicPreset
import com.qorvexa.recorder.core.model.MicProcessing
import com.qorvexa.recorder.core.model.OptimizationMode
import com.qorvexa.recorder.core.model.OrientationMode
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.SampleRate
import com.qorvexa.recorder.core.model.ThemeMode
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.video.BitrateCalculator
import com.qorvexa.recorder.core.video.VideoConfigResolver
import com.qorvexa.recorder.device.DeviceTester
import com.qorvexa.recorder.permissions.Permissions
import com.qorvexa.recorder.ui.AppViewModel
import com.qorvexa.recorder.ui.components.InfoNote
import com.qorvexa.recorder.ui.components.OptionPickerDialog
import com.qorvexa.recorder.ui.components.PickerOption
import com.qorvexa.recorder.ui.components.QxCard
import com.qorvexa.recorder.ui.components.QxTopBar
import com.qorvexa.recorder.ui.components.SectionTitle
import com.qorvexa.recorder.ui.components.SettingRow
import com.qorvexa.recorder.ui.components.SliderRow
import com.qorvexa.recorder.ui.components.SwitchRow
import com.qorvexa.recorder.ui.navigation.Routes
import com.qorvexa.recorder.ui.theme.LocalQx
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ScreenScaffold(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        QxTopBar(title, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Hub

@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<String?>(null) }
    val qx = LocalQx.current
    ScreenScaffold("Settings", null) {
        QxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.qorvexa_mark), null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("QORVEXA", style = MaterialTheme.typography.titleLarge)
                    Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                    Text("100% Local", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        SectionTitle("Optimization")
        QxCard {
            SettingRow(Icons.Filled.AutoAwesome, "Optimization mode", s.optimization.label) { picker = "opt" }
            preview?.let { InfoNote("Next recording: ${it.config.size} · ${it.config.fps} FPS · ${it.config.codec.label} · ${it.config.bitrate / 1_000_000.0} Mbps · ${it.engine.label} engine") }
        }
        SectionTitle("Video Settings")
        QxCard {
            SettingRow(Icons.Filled.Hd, "Resolution", s.resolution.label) { nav.navigate(Routes.VIDEO) }
            SettingRow(Icons.Filled.Speed, "Frame Rate", s.fps.label) { nav.navigate(Routes.VIDEO) }
            SettingRow(Icons.Filled.DataUsage, "Bitrate", if (s.bitrate == BitrateOption.CUSTOM) "${s.customBitrateMbps} Mbps" else s.bitrate.label) { nav.navigate(Routes.VIDEO) }
            SettingRow(Icons.Filled.Videocam, "Video Codec", s.codec.label) { nav.navigate(Routes.VIDEO) }
        }
        SectionTitle("Audio Settings")
        QxCard {
            SettingRow(Icons.Filled.Audiotrack, "Audio Source", s.audioSource.label) { nav.navigate(Routes.AUDIO) }
            SettingRow(Icons.Filled.GraphicEq, "Audio Quality", s.audioBitrate.label) { nav.navigate(Routes.AUDIO) }
            SettingRow(Icons.Filled.SurroundSound, "Channels", s.channels.label) { nav.navigate(Routes.AUDIO) }
        }
        SectionTitle("Recording")
        QxCard {
            SettingRow(Icons.Filled.Memory, "Engine Mode", s.engine.label) { nav.navigate(Routes.ENGINE) }
            SettingRow(Icons.Filled.BlurOn, "Floating Overlay", if (vm.overlay.collectAsStateWithLifecycle().value.enabled) "On" else "Off") { nav.navigate(Routes.OVERLAY) }
            SettingRow(Icons.Filled.SportsEsports, "Gaming Mode", if (s.gamingMode) s.gamingPreset.label else "Off") { nav.navigate(Routes.GAMING) }
            SettingRow(Icons.Filled.Tune, "Advanced Settings") { nav.navigate(Routes.ADVANCED) }
        }
        SectionTitle("Appearance")
        QxCard { SettingRow(Icons.Filled.DarkMode, "Theme", s.theme.label) { picker = "theme" } }
    }
    when (picker) {
        "opt" -> OptionPickerDialog(
            "Optimization mode",
            OptimizationMode.entries.map { PickerOption(it, it.label, note = optimizationNote(it)) },
            s.optimization, { vm.applyOptimization(it) }, { picker = null },
        )
        "theme" -> OptionPickerDialog("Theme", ThemeMode.entries.map { PickerOption(it, it.label) }, s.theme, { t -> vm.update { it.copy(theme = t) } }, { picker = null })
    }
}

private fun optimizationNote(m: OptimizationMode) = when (m) {
    OptimizationMode.AUTOMATIC -> "Picks settings from your hardware profile"
    OptimizationMode.BATTERY_SAVER -> "720p · 30 FPS · H.264 · Basic engine"
    OptimizationMode.BALANCED -> "1080p · 30–60 FPS"
    OptimizationMode.HIGH_PERFORMANCE -> "1080p · highest smooth FPS · HEVC when available"
    OptimizationMode.MAXIMUM_QUALITY -> "Highest native resolution · high bitrate"
    OptimizationMode.GAMING -> "Uses your Gaming Mode preset"
    OptimizationMode.CUSTOM -> "Keep your own settings"
}

// ---------------------------------------------------------------- Video

@Composable
fun VideoSettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val caps by vm.caps.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<String?>(null) }
    val c = caps
    ScreenScaffold("Video Settings", onBack) {
        if (c == null) {
            Text("Detecting hardware…"); return@ScreenScaffold
        }
        val cfg = preview?.config
        QxCard {
            SettingRow(Icons.Filled.Hd, "Resolution", if (s.resolution == ResolutionPreset.CUSTOM) "${s.customWidth}×${s.customHeight}" else s.resolution.label) { picker = "res" }
            SettingRow(Icons.Filled.Speed, "Frame rate", s.fps.label) { picker = "fps" }
            SettingRow(Icons.Filled.DataUsage, "Bitrate", if (s.bitrate == BitrateOption.CUSTOM) "${s.customBitrateMbps} Mbps" else s.bitrate.label) { picker = "bitrate" }
            SettingRow(Icons.Filled.Videocam, "Codec", s.codec.label) { picker = "codec" }
            SettingRow(Icons.Filled.Equalizer, "Bitrate mode", s.bitrateMode.label) { picker = "mode" }
            SettingRow(Icons.Filled.Key, "Keyframe interval", "${s.keyframeIntervalSec} s") { picker = "gop" }
            SettingRow(Icons.Filled.ScreenRotation, "Orientation", s.orientation.label) { picker = "orient" }
            SwitchRow(Icons.Filled.DeveloperBoard, "Prefer hardware encoder", s.preferHardwareEncoder, "Software encoding is slower and uses more battery") {
                v -> vm.update(true) { it.copy(preferHardwareEncoder = v) }
            }
            SwitchRow(Icons.Filled.HdrOn, "HDR", false, "Not supported: Android screen capture always produces SDR video", enabled = false) {}
        }
        cfg?.let {
            SectionTitle("What will be recorded")
            QxCard {
                Text("${it.size} · ${it.fps} FPS · ${it.codec.label} · ${"%.1f".format(it.bitrate / 1e6)} Mbps", style = MaterialTheme.typography.titleSmall)
                Text("Encoder: ${it.encoderName ?: "default"} (${if (it.hardware) "hardware" else "software"})", style = MaterialTheme.typography.bodySmall, color = LocalQx.current.textSoft)
                Text(
                    "Recommended bitrate: ${"%.1f".format(BitrateCalculator.recommend(it.size.width, it.size.height, it.fps, it.codec) / 1e6)} Mbps · " +
                        "≈ ${BitrateCalculator.estimateBytes(it.bitrate, s.audioBitrate.bps, 60) / (1024 * 1024)} MB per minute",
                    style = MaterialTheme.typography.bodySmall, color = LocalQx.current.textSoft,
                )
                it.notes.forEach { n -> InfoNote(n, color = LocalQx.current.amber) }
            }
        }
    }
    val display = VideoConfigResolver.orientedDisplay(c ?: return, s.orientation, false)
    val size = cfg(preview)
    when (picker) {
        "res" -> OptionPickerDialog(
            "Resolution",
            ResolutionPreset.entries.map { p ->
                val a = VideoConfigResolver.resolutionAvailability(p, c, s.codec, s.copy(resolution = p))
                val sz = VideoConfigResolver.sizeForPreset(p, display, s.customWidth, s.customHeight)
                PickerOption(p, p.label, a.available, a.reason ?: sz?.toString())
            },
            s.resolution,
            { p -> if (p == ResolutionPreset.CUSTOM) picker = "custom" else vm.update(true) { it.copy(resolution = p) } },
            { if (picker != "custom") picker = null },
        )
        "custom" -> CustomSizeDialog(s.customWidth, s.customHeight, { w, h -> vm.update(true) { it.copy(resolution = ResolutionPreset.CUSTOM, customWidth = w, customHeight = h) } }) { picker = null }
        "fps" -> OptionPickerDialog(
            "Frame rate",
            FpsOption.entries.map { f ->
                val a = VideoConfigResolver.fpsAvailability(f, size ?: display, c, s.codec)
                PickerOption(f, f.label, a.available, a.reason ?: if (f == FpsOption.AUTO) "Best smooth rate for your display (up to 60)" else null)
            },
            s.fps, { f -> vm.update(true) { it.copy(fps = f) } }, { picker = null },
        )
        "bitrate" -> OptionPickerDialog(
            "Bitrate",
            BitrateOption.entries.map { b ->
                val rec = size?.let { BitrateCalculator.recommend(it.width, it.height, s.fps.fps.takeIf { f -> f > 0 } ?: 30, s.codec) }
                PickerOption(b, b.label, note = if (b == BitrateOption.RECOMMENDED && rec != null) "%.1f Mbps for current settings".format(rec / 1e6) else null)
            },
            s.bitrate,
            { b -> if (b == BitrateOption.CUSTOM) picker = "custombitrate" else vm.update(true) { it.copy(bitrate = b) } },
            { if (picker != "custombitrate") picker = null },
        )
        "custombitrate" -> NumberDialog("Custom bitrate (Mbps)", s.customBitrateMbps, 1..200, { v -> vm.update(true) { it.copy(bitrate = BitrateOption.CUSTOM, customBitrateMbps = v) } }) { picker = null }
        "codec" -> OptionPickerDialog(
            "Video codec",
            VideoCodec.entries.map { v -> val a = VideoConfigResolver.codecAvailability(v, c); PickerOption(v, v.label, a.available, a.reason) },
            s.codec, { v -> vm.update(true) { it.copy(codec = v) } }, { picker = null },
        )
        "mode" -> OptionPickerDialog(
            "Bitrate mode",
            BitrateMode.entries.map { m ->
                val enc = c.bestEncoder(s.codec)
                val ok = enc == null || (if (m == BitrateMode.CBR) enc.supportsCbr else enc.supportsVbr)
                PickerOption(m, m.label, ok, if (!ok) "Not supported by ${enc?.name}" else null)
            },
            s.bitrateMode, { m -> vm.update(true) { it.copy(bitrateMode = m) } }, { picker = null },
        )
        "gop" -> OptionPickerDialog("Keyframe interval", listOf(1, 2, 3, 5, 10).map { PickerOption(it, "$it s", note = if (it == 1) "Best for editing and seeking" else null) }, s.keyframeIntervalSec, { v -> vm.update(true) { it.copy(keyframeIntervalSec = v) } }, { picker = null })
        "orient" -> OptionPickerDialog("Orientation", OrientationMode.entries.map { PickerOption(it, it.label, note = if (it == OrientationMode.AUTO) "Uses the screen orientation when recording starts" else null) }, s.orientation, { o -> vm.update(true) { it.copy(orientation = o) } }, { picker = null })
    }
}

private fun cfg(p: AppViewModel.Preview?) = p?.config?.size

@Composable
private fun CustomSizeDialog(w0: Int, h0: Int, onSave: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(w0.toString()) }
    var h by remember { mutableStateOf(h0.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom resolution") },
        text = {
            Column {
                OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text("Width") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text("Height") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                InfoNote("Sizes above your screen's native resolution are reduced to fit, never upscaled.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val wi = w.toIntOrNull() ?: 0; val hi = h.toIntOrNull() ?: 0
                if (wi in 144..7680 && hi in 144..7680) onSave(wi, hi)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NumberDialog(title: String, initial: Int, range: IntRange, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(v, { v = it.filter(Char::isDigit).take(6) }, label = { Text("${range.first}–${range.last}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = {
            TextButton(onClick = { v.toIntOrNull()?.takeIf { it in range }?.let(onSave); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Audio

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TesterEntryPoint {
    fun tester(): DeviceTester
}

@Composable
fun AudioSettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val caps by vm.caps.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var micTest by remember { mutableStateOf<String?>(null) }
    var micGain by remember(s.micGainPercent) { mutableFloatStateOf(s.micGainPercent.toFloat()) }
    var internalVol by remember(s.internalVolumePercent) { mutableFloatStateOf(s.internalVolumePercent.toFloat()) }
    val qx = LocalQx.current
    val internalOk = caps?.playbackCaptureSupported == true
    ScreenScaffold("Audio Settings", onBack) {
        QxCard {
            SettingRow(Icons.Filled.Audiotrack, "Audio source", s.audioSource.label) { picker = "source" }
            SettingRow(Icons.Filled.GraphicEq, "Audio bitrate", s.audioBitrate.label) { picker = "bitrate" }
            SettingRow(Icons.Filled.Equalizer, "Sample rate", s.sampleRate.label) { picker = "rate" }
            SettingRow(Icons.Filled.SurroundSound, "Channels", s.channels.label) { picker = "channels" }
            if (!Permissions.micGranted(ctx) && s.audioSource != AudioSource.NONE) {
                InfoNote("Microphone permission will be requested when you start recording. Android requires it for internal audio too.", color = qx.amber)
            }
            if (!internalOk) InfoNote("Internal audio capture needs Android 10 or newer. On this device only the microphone can be recorded.")
            if (internalOk && s.audioSource.usesInternal) InfoNote("Some apps block internal audio capture (e.g. calls and DRM-protected media); those sounds will be silent.")
        }
        if (s.audioSource.usesMic) {
            SectionTitle("Microphone")
            QxCard {
                SettingRow(Icons.Filled.Headset, "Input device", caps?.inputDevices?.firstOrNull { it.id == s.micDeviceId }?.name ?: "System default") { picker = "device" }
                SettingRow(Icons.Filled.RecordVoiceOver, "Preset", s.micPreset.label) { picker = "preset" }
                val fx = caps?.effects
                SwitchRow(Icons.Filled.Hearing, "Noise suppression", s.micProcessing.noiseSuppression, if (fx?.noiseSuppressor == false) "Not supported on this device" else null, enabled = fx?.noiseSuppressor != false) { v ->
                    vm.update { it.copy(micPreset = MicPreset.CUSTOM, micProcessing = it.micProcessing.copy(noiseSuppression = v)) }
                }
                SwitchRow(Icons.Filled.VolumeUp, "Echo cancellation", s.micProcessing.echoCancellation, if (fx?.echoCanceler == false) "Not supported on this device" else null, enabled = fx?.echoCanceler != false) { v ->
                    vm.update { it.copy(micPreset = MicPreset.CUSTOM, micProcessing = it.micProcessing.copy(echoCancellation = v)) }
                }
                SwitchRow(Icons.Filled.AutoAwesome, "Automatic gain control", s.micProcessing.autoGain, if (fx?.autoGain == false) "Not supported on this device" else null, enabled = fx?.autoGain != false) { v ->
                    vm.update { it.copy(micPreset = MicPreset.CUSTOM, micProcessing = it.micProcessing.copy(autoGain = v)) }
                }
                SwitchRow(Icons.Filled.RecordVoiceOver, "Voice enhancement", s.micProcessing.voiceEnhancement, "Removes low rumble and handling noise (100 Hz high-pass)") { v ->
                    vm.update { it.copy(micPreset = MicPreset.CUSTOM, micProcessing = it.micProcessing.copy(voiceEnhancement = v)) }
                }
                SliderRow("Microphone gain", micGain, 0f..200f, "${micGain.toInt()}%", onChange = { micGain = it }, onDone = { vm.update { it.copy(micGainPercent = micGain.toInt()) } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        micTest = "Listening…"
                        scope.launch {
                            val tester = EntryPointAccessors.fromApplication(ctx.applicationContext, TesterEntryPoint::class.java).tester()
                            val (db, err) = withContext(Dispatchers.IO) { tester.micLevel() }
                            micTest = err ?: db?.let { level ->
                                "Peak level: ${"%.0f".format(level)} dBFS " + when {
                                    level < -50 -> "(very quiet — check the mic)"
                                    level < -30 -> "(quiet)"
                                    level < -6 -> "(good)"
                                    else -> "(loud — lower the gain)"
                                }
                            } ?: "No reading"
                        }
                    }) { Text("Test microphone") }
                    micTest?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = qx.textSoft) }
                }
            }
        }
        if (s.audioSource == AudioSource.INTERNAL_AND_MIC || s.audioSource == AudioSource.INTERNAL) {
            SectionTitle("Mixing")
            QxCard {
                SliderRow("Internal audio volume", internalVol, 0f..150f, "${internalVol.toInt()}%", onChange = { internalVol = it }, onDone = { vm.update { it.copy(internalVolumePercent = internalVol.toInt()) } })
                InfoNote("Microphone and internal audio are mixed with a soft limiter so loud moments don't clip.")
            }
        }
    }
    when (picker) {
        "source" -> OptionPickerDialog(
            "Audio source",
            AudioSource.entries.map { a ->
                val ok = !a.usesInternal || internalOk
                PickerOption(a, a.label, ok, if (!ok) "Needs Android 10+" else null)
            },
            s.audioSource, { a -> vm.update { it.copy(audioSource = a) } }, { picker = null },
        )
        "bitrate" -> OptionPickerDialog("Audio bitrate", AudioBitrate.entries.map { PickerOption(it, it.label) }, s.audioBitrate, { b -> vm.update { it.copy(audioBitrate = b) } }, { picker = null })
        "rate" -> OptionPickerDialog("Sample rate", SampleRate.entries.map { PickerOption(it, it.label, note = if (it == SampleRate.HZ_48000) "Standard for video" else null) }, s.sampleRate, { r -> vm.update { it.copy(sampleRate = r) } }, { picker = null })
        "channels" -> OptionPickerDialog(
            "Channels",
            ChannelMode.entries.map { ch ->
                PickerOption(
                    ch, ch.label,
                    note = when (ch) {
                        ChannelMode.AUTO -> "Stereo for internal audio, mono for microphone only"
                        ChannelMode.STEREO -> if (s.audioSource == AudioSource.MIC && caps?.stereoMic != true) "Your microphone is mono; both channels will be identical" else null
                        ChannelMode.MONO -> null
                    },
                )
            },
            s.channels, { ch -> vm.update { it.copy(channels = ch) } }, { picker = null },
        )
        "device" -> OptionPickerDialog(
            "Input device",
            listOf(PickerOption(0, "System default")) + (caps?.inputDevices ?: emptyList()).map { PickerOption(it.id, it.name, note = it.type + if (it.stereo) " · stereo" else "") },
            s.micDeviceId, { id -> vm.update { it.copy(micDeviceId = id) } }, { picker = null },
        )
        "preset" -> OptionPickerDialog(
            "Microphone preset",
            MicPreset.entries.map { PickerOption(it, it.label) },
            s.micPreset, { p -> vm.update { it.copy(micPreset = p, micProcessing = MicProcessing.forPreset(p, it.micProcessing)) } }, { picker = null },
        )
    }
}
