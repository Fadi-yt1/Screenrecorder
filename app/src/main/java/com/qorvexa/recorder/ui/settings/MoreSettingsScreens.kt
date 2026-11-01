package com.qorvexa.recorder.ui.settings

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AlignHorizontalLeft
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qorvexa.recorder.R
import com.qorvexa.recorder.core.engine.EngineSelector
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.FileNamePattern
import com.qorvexa.recorder.core.model.GamingPreset
import com.qorvexa.recorder.core.model.OrientationMode
import com.qorvexa.recorder.core.storage.FileNaming
import com.qorvexa.recorder.permissions.Permissions
import com.qorvexa.recorder.settings.BubbleIcon
import com.qorvexa.recorder.settings.BubbleShape
import com.qorvexa.recorder.settings.BubbleSide
import com.qorvexa.recorder.ui.AppViewModel
import com.qorvexa.recorder.ui.components.InfoNote
import com.qorvexa.recorder.ui.components.KeyValueRow
import com.qorvexa.recorder.ui.components.OptionPickerDialog
import com.qorvexa.recorder.ui.components.PickerOption
import com.qorvexa.recorder.ui.components.QxCard
import com.qorvexa.recorder.ui.components.SectionTitle
import com.qorvexa.recorder.ui.components.SettingRow
import com.qorvexa.recorder.ui.components.SliderRow
import com.qorvexa.recorder.ui.components.StatusChip
import com.qorvexa.recorder.ui.components.SwitchRow
import com.qorvexa.recorder.ui.theme.LocalQx
import com.qorvexa.recorder.ui.theme.QxColors
import java.time.LocalDateTime

// ---------------------------------------------------------------- Engine

@Composable
fun EngineScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val caps by vm.caps.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val qx = LocalQx.current
    ScreenScaffold("Recording Engine", onBack) {
        val c = caps ?: run { Text("Detecting hardware…"); return@ScreenScaffold }
        QxCard {
            KeyValueRow("Active / next engine", (status.engine.takeIf { status.state.isActive } ?: preview?.engine)?.label ?: "—")
            KeyValueRow("Hardware acceleration", if (preview?.config?.hardware == true) "On (${preview?.config?.encoderName})" else "Off (software encoder)")
            preview?.config?.let { KeyValueRow("Capture configuration", "${it.size} · ${it.fps} FPS · ${it.codec.label}") }
            InfoNote("All engines capture with MediaProjection and encode with MediaCodec. They differ in what happens between the screen and the encoder.")
        }
        SectionTitle("Engines")
        EngineSelector.compatibility(c).forEach { e ->
            val selected = s.engine == e.engine
            Surface(
                onClick = { if (e.selectable) vm.update { it.copy(engine = e.engine) } },
                enabled = e.selectable,
                shape = RoundedCornerShape(18.dp),
                color = qx.card,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else qx.cardBorder),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected = selected, onClick = null, enabled = e.selectable)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.engine.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(e.status)
                        }
                        if (e.engine != EngineType.VULKAN) Text(e.engine.description, style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 4.dp))
                        Text(e.detail, style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        InfoNote("If an engine fails to start, QORVEXA falls back automatically: OpenGL → Advanced → Basic, and tells you it did.")
        if (s.watermark) InfoNote("Watermark is on, so the OpenGL engine is used (only it can draw into the video).", color = qx.amber)
    }
}

// ---------------------------------------------------------------- Overlay

