package com.qorvexa.recorder

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qorvexa.recorder.device.DeviceCapabilityDetector
import com.qorvexa.recorder.library.db.AppDatabase
import com.qorvexa.recorder.library.db.RecordingEntity
import com.qorvexa.recorder.library.db.RecordingStatus
import com.qorvexa.recorder.storage.MediaKind
import com.qorvexa.recorder.storage.MediaStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Build
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class StorageAndDatabaseTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun daoRoundTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val dao = db.recordings()
        val e = RecordingEntity(0, "content://x/1", "a.mp4", null, 1080, 2400, 60, 8_000_000, 128_000, "H.264", "Basic", "Mic", 1000, 2000, 1, false, RecordingStatus.IN_PROGRESS)
        dao.upsert(e)
        assertEquals(1, dao.byStatus(RecordingStatus.IN_PROGRESS).size)
        dao.rename("content://x/1", "b")
        assertEquals("b", dao.byUri("content://x/1")?.displayName)
        dao.delete("content://x/1")
        assertTrue(dao.observeAll().first().isEmpty())
        db.close()
    }

    /** Creates, publishes, lists and deletes a file through MediaStore (Android 10+ path). */
    @Test fun mediaStoreLifecycle() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking // legacy path needs runtime storage permission
        val storage = MediaStorage(ctx)
        val target = storage.createVideoOutput("QX_Test_${System.currentTimeMillis()}")
        assertNotNull(target.pfd)
        FileOutputStream(target.fd).use { it.write(ByteArray(1024)) }
        val uri = storage.finalize(target, success = true)
        assertNotNull(uri)
        val listed = storage.listMedia(MediaKind.VIDEO).firstOrNull { it.uri == uri }
        assertNotNull("published file should be listed", listed)
        // A 1 KB non-MP4 file is not playable; recovery logic must treat it as unrecoverable.
        assertFalse(storage.isPlayable(uri, null))
        storage.discard(uri, null)
        assertTrue(storage.listMedia(MediaKind.VIDEO).none { it.uri == uri })
    }

    @Test fun failedRecordingIsRemoved() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking
        val storage = MediaStorage(ctx)
        val name = "QX_Fail_${System.currentTimeMillis()}"
        val target = storage.createVideoOutput(name)
        val uri = storage.finalize(target, success = false)
        assertEquals(null, uri)
        assertTrue(storage.listMedia(MediaKind.VIDEO).none { it.displayName.startsWith(name) })
    }

    @Test fun detectorReadsRealHardware() {
        val caps = DeviceCapabilityDetector(ctx).detect()
        assertTrue(caps.displayWidth > 0 && caps.displayHeight > 0)
        assertTrue("at least one H.264 encoder", caps.encodersFor(com.qorvexa.recorder.core.model.VideoCodec.H264).isNotEmpty())
        assertTrue(caps.totalRamMb > 0)
        assertTrue(caps.cpuCores >= 1)
    }
}
