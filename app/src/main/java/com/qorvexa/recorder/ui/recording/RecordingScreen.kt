package com.qorvexa.recorder.ui.recording

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qorvexa.recorder.core.recording.RecordingState
import com.qorvexa.recorder.permissions.Permissions
import com.qorvexa.recorder.ui.AppViewModel
import com.qorvexa.recorder.ui.components.Dot
import com.qorvexa.recorder.ui.components.InfoNote
import com.qorvexa.recorder.ui.components.KeyValueRow
import com.qorvexa.recorder.ui.components.QxCard
import com.qorvexa.recorder.ui.components.QxTopBar
import com.qorvexa.recorder.ui.components.formatBytes
import com.qorvexa.recorder.ui.components.formatDuration
import com.qorvexa.recorder.ui.theme.LocalQx
import com.qorvexa.recorder.ui.theme.Mono

/** Screen 2: live recording controls inside the app. */
@Composable
fun RecordingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val qx = LocalQx.current
    val st = status.state
    LaunchedEffect(st) {
        if (st == RecordingState.COMPLETED || st == RecordingState.IDLE || st == RecordingState.ERROR) onBack()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        QxTopBar("Recording", onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = qx.red.copy(alpha = if (st == RecordingState.PAUSED) 0.4f else 1f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(Color.White, 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (st == RecordingState.PAUSED) "PAUSED" else "REC", color = Color.White, style = Mono)
                        Spacer(Modifier.width(10.dp))
                        Text(formatDuration(status.elapsedMs), color = Color.White, style = Mono, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                status.config?.let {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("${it.size.shortSide}P · ${it.fps}FPS", style = Mono, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            if (st == RecordingState.COUNTDOWN) {
                Text("Starting in ${status.countdown}…", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
            }

            Spacer(Modifier.height(20.dp))
            QxCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (st == RecordingState.PAUSED) {
                        ControlButton(Icons.Filled.PlayArrow, "Resume", MaterialTheme.colorScheme.primary) { vm.resume(ctx) }
                    } else {
                        ControlButton(Icons.Filled.Pause, "Pause", MaterialTheme.colorScheme.primary, enabled = st == RecordingState.RECORDING) { vm.pause(ctx) }
                    }
                    ControlButton(Icons.Filled.Stop, "Stop", qx.red) { vm.stopRecording(ctx) }
                    ControlButton(Icons.Filled.PhotoCamera, "Screenshot", qx.cyan, enabled = st == RecordingState.RECORDING || st == RecordingState.PAUSED) { vm.screenshot(ctx) }
                    ControlButton(Icons.Filled.Brush, "Draw", qx.amber) {
                        // Drawing happens in the floating overlay so it works over any app.
                        if (Permissions.canDrawOverlays(ctx)) {
                            vm.setOverlayEnabled(true)
                            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            ctx.startActivity(Permissions.overlaySettingsIntent(ctx))
                        }
                    }
                }
                if (!overlay.enabled) {
                    InfoNote("Drawing tools live in the floating button. Tap Draw to turn it on, then use the pen icon in its menu.")
                }
            }

            Spacer(Modifier.height(12.dp))
            QxCard {
                Text("Microphone level", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val level = ((status.micLevelDb + 60f) / 60f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { level }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = if (level > 0.9f) qx.red else qx.green, trackColor = MaterialTheme.colorScheme.outline,
                )
                status.effects?.let { e ->
                    Text(
                        listOfNotNull(
                            "Noise suppression".takeIf { e.noiseSuppressor },
                            "Echo cancellation".takeIf { e.echoCanceler },
                            "Auto gain".takeIf { e.autoGain },
                            "Voice enhancement".takeIf { e.voiceEnhance },
                        ).ifEmpty { listOf("No processing active") }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            QxCard {
                Text("Session", style = MaterialTheme.typography.titleSmall)
                KeyValueRow("Engine", status.engine?.label ?: "—")
                KeyValueRow("Encoder", status.encoderName ?: "—")
                status.config?.let {
                    KeyValueRow("Video", "${it.size} · ${it.codec.label} · ${it.bitrate / 1_000_000.0} Mbps")
                    KeyValueRow("Acceleration", if (it.hardware) "Hardware" else "Software")
                }
                KeyValueRow("File size so far", formatBytes(status.bytesWritten))
            }
            if (status.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                QxCard(accent = qx.amber) {
                    status.warnings.forEach { InfoNote(it, color = qx.amber) }
                    TextButton(onClick = vm::dismissMessages) { Text("Dismiss") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp),
            color = tint.copy(alpha = if (enabled) 0.18f else 0.06f), modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(30.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}
