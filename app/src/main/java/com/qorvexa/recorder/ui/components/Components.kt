package com.qorvexa.recorder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qorvexa.recorder.core.model.SupportStatus
import com.qorvexa.recorder.ui.theme.LocalQx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QxTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
fun QxCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val qx = LocalQx.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = qx.card,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.45f) ?: qx.cardBorder),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** A tappable settings row: icon, title, current value and chevron. */
@Composable
fun SettingRow(icon: ImageVector, title: String, value: String? = null, subtitle: String? = null, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, if (enabled) tint else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp)) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, steps: Int = 0, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/** An option in a picker. Unavailable options stay visible with the reason, never silently hidden. */
data class PickerOption<T>(val value: T, val label: String, val available: Boolean = true, val note: String? = null)

@Composable
fun <T> OptionPickerDialog(title: String, options: List<PickerOption<T>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(options) { o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = o.available) { onSelect(o.value); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                o.label,
                                color = if (o.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontWeight = if (o.value == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            o.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (o.value == selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Helper that owns the "which picker is open" state. */
@Composable
fun rememberPickerState() = remember { mutableStateOf<String?>(null) }

@Composable
fun StatusChip(status: SupportStatus) {
    val qx = LocalQx.current
    val color = when (status) {
        SupportStatus.SUPPORTED -> qx.green
        SupportStatus.PARTIAL -> qx.amber
        SupportStatus.NOT_SUPPORTED -> qx.red
        SupportStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(status.label, color = color, style = MaterialTheme.typography.labelMedium) }
}

@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb -> "%.1f GB".format(bytes / (kb * kb * kb))
        bytes >= kb * kb -> "%.1f MB".format(bytes / (kb * kb))
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
