package com.qorvexa.recorder.di

import android.content.Context
import androidx.room.Room
import com.qorvexa.recorder.library.db.AppDatabase
import com.qorvexa.recorder.library.db.RecordingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "qorvexa.db").build()

    @Provides
    fun recordingDao(db: AppDatabase): RecordingDao = db.recordings()
}
