package com.qorvexa.recorder.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.qorvexa.recorder.library.LibraryItem
import com.qorvexa.recorder.library.SortOrder
import com.qorvexa.recorder.permissions.Permissions
import com.qorvexa.recorder.storage.MediaKind
import com.qorvexa.recorder.storage.ThumbnailLoader
import com.qorvexa.recorder.ui.components.InfoNote
import com.qorvexa.recorder.ui.components.KeyValueRow
import com.qorvexa.recorder.ui.components.QxTopBar
import com.qorvexa.recorder.ui.components.formatBytes
import com.qorvexa.recorder.ui.components.formatDuration
import com.qorvexa.recorder.ui.navigation.Routes
import com.qorvexa.recorder.ui.theme.LocalQx
import java.text.DateFormat
import java.util.Date

/** Thumbnail loaded lazily through the platform thumbnail cache. */
@Composable
fun MediaThumb(item: LibraryItem, loader: ThumbnailLoader, modifier: Modifier = Modifier) {
    var bmp by remember(item.media.uri) { mutableStateOf(loader.cached(item.media)) }
    LaunchedEffect(item.media.uri) { if (bmp == null) bmp = loader.load(item.media) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(Icons.Filled.VideoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.media.kind == MediaKind.VIDEO) {
            Icon(Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
            Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                Text(formatDuration(item.media.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun LibraryScreen(nav: NavHostController, vm: LibraryViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val consent by vm.pendingConsent.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<LibraryItem?>(null) }
    var renaming by remember { mutableStateOf<LibraryItem?>(null) }
    var deleting by remember { mutableStateOf<LibraryItem?>(null) }
    val qx = LocalQx.current

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.message.value = "Done"
        vm.consentHandled()
    }
    LaunchedEffect(consent) {
        consent?.let { c ->
            val sender = c.intent?.intentSender ?: c.sender
            if (sender != null) consentLauncher.launch(IntentSenderRequest.Builder(sender).build()) else vm.consentHandled()
        }
    }
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.reload() }
    LaunchedEffect(Unit) { vm.reload() }

    Column(Modifier.fillMaxSize()) {
        QxTopBar("Recordings", actions = {
            IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { o ->
                        DropdownMenuItem(text = { Text(o.label + if (o == sort) "  ✓" else "") }, onClick = { vm.sort.value = o; sortMenu = false })
                    }
                }
            }
        })
        if (searching) {
            OutlinedTextField(query, { vm.query.value = it }, placeholder = { Text("Search by name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { f ->
                FilterChip(
                    selected = f == filter, onClick = { vm.filter.value = f }, label = { Text(f.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Color.White),
                )
            }
        }
        message?.let {
            InfoNote(it, Modifier.padding(horizontal = 16.dp))
            LaunchedEffect(it) { kotlinx.coroutines.delay(2500); vm.message.value = null }
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.VideoLibrary, null, tint = qx.textSoft, modifier = Modifier.size(56.dp))
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("Your videos are saved to Movies/ScreenRecorder.", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
                if (!Permissions.mediaReadGranted(ctx)) {
                    Spacer(Modifier.height(16.dp))
                    Text("Reinstalled the app? Allow media access to show earlier recordings.", style = MaterialTheme.typography.bodySmall, color = qx.textSoft)
                    TextButton(onClick = { readPermission.launch(Permissions.mediaReadPermissions()) }) { Text("Allow access") }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.media.uri.toString() }) { item ->
                    LibraryCell(
                        item, vm.thumbnails,
                        onOpen = {
                            if (item.media.kind == MediaKind.VIDEO) nav.navigate(Routes.player(item.media.uri))
                            else openExternal(ctx, item.media.uri, "image/*")
                        },
                        onRename = { renaming = item },
                        onDelete = { deleting = item },
                        onShare = { share(ctx, item) },
                        onDetails = { details = item },
                        onExternal = { openExternal(ctx, item.media.uri, if (item.media.kind == MediaKind.VIDEO) "video/*" else "image/*") },
                    )
                }
            }
        }
    }

    details?.let { d -> DetailsDialog(d) { details = null } }
    renaming?.let { r ->
        var name by remember(r) { mutableStateOf(r.media.displayName.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it.take(100) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.rename(r, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete?") },
            text = { Text("“${d.media.displayName}” will be permanently deleted from your device.") },
            confirmButton = { TextButton(onClick = { vm.delete(d); deleting = null }) { Text("Delete", color = qx.red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LibraryCell(
    item: LibraryItem, loader: ThumbnailLoader,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit, onDetails: () -> Unit, onExternal: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clickable(onClick = onOpen)) {
        MediaThumb(item, loader, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(item.media.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        formatBytes(item.media.sizeBytes),
                        item.media.height.takeIf { it > 0 }?.let { "${minOf(item.media.width, item.media.height)}p" },
                        item.fps?.let { "${it}FPS" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = LocalQx.current.textSoft, maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onDetails() })
                    DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; onExternal() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun DetailsDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val m = item.media
    val meta = item.meta
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column {
                KeyValueRow("Name", m.displayName)
                KeyValueRow("Size", formatBytes(m.sizeBytes))
                if (m.width > 0) KeyValueRow("Resolution", "${m.width} × ${m.height}")
                if (m.kind == MediaKind.VIDEO) KeyValueRow("Duration", formatDuration(m.durationMs))
                meta?.let {
                    KeyValueRow("Frame rate", "${it.fps} FPS")
                    KeyValueRow("Video bitrate", "%.1f Mbps".format(it.videoBitrate / 1e6))
                    KeyValueRow("Codec", it.codec)
                    KeyValueRow("Audio", if (it.audioBitrate > 0) "${it.audioSource} · ${it.audioBitrate / 1000} kbps" else "None")
                    KeyValueRow("Engine", it.engine)
                }
                KeyValueRow("Recorded", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.dateAddedSec * 1000)))
                m.path?.let { KeyValueRow("Location", it.substringAfter("/0/").substringBeforeLast('/')) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun share(ctx: android.content.Context, item: LibraryItem) {
    val i = Intent(Intent.ACTION_SEND)
        .setType(if (item.media.kind == MediaKind.VIDEO) "video/mp4" else "image/png")
        .putExtra(Intent.EXTRA_STREAM, item.media.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openExternal(ctx: android.content.Context, uri: Uri, type: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** In-app player using the platform VideoView (streams from the content URI; nothing is loaded into memory). */
@Composable
fun PlayerScreen(uri: Uri, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        QxTopBar("Player", onBack = onBack, actions = {
            TextButton(onClick = { openExternal(ctx, uri, "video/*") }) { Text("Open with…") }
        })
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        val controller = MediaController(c)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(uri)
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
