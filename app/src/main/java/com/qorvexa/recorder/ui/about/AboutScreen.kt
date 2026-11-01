package com.qorvexa.recorder.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qorvexa.recorder.BuildConfig
import com.qorvexa.recorder.R
import com.qorvexa.recorder.ui.components.KeyValueRow
import com.qorvexa.recorder.ui.components.QxCard
import com.qorvexa.recorder.ui.components.SectionTitle
import com.qorvexa.recorder.ui.theme.LocalQx

@Composable
fun AboutScreen() {
    val qx = LocalQx.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Image(painterResource(R.drawable.qorvexa_logo), contentDescription = "QORVEXA logo", modifier = Modifier.size(200.dp).clip(RoundedCornerShape(36.dp)))
        Spacer(Modifier.height(12.dp))
        Text("Capture Every Frame. Experience Every Detail.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
        SectionTitle("Privacy", Modifier.fillMaxWidth())
        QxCard {
            Text(
                "Everything stays on your device. No account, no cloud upload, no analytics and no internet permission. " +
                    "Recordings are saved to Movies/ScreenRecorder and screenshots to Pictures/ScreenRecorder.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SectionTitle("How it works", Modifier.fillMaxWidth())
        QxCard {
            KeyValueRow("Capture", "Android MediaProjection")
            KeyValueRow("Video", "MediaCodec hardware encoder → MP4")
            KeyValueRow("Audio", "AudioRecord + playback capture → AAC")
            KeyValueRow("GPU path", "OpenGL ES compositor")
        }
        SectionTitle("Limits worth knowing", Modifier.fillMaxWidth())
        QxCard {
            listOf(
                "Frame rate can't exceed your display's refresh rate, and resolution can't exceed your screen's.",
                "Internal audio needs Android 10+, and some apps (calls, protected media) block capture.",
                "Android 14+ asks for screen-capture permission for every recording.",
                "Floating controls and drawings are visible on screen, so they appear in recordings.",
                "HDR capture isn't available: Android screen capture is SDR.",
                "The Vulkan compositor isn't included yet; the OpenGL engine provides GPU composition.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = qx.textSoft, modifier = Modifier.padding(vertical = 3.dp)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
