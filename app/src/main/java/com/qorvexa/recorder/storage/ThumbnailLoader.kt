package com.qorvexa.recorder.storage

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads small thumbnails via the platform thumbnail cache (never decodes whole videos into memory).
 * Results are kept in a bounded LRU cache.
 */
@Singleton
class ThumbnailLoader @Inject constructor(@ApplicationContext private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val permits = Semaphore(3)

    fun cached(item: MediaItem): Bitmap? = cache.get(item.uri.toString())

    suspend fun load(item: MediaItem): Bitmap? {
        cache.get(item.uri.toString())?.let { return it }
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                val bmp = runCatching { loadInternal(item) }.getOrNull()
                if (bmp != null) cache.put(item.uri.toString(), bmp)
                bmp
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadInternal(item: MediaItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.contentResolver.loadThumbnail(item.uri, Size(384, 216), null)
        }
        val id = ContentUris.parseId(item.uri)
        return when (item.kind) {
            MediaKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                ?: frameAt(item)
            MediaKind.IMAGE -> MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    private fun frameAt(item: MediaItem): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, item.uri)
            r.getFrameAtTime(1_000_000)?.let { Bitmap.createScaledBitmap(it, 384, 384 * it.height / it.width.coerceAtLeast(1), true) }
        } finally {
            runCatching { r.release() }
        }
    }
}
