package com.qorvexa.recorder.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qorvexa.recorder.library.LibraryItem
import com.qorvexa.recorder.library.LibraryRepository
import com.qorvexa.recorder.library.SortOrder
import com.qorvexa.recorder.storage.EditResult
import com.qorvexa.recorder.storage.MediaKind
import com.qorvexa.recorder.storage.ThumbnailLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryFilter(val label: String) { ALL("All"), VIDEOS("Videos"), GAMING("Gaming"), SCREENSHOTS("Screenshots") }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: LibraryRepository,
    val thumbnails: ThumbnailLoader,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LibraryFilter.ALL)
    val sort = MutableStateFlow(SortOrder.NEWEST)
    /** Set when Android needs the user to confirm an edit (files from a previous install). */
    val pendingConsent = MutableStateFlow<EditResult.NeedsConsent?>(null)
    val message = MutableStateFlow<String?>(null)

    val items: StateFlow<List<LibraryItem>> = combine(repo.items, query, filter, sort) { list, q, f, s ->
        val filtered = list.filter { item ->
            when (f) {
                LibraryFilter.ALL -> true
                LibraryFilter.VIDEOS -> item.media.kind == MediaKind.VIDEO
                LibraryFilter.GAMING -> item.isGaming
                LibraryFilter.SCREENSHOTS -> item.media.kind == MediaKind.IMAGE
            } && (q.isBlank() || item.media.displayName.contains(q.trim(), ignoreCase = true))
        }
        LibraryRepository.sort(filtered, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun reload() = viewModelScope.launch { repo.reload() }

    fun rename(item: LibraryItem, name: String) = viewModelScope.launch { handle(repo.rename(item, name), "Renamed") }
    fun delete(item: LibraryItem) = viewModelScope.launch { handle(repo.delete(item), "Deleted") }

    private fun handle(r: EditResult, ok: String) {
        when (r) {
            is EditResult.Done -> message.value = ok
            is EditResult.NeedsConsent -> pendingConsent.value = r
            is EditResult.Failed -> message.value = r.message
        }
    }

    fun consentHandled() {
        pendingConsent.value = null
        reload()
    }
}
