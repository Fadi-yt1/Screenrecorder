package com.qorvexa.recorder.storage

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.common.RecorderError
import com.qorvexa.recorder.core.storage.FileNaming
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Where the muxer writes. Exactly one of [fd] / [path] is set. */
class OutputTarget(
    val uri: Uri?,
    val pfd: ParcelFileDescriptor?,
    val path: String?,
    val displayName: String,
) {
    val fd get() = pfd?.fileDescriptor
}

enum class MediaKind { VIDEO, IMAGE }

data class MediaItem(
    val uri: Uri,
    val kind: MediaKind,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
    val path: String?,
)

/** Outcome of an edit that may need user confirmation (Android 10+ ownership rules). */
sealed class EditResult {
    data object Done : EditResult()
    data class NeedsConsent(val intent: PendingIntent?, val sender: android.content.IntentSender?) : EditResult()
    data class Failed(val message: String) : EditResult()
}

/**
 * All file I/O for recordings and screenshots.
 * - Android 10+: MediaStore with RELATIVE_PATH and IS_PENDING (no storage permission needed).
 * - Android 5–9: files under the public Movies/Pictures folders, then media-scanned.
 */
@Singleton
class MediaStorage @Inject constructor(@ApplicationContext private val context: Context) {

    private val resolver get() = context.contentResolver

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun imageCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** Names already used in this month's folder, for duplicate prevention. */
    fun existingNames(kind: MediaKind, now: LocalDateTime = LocalDateTime.now()): Set<String> {
        val folder = FileNaming.folderFor(now)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val base = if (kind == MediaKind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val coll = if (kind == MediaKind.VIDEO) videoCollection() else imageCollection()
            val names = HashSet<String>()
            runCatching {
                resolver.query(
                    coll, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$base/$folder%"), null,
                )?.use { c -> while (c.moveToNext()) names += c.getString(0) }
            }
            names
        } else {
            legacyDir(kind, folder).list()?.toSet() ?: emptySet()
        }
    }

    /** All names we have ever produced, used to continue the counter after reinstall. */
    fun allRecordingNames(): List<String> = listMedia(MediaKind.VIDEO).map { it.displayName }

    @Suppress("DEPRECATION")
    private fun legacyDir(kind: MediaKind, folder: String): File {
        val base = Environment.getExternalStoragePublicDirectory(
            if (kind == MediaKind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        return File(base, folder)
    }

    fun createVideoOutput(baseName: String, now: LocalDateTime = LocalDateTime.now()): OutputTarget =
        createOutput(MediaKind.VIDEO, baseName, "mp4", "video/mp4", now)

    private fun createOutput(kind: MediaKind, baseName: String, ext: String, mime: String, now: LocalDateTime): OutputTarget {
        val folder = FileNaming.folderFor(now)
        val fileName = FileNaming.unique(baseName, ext, existingNames(kind, now))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val base = if (kind == MediaKind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/$folder")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(if (kind == MediaKind.VIDEO) videoCollection() else imageCollection(), values)
                    ?: throw RecorderError.StorageFailure("MediaStore refused to create $fileName")
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: run {
                        resolver.delete(uri, null, null)
                        throw RecorderError.StorageFailure("Couldn't open $fileName for writing")
                    }
                return OutputTarget(uri, pfd, null, fileName)
            } else {
                val dir = legacyDir(kind, folder)
                if (!dir.exists() && !dir.mkdirs()) throw RecorderError.StorageFailure("Couldn't create ${dir.path}")
                val file = File(dir, fileName)
                return OutputTarget(null, null, file.absolutePath, fileName)
            }
        } catch (e: RecorderError) {
            throw e
        } catch (e: Exception) {
            throw RecorderError.StorageFailure(e.message ?: "unknown error", e)
        }
    }

    /**
     * Publishes a finished file (clears IS_PENDING / scans it) or deletes a failed one.
     * Returns the public content URI on success.
     */
    suspend fun finalize(target: OutputTarget, success: Boolean): Uri? {
        runCatching { target.pfd?.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = target.uri ?: return null
            return if (success) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } else {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
        val path = target.path ?: return null
        if (!success) {
            File(path).delete()
            return null
        }
        return scan(path)
    }

    private suspend fun scan(path: String): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri -> if (cont.isActive) cont.resume(uri) }
    }

