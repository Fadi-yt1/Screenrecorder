package com.qorvexa.recorder.library

import android.net.Uri
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.library.db.RecordingDao
import com.qorvexa.recorder.library.db.RecordingEntity
import com.qorvexa.recorder.library.db.RecordingStatus
import com.qorvexa.recorder.recording.RecorderController
import com.qorvexa.recorder.storage.EditResult
import com.qorvexa.recorder.storage.MediaItem
import com.qorvexa.recorder.storage.MediaKind
import com.qorvexa.recorder.storage.MediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A library row: the MediaStore file plus whatever extra metadata we recorded for it. */
data class LibraryItem(val media: MediaItem, val meta: RecordingEntity?) {
    val fps: Int? get() = meta?.fps
    val isGaming: Boolean get() = meta?.gaming == true
}

enum class SortOrder(val label: String) {
    NEWEST("Newest"), OLDEST("Oldest"), LARGEST("Largest"), SMALLEST("Smallest"), LONGEST("Longest"), SHORTEST("Shortest")
}

@Singleton
class LibraryRepository @Inject constructor(
    private val storage: MediaStorage,
    private val dao: RecordingDao,
    private val controller: RecorderController,
) {
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    suspend fun reload() = withContext(Dispatchers.IO) {
        val meta = dao.observeAll().first().associateBy { it.uri }
        val videos = storage.listMedia(MediaKind.VIDEO).map { LibraryItem(it, meta[it.uri.toString()]) }
        val images = storage.listMedia(MediaKind.IMAGE).map { LibraryItem(it, null) }
        _items.value = (videos + images).sortedByDescending { it.media.dateAddedSec }
    }

    suspend fun recordCompleted(entity: RecordingEntity) {
        dao.upsert(entity)
        reload()
    }

    suspend fun rename(item: LibraryItem, newName: String): EditResult = withContext(Dispatchers.IO) {
        val r = storage.rename(item.media, newName)
        if (r is EditResult.Done) dao.rename(item.media.uri.toString(), newName)
        reload()
        r
    }

    suspend fun delete(item: LibraryItem): EditResult = withContext(Dispatchers.IO) {
        val r = storage.delete(item.media)
        if (r is EditResult.Done) dao.delete(item.media.uri.toString())
        reload()
        r
    }

    /** Called after the user approves a system consent dialog for a delete. */
    suspend fun forgetDeleted(uri: Uri) {
        dao.delete(uri.toString())
        reload()
    }

    /**
     * Recovery after a crash or process kill:
     * - finished-enough files (playable) are published and kept;
     * - unplayable partial files are deleted so they don't waste space.
     * MP4 needs its index written at stop, so a file cut off mid-recording usually can't be saved.
     */
    suspend fun recoverInterrupted(): Int = withContext(Dispatchers.IO) {
        if (controller.status.value.state.isActive) return@withContext 0
        var recovered = 0
        for (row in dao.byStatus(RecordingStatus.IN_PROGRESS)) {
            val uri = runCatching { Uri.parse(row.uri) }.getOrNull()
            val playable = storage.isPlayable(uri?.takeIf { row.filePath == null }, row.filePath)
            if (playable) {
                uri?.let { storage.publishPending(it) }
                dao.upsert(row.copy(status = RecordingStatus.RECOVERED))
                recovered++
                Log.i("Library", "Recovered ${row.displayName}")
            } else {
                storage.discard(uri?.takeIf { row.filePath == null }, row.filePath)
                dao.upsert(row.copy(status = RecordingStatus.FAILED))
                Log.w("Library", "Discarded unrecoverable ${row.displayName}")
            }
        }
        recovered
    }

    companion object {
        fun sort(list: List<LibraryItem>, order: SortOrder): List<LibraryItem> = when (order) {
            SortOrder.NEWEST -> list.sortedByDescending { it.media.dateAddedSec }
            SortOrder.OLDEST -> list.sortedBy { it.media.dateAddedSec }
            SortOrder.LARGEST -> list.sortedByDescending { it.media.sizeBytes }
            SortOrder.SMALLEST -> list.sortedBy { it.media.sizeBytes }
            SortOrder.LONGEST -> list.sortedByDescending { it.media.durationMs }
            SortOrder.SHORTEST -> list.sortedBy { it.media.durationMs }
        }
    }
}
