package com.qorvexa.recorder.library.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Lifecycle of a file we created. IN_PROGRESS rows left after a crash are recovered on next launch. */
object RecordingStatus {
    const val IN_PROGRESS = 0
    const val COMPLETE = 1
    const val FAILED = 2
    const val RECOVERED = 3
}

/**
 * Metadata MediaStore doesn't keep (FPS, bitrate, codec, engine). The file itself
 * lives in MediaStore; [uri] links the two.
 */
@Entity(tableName = "recordings", indices = [Index(value = ["uri"], unique = true)])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val filePath: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val codec: String,
    val engine: String,
    val audioSource: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val gaming: Boolean,
    val status: Int,
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE status = :status")
    suspend fun byStatus(status: Int): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecordingEntity): Long

    @Query("UPDATE recordings SET displayName = :name WHERE uri = :uri")
    suspend fun rename(uri: String, name: String)

    @Query("DELETE FROM recordings WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Database(entities = [RecordingEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
}