    /** Saves a screenshot PNG into Pictures/ScreenRecorder/<year>/<month>. */
    suspend fun saveScreenshot(bitmap: Bitmap, baseName: String): Uri? {
        val target = createOutput(MediaKind.IMAGE, baseName, "png", "image/png", LocalDateTime.now())
        return try {
            val stream = target.pfd?.let { FileOutputStream(it.fileDescriptor) } ?: FileOutputStream(target.path!!)
            stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            finalize(target, true)
        } catch (e: Exception) {
            Log.e("Storage", "screenshot save failed", e)
            finalize(target, false)
            null
        }
    }

    /** True when the file has a readable duration, i.e. the MP4 was finalised (moov atom present). */
    fun isPlayable(uri: Uri?, path: String?): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            if (path != null) r.setDataSource(path) else r.setDataSource(context, uri)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { r.release() }
        }
    }

    /** Deletes a half-written pending item left behind by a crash. */
    fun discard(uri: Uri?, path: String?) {
        runCatching { if (uri != null) resolver.delete(uri, null, null) }
        runCatching { if (path != null) File(path).delete() }
    }

    /** Publishes a pending item that turned out to be playable. */
    fun publishPending(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
        }
    }

    // ---------- Library ----------

    @Suppress("DEPRECATION")
    fun listMedia(kind: MediaKind): List<MediaItem> {
        val coll = if (kind == MediaKind.VIDEO) videoCollection() else imageCollection()
        val base = if (kind == MediaKind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATA,
        )
        if (kind == MediaKind.VIDEO) proj += MediaStore.Video.VideoColumns.DURATION
        val (sel, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0" to arrayOf("$base/${FileNaming.ROOT_DIR}/%")
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/$base/${FileNaming.ROOT_DIR}/%")
        }
        val out = ArrayList<MediaItem>()
        runCatching {
            resolver.query(coll, proj.toTypedArray(), sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iDur = if (kind == MediaKind.VIDEO) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    out += MediaItem(
                        uri = ContentUris.withAppendedId(coll, id),
                        kind = kind,
                        displayName = c.getString(iName) ?: "",
                        sizeBytes = c.getLong(iSize),
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0,
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        dateAddedSec = c.getLong(iDate),
                        path = if (iData >= 0) c.getString(iData) else null,
                    )
                }
            }
        }.onFailure { Log.w("Storage", "media query failed", it) }
        return out
    }

    fun rename(item: MediaItem, newBase: String): EditResult {
        val ext = item.displayName.substringAfterLast('.', if (item.kind == MediaKind.VIDEO) "mp4" else "png")
        val newName = FileNaming.sanitize(newBase) + "." + ext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
                EditResult.Done
            } else {
                val path = item.path ?: return EditResult.Failed("File path unknown")
                val src = File(path)
                val dst = File(src.parentFile, newName)
                if (dst.exists()) return EditResult.Failed("A file named $newName already exists")
                if (!src.renameTo(dst)) return EditResult.Failed("Rename failed")
                resolver.delete(item.uri, null, null)
                MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), null, null)
                EditResult.Done
            }
        } catch (e: SecurityException) {
            consentFor(e, item.uri, write = true)
        } catch (e: Exception) {
            EditResult.Failed(e.message ?: "Rename failed")
        }
    }

    fun delete(item: MediaItem): EditResult = try {
        val rows = resolver.delete(item.uri, null, null)
        if (rows == 0 && item.path != null) File(item.path).delete()
        EditResult.Done
    } catch (e: SecurityException) {
        consentFor(e, item.uri, write = false)
    } catch (e: Exception) {
        EditResult.Failed(e.message ?: "Delete failed")
    }

    /** Files from a previous install aren't "owned" by us; Android asks the user to confirm. */
    private fun consentFor(e: SecurityException, uri: Uri, write: Boolean): EditResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = if (write) MediaStore.createWriteRequest(resolver, listOf(uri)) else MediaStore.createDeleteRequest(resolver, listOf(uri))
            return EditResult.NeedsConsent(pi, null)
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
            return EditResult.NeedsConsent(null, e.userAction.actionIntent.intentSender)
        }
        return EditResult.Failed("Permission denied")
    }
}