@Composable
fun OverlaySettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val o by vm.overlay.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val canDraw = Permissions.canDrawOverlays(ctx)
    var bubbleSize by remember(o.sizeDp) { mutableFloatStateOf(o.sizeDp.toFloat()) }
    var icon by remember(o.iconScalePercent) { mutableFloatStateOf(o.iconScalePercent.toFloat()) }
    var opacity by remember(o.opacityPercent) { mutableFloatStateOf(o.opacityPercent.toFloat()) }
    val qx = LocalQx.current
    ScreenScaffold("Floating Overlay", onBack) {
        // Live preview
        Box(
            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1A0F3A), Color(0xFF0B2A4A), QxColors.Graphite))),
            contentAlignment = if (o.side == BubbleSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            val shape = if (o.shape == BubbleShape.CIRCLE) CircleShape else RoundedCornerShape((bubbleSize * 0.28f).dp)
            Box(
                Modifier.padding(16.dp).size(bubbleSize.dp).alpha(opacity / 100f).clip(shape).background(Color(o.backgroundColor))
                    .border(3.dp, QxColors.Purple, shape),
                contentAlignment = Alignment.Center,
            ) {
                val iconDp = (bubbleSize * icon / 100f).dp
                when (o.icon) {
                    BubbleIcon.LOGO -> Image(painterResource(R.drawable.qorvexa_mark), null, Modifier.size(iconDp).clip(RoundedCornerShape(iconDp * 0.2f)))
                    BubbleIcon.RECORD -> Canvas(Modifier.size(iconDp)) { drawCircle(QxColors.Red, size.minDimension * 0.38f) }
                    BubbleIcon.CAMERA -> androidx.compose.material3.Icon(painterResource(R.drawable.ic_notification), null, tint = Color(o.iconColor), modifier = Modifier.size(iconDp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        QxCard {
            SwitchRow(Icons.Filled.BlurOn, "Show floating button", o.enabled && canDraw, if (!canDraw) "Needs “Display over other apps” permission" else "Record from any app") { on ->
                if (on && !canDraw) ctx.startActivity(Permissions.overlaySettingsIntent(ctx)) else vm.setOverlayEnabled(on)
            }
        }
        SectionTitle("Appearance")
        QxCard {
            SliderRow("Size", bubbleSize, 40f..88f, "${bubbleSize.toInt()} dp", onChange = { bubbleSize = it }, onDone = { vm.updateOverlay { it.copy(sizeDp = bubbleSize.toInt()) } })
            SliderRow("Icon size", icon, 30f..90f, "${icon.toInt()}%", onChange = { icon = it }, onDone = { vm.updateOverlay { it.copy(iconScalePercent = icon.toInt()) } })
            SliderRow("Opacity", opacity, 10f..100f, "${opacity.toInt()}%", onChange = { opacity = it }, onDone = { vm.updateOverlay { it.copy(opacityPercent = opacity.toInt()) } })
            Text("Shape", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            ChoiceRow(BubbleShape.entries, o.shape, { if (it == BubbleShape.CIRCLE) "Circle" else "Rounded square" }) { v -> vm.updateOverlay { it.copy(shape = v) } }
            Text("Icon", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            ChoiceRow(BubbleIcon.entries, o.icon, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> vm.updateOverlay { it.copy(icon = v) } }
            Text("Background color", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            ColorRow(listOf(0xFF151325, 0xFF000000, 0xFF2A1758, 0xFF0B2A4A, 0xFFFF2D46, 0xFFFFFFFF).map { it.toInt() }, o.backgroundColor) { c -> vm.updateOverlay { it.copy(backgroundColor = c) } }
            Text("Icon color", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            ColorRow(listOf(0xFFFFFFFF, 0xFF22E4FF, 0xFF8B3DFF, 0xFFFFC23D, 0xFF000000).map { it.toInt() }, o.iconColor) { c -> vm.updateOverlay { it.copy(iconColor = c) } }
        }
        SectionTitle("Behavior")
        QxCard {
            Text("Position", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
            ChoiceRow(BubbleSide.entries, o.side, { if (it == BubbleSide.LEFT) "Left" else "Right" }) { v -> vm.updateOverlay { it.copy(side = v) } }
            SwitchRow(Icons.Filled.AlignHorizontalLeft, "Snap to edge", o.snapToEdge) { v -> vm.updateOverlay { it.copy(snapToEdge = v) } }
            SwitchRow(Icons.Filled.Animation, "Animations", o.animate) { v -> vm.updateOverlay { it.copy(animate = v) } }
            SwitchRow(Icons.Filled.VisibilityOff, "Auto-hide", o.autoHide, "Tucks half off-screen after 3 seconds") { v -> vm.updateOverlay { it.copy(autoHide = v) } }
            val s by vm.settings.collectAsStateWithLifecycle()
            SwitchRow(Icons.Filled.VisibilityOff, "Hide during recording", s.hideOverlayWhileRecording, "Use the notification to pause or stop") { v -> vm.update { it.copy(hideOverlayWhileRecording = v) } }
        }
        InfoNote("Like everything on screen, the floating button and drawing toolbar appear in recordings. Turn on “Hide during recording” or close the toolbar to keep them out.")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.updateOverlay { com.qorvexa.recorder.settings.OverlaySettings(enabled = it.enabled) } }, modifier = Modifier.fillMaxWidth()) { Text("Reset to default") }
        if (o.enabled) Text("Changes apply to the floating button immediately.", style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Surface(
                onClick = { onSelect(o) }, shape = RoundedCornerShape(50),
                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(label(o), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ColorRow(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) QxColors.Cyan else Color.Gray.copy(alpha = 0.4f), CircleShape)
                    .clickable { onSelect(c) },
            )
        }
    }
}

// ---------------------------------------------------------------- Gaming

@Composable
fun GamingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val caps by vm.caps.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val qx = LocalQx.current
    ScreenScaffold("Gaming Mode", onBack) {
        Box(
            Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A0F6A), Color(0xFF0B2A4A), Color(0xFF6A0F3A)))),
        ) {
            Column(Modifier.padding(18.dp).align(Alignment.BottomStart)) {
                Text("Record Your\nGaming Moments", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("High FPS · Low latency · Smooth capture", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
            androidx.compose.material3.Icon(Icons.Filled.SportsEsports, null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(110.dp).align(Alignment.CenterEnd).padding(end = 12.dp))
        }
        SectionTitle("Presets")
        GamingPreset.entries.forEach { p ->
            val selected = s.gamingMode && s.gamingPreset == p
            Surface(
                onClick = { vm.applyGamingPreset(p) },
                shape = RoundedCornerShape(16.dp), color = qx.card,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else qx.cardBorder),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(p.label, style = MaterialTheme.typography.titleSmall)
                        Text(p.description, style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
                    }
                }
            }
        }
        preview?.takeIf { s.gamingMode }?.let {
            InfoNote("On this device: ${it.config.size} · ${it.config.fps} FPS · ${it.config.codec.label}. Frame rates above your display's refresh rate are never offered.")
        }
        SectionTitle("Performance settings")
        QxCard {
            SettingRow(Icons.Filled.Speed, "FPS", preview?.config?.fps?.let { "$it FPS" } ?: s.fps.label) { }
            SettingRow(Icons.Filled.Mic, "Audio", s.audioSource.label) {
                val next = if (caps?.playbackCaptureSupported == true) AudioSource.INTERNAL_AND_MIC else AudioSource.MIC
                vm.update { it.copy(audioSource = if (it.audioSource == next) AudioSource.INTERNAL else next) }
            }
            val showTouches = Permissions.showTouchesEnabled(ctx)
            SwitchRow(Icons.Filled.TouchApp, "Touch indicator", showTouches, "Uses Android's “Show taps” developer option (apps can't turn it on themselves)") {
                runCatching { ctx.startActivity(Permissions.developerOptionsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        SectionTitle("Performance monitor")
        caps?.let { c ->
            QxCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Monitor("${c.maxRefreshRate.toInt()} Hz", "Display")
                    Monitor(c.thermalStatus.label, "Thermal")
                    Monitor(if (c.batteryPercent >= 0) "${c.batteryPercent}%" else "—", if (c.charging) "Charging" else "Battery")
                }
                InfoNote("Live values update while the app is open; during recording, heat and battery are watched by the recorder and you're warned via notification.")
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.setGamingMode(!s.gamingMode) }, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (s.gamingMode) qx.red else MaterialTheme.colorScheme.primary),
        ) { Text(if (s.gamingMode) "Turn off Gaming Mode" else "Apply Gaming Mode") }
        if (s.gamingMode) {
            OutlinedButton(onClick = { vm.startRecording(ctx) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text("Start Gaming Record") }
        }
    }
}

@Composable
private fun Monitor(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = LocalQx.current.green)
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalQx.current.textSoft)
    }
}

// ---------------------------------------------------------------- Advanced

@Composable
fun AdvancedSettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val qx = LocalQx.current
    ScreenScaffold("Advanced Settings", onBack) {
        SectionTitle("Recording")
        QxCard {
            SettingRow(Icons.Filled.Timer, "Countdown", if (s.countdownSec == 0) "Off" else "${s.countdownSec} s") { picker = "countdown" }
            SwitchRow(Icons.Filled.TouchApp, "Touch indicators", Permissions.showTouchesEnabled(ctx), "Opens Developer options → “Show taps”. Android doesn't let apps switch it on.") {
                runCatching { ctx.startActivity(Permissions.developerOptionsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            SwitchRow(Icons.Filled.BrandingWatermark, "Watermark", s.watermark, "Adds “QORVEXA” in the corner (uses the OpenGL engine)") { v -> vm.update { it.copy(watermark = v) } }
            SettingRow(Icons.Filled.ScreenLockPortrait, "Orientation lock", s.orientation.label) { picker = "orient" }
            SwitchRow(Icons.Filled.Save, "Keep screen awake", s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
            SwitchRow(Icons.Filled.Vibration, "Shake to start", s.shakeToStart, "Works while the floating button is on") { v -> vm.update { it.copy(shakeToStart = v) } }
        }
        SectionTitle("Auto-stop")
        QxCard {
            SettingRow(Icons.Filled.TimerOff, "Maximum duration", if (s.maxDurationMin == 0) "Unlimited" else "${s.maxDurationMin} min") { picker = "duration" }
            SettingRow(Icons.Filled.FileDownload, "Maximum file size", if (s.maxFileSizeMb == 0) "Unlimited" else "${s.maxFileSizeMb} MB") { picker = "size" }
            SwitchRow(Icons.Filled.SdStorage, "Low-storage protection", s.lowStorageProtection, "Stops safely before storage runs out") { v -> vm.update { it.copy(lowStorageProtection = v) } }
            if (s.lowStorageProtection) SettingRow(Icons.Filled.SdStorage, "Keep free", "${s.minFreeStorageMb} MB") { picker = "minfree" }
            SwitchRow(Icons.Filled.BatteryAlert, "Battery protection", s.batteryProtection, "Stops when battery is low and not charging") { v -> vm.update { it.copy(batteryProtection = v) } }
            if (s.batteryProtection) SettingRow(Icons.Filled.BatteryFull, "Stop at", "${s.batteryStopPercent}%") { picker = "battery" }
        }
        SectionTitle("Notifications & files")
        QxCard {
            SwitchRow(Icons.Filled.Notifications, "Controls in notification", s.notificationControls, "Pause, resume, stop and screenshot") { v -> vm.update { it.copy(notificationControls = v) } }
            SettingRow(Icons.Filled.Edit, "File name", s.fileNamePattern, FileNaming.expand(s.fileNamePattern, 1, LocalDateTime.now(), "1080p", 60) + ".mp4") { picker = "name" }
            SwitchRow(Icons.Filled.Save, "Remember last configuration", s.rememberLastConfig, "Off: start from the recommended settings each launch") { v -> vm.update { it.copy(rememberLastConfig = v) } }
            InfoNote("Recordings are saved to Movies/ScreenRecorder/<year>/<month>. Screenshots go to Pictures/ScreenRecorder.")
        }
        SectionTitle("Reset")
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Icon(Icons.Filled.RestartAlt, null)
            Spacer(Modifier.width(8.dp))
            Text("Reset all settings")
        }
        Text("Thermal status is watched during recording; you get a warning and a suggestion, but settings are never lowered silently.", style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 8.dp))
    }
    when (picker) {
        "countdown" -> OptionPickerDialog("Countdown", listOf(0, 3, 5, 10).map { PickerOption(it, if (it == 0) "Off" else "$it seconds") }, s.countdownSec, { v -> vm.update { it.copy(countdownSec = v) } }, { picker = null })
        "orient" -> OptionPickerDialog("Orientation lock", OrientationMode.entries.map { PickerOption(it, it.label) }, s.orientation, { v -> vm.update { it.copy(orientation = v) } }, { picker = null })
        "duration" -> OptionPickerDialog("Maximum duration", listOf(0, 1, 5, 10, 15, 30, 60, 120).map { PickerOption(it, if (it == 0) "Unlimited" else "$it minutes") }, s.maxDurationMin, { v -> vm.update { it.copy(maxDurationMin = v) } }, { picker = null })
        "size" -> OptionPickerDialog("Maximum file size", listOf(0, 100, 250, 500, 1024, 2048, 4096).map { PickerOption(it, if (it == 0) "Unlimited" else if (it >= 1024) "${it / 1024} GB" else "$it MB") }, s.maxFileSizeMb, { v -> vm.update { it.copy(maxFileSizeMb = v) } }, { picker = null })
        "minfree" -> OptionPickerDialog("Keep free", listOf(200, 500, 1024, 2048).map { PickerOption(it, if (it >= 1024) "${it / 1024} GB" else "$it MB") }, s.minFreeStorageMb, { v -> vm.update { it.copy(minFreeStorageMb = v) } }, { picker = null })
        "battery" -> OptionPickerDialog("Stop at battery level", listOf(5, 10, 15, 20, 30).map { PickerOption(it, "$it%") }, s.batteryStopPercent, { v -> vm.update { it.copy(batteryStopPercent = v) } }, { picker = null })
        "name" -> FileNameDialog(s.fileNamePattern, { p -> vm.update { it.copy(fileNamePattern = p) } }) { picker = null }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all settings?") },
            text = { Text("Video, audio, overlay and advanced settings go back to the recommended values for this device. Your recordings are not touched.") },
            confirmButton = { TextButton(onClick = { vm.resetSettings(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileNameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File name pattern") },
        text = {
            Column {
                OutlinedTextField(v, { v = it.take(80) }, singleLine = true)
                Text("Tokens: ${FileNamePattern.TOKENS.joinToString(" ")}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Text("Preview: " + FileNaming.expand(v, 1, LocalDateTime.now(), "1080p", 60) + ".mp4", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(v.ifBlank { FileNamePattern.DEFAULT }); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
