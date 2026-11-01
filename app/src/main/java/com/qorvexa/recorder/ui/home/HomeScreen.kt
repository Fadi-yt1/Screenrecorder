package com.qorvexa.recorder.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.qorvexa.recorder.R
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.recording.RecordingState
import com.qorvexa.recorder.storage.MediaKind
import com.qorvexa.recorder.ui.AppViewModel
import com.qorvexa.recorder.ui.components.QxCard
import com.qorvexa.recorder.ui.components.formatBytes
import com.qorvexa.recorder.ui.components.formatDuration
import com.qorvexa.recorder.ui.library.LibraryViewModel
import com.qorvexa.recorder.ui.library.MediaThumb
import com.qorvexa.recorder.ui.navigation.Routes
import com.qorvexa.recorder.ui.navigation.navigateTab
import com.qorvexa.recorder.ui.theme.BrandTitle
import com.qorvexa.recorder.ui.theme.LocalQx
import com.qorvexa.recorder.ui.theme.Mono
import com.qorvexa.recorder.ui.theme.QxColors

@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val caps by vm.caps.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val library by vm.libraryItems.collectAsStateWithLifecycle()
    val libVm: LibraryViewModel = hiltViewModel()
    val qx = LocalQx.current
    val active = status.state.isActive && status.state != RecordingState.REQUESTING_PERMISSION

    Column(
        Modifier.fillMaxSize().background(heroBrush(qx.isDark)).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screenshot(ctx) }) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Take screenshot") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { nav.navigateTab(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        Image(painterResource(R.drawable.qorvexa_mark), contentDescription = null, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)))
        Spacer(Modifier.height(8.dp))
        Text("QORVEXA", style = BrandTitle, color = MaterialTheme.colorScheme.onBackground)
        Text("S C R E E N   R E C O R D E R", style = MaterialTheme.typography.labelMedium, color = qx.textSoft, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Text("Capture Every Frame.\nExperience Every Detail.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.height(24.dp))
        RecordButton(
            recording = active,
            paused = status.state == RecordingState.PAUSED,
            onClick = {
                if (active) nav.navigate(Routes.RECORDING) else vm.startRecording(ctx)
            },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                status.state == RecordingState.COUNTDOWN -> "Starting in ${status.countdown}…"
                status.state == RecordingState.PAUSED -> "Paused · ${formatDuration(status.elapsedMs)} · tap for controls"
                active -> "Recording · ${formatDuration(status.elapsedMs)} · tap for controls"
                status.state == RecordingState.REQUESTING_PERMISSION -> "Waiting for screen capture permission…"
                else -> "Tap to Start Recording"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (active) qx.red else MaterialTheme.colorScheme.onBackground,
        )

        Messages(status.error, status.warnings, onDismiss = vm::dismissMessages)

        Spacer(Modifier.height(18.dp))
        val cfg = preview?.config
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Icons.Filled.Hd, cfg?.let { "${it.size.shortSide}P" } ?: settings.resolution.label, "Resolution", Modifier.weight(1f)) { nav.navigate(Routes.VIDEO) }
            StatTile(Icons.Filled.Speed, cfg?.let { "${it.fps} FPS" } ?: settings.fps.label, "Frame Rate", Modifier.weight(1f)) { nav.navigate(Routes.VIDEO) }
            StatTile(
                if (settings.audioSource == AudioSource.NONE) Icons.Filled.MicOff else Icons.Filled.Mic,
                when (settings.audioSource) {
                    AudioSource.NONE -> "Off"; AudioSource.MIC -> "Mic"; AudioSource.INTERNAL -> "Internal"; AudioSource.INTERNAL_AND_MIC -> "Both"
                },
                "Audio", Modifier.weight(1f),
            ) { nav.navigate(Routes.AUDIO) }
        }
        cfg?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
            Text(notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = qx.amber, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        Spacer(Modifier.height(12.dp))
        QxCard(onClick = { nav.navigate(Routes.GAMING) }, accent = qx.amber) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = qx.amber, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Gaming Mode", style = MaterialTheme.typography.titleMedium, color = qx.amber)
                    Text(if (settings.gamingMode) settings.gamingPreset.label else "High performance recording", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
                }
                Switch(
                    checked = settings.gamingMode, onCheckedChange = { vm.setGamingMode(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = qx.amber),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        caps?.let { c ->
            QxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SdStorage, contentDescription = null, tint = qx.green)
                    Spacer(Modifier.width(12.dp))
                    Text("Storage Available", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${formatBytes(c.storageFreeBytes)} / ${formatBytes(c.storageTotalBytes)}", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
                }
                Spacer(Modifier.height(10.dp))
                val used = if (c.storageTotalBytes > 0) 1f - c.storageFreeBytes.toFloat() / c.storageTotalBytes else 0f
                LinearProgressIndicator(
                    progress = { used }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (used > 0.9f) qx.red else qx.green, trackColor = MaterialTheme.colorScheme.outline,
                )
                cfg?.let {
                    val perMin = (it.bitrate.toLong() + if (settings.audioSource == AudioSource.NONE) 0 else settings.audioBitrate.bps) / 8 * 60
                    if (perMin > 0) {
                        Text(
                            "≈ ${formatBytes(perMin)} per minute · about ${c.storageFreeBytes / perMin / 60} h of recording space",
                            style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QxCard(Modifier.weight(1f), onClick = { nav.navigateTab(Routes.DEVICE) }) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Device profile", style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 6.dp))
                Text(preview?.profile?.tier?.label ?: "Detecting…", style = MaterialTheme.typography.titleSmall)
            }
            QxCard(Modifier.weight(1f), onClick = { nav.navigate(Routes.ENGINE) }) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = qx.cyan)
                Text("Recording engine", style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 6.dp))
                Text(
                    (status.engine.takeIf { active } ?: preview?.engine)?.label ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        val recent = library.filter { it.media.kind == MediaKind.VIDEO }.take(8)
        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent recordings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.navigateTab(Routes.RECORDINGS) }) { Text("See all") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                items(recent, key = { it.media.uri.toString() }) { item ->
                    Column(Modifier.width(150.dp).clickable { nav.navigate(Routes.player(item.media.uri)) }) {
                        MediaThumb(item, libVm.thumbnails, Modifier.fillMaxWidth().height(90.dp))
                        Text(item.media.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun heroBrush(dark: Boolean) = if (dark) {
    Brush.verticalGradient(listOf(Color(0xFF1A0F3A), QxColors.Graphite, QxColors.Graphite), endY = 1400f)
} else {
    Brush.verticalGradient(listOf(Color(0xFFEDE4FF), Color(0xFFF6F5FB)), endY = 1400f)
}

@Composable
private fun RecordButton(recording: Boolean, paused: Boolean, onClick: () -> Unit) {
    val qx = LocalQx.current
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, if (recording && !paused) 1.06f else 1.02f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
    Box(
        Modifier.size(188.dp).semantics { contentDescription = if (recording) "Open recording controls" else "Start recording" }
            .clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().scale(scale)) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(qx.red.copy(alpha = 0.35f), Color.Transparent), center, r), r)
            drawCircle(
                Brush.sweepGradient(listOf(QxColors.Purple, QxColors.Cyan, QxColors.Purple), center),
                r * 0.86f, style = Stroke(width = r * 0.05f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFF5A6E), qx.red, Color(0xFFB0001A)), center + Offset(-r * 0.15f, -r * 0.2f), r * 0.9f),
                r * 0.66f,
            )
        }
        Icon(
            Icons.Filled.Videocam, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(56.dp),
        )
        if (recording) {
            Text(if (paused) "PAUSED" else "● REC", color = Color.White, style = Mono, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp))
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val qx = LocalQx.current
    QxCard(modifier, onClick = onClick) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = qx.cyan)
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
        }
    }
}

@Composable
private fun Messages(error: String?, warnings: List<String>, onDismiss: () -> Unit) {
    if (error == null && warnings.isEmpty()) return
    val qx = LocalQx.current
    Spacer(Modifier.height(12.dp))
    QxCard(accent = if (error != null) qx.red else qx.amber) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = if (error != null) qx.red else qx.amber)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = qx.red) }
                warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(top = 2.dp)) }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
}
