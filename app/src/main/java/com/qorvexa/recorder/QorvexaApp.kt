package com.qorvexa.recorder

import android.app.Application
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.device.DeviceRepository
import com.qorvexa.recorder.library.LibraryRepository
import com.qorvexa.recorder.recording.RecordingNotifications
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class QorvexaApp : Application() {

    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var libraryRepository: LibraryRepository

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.sink = AndroidLogSink
        RecordingNotifications.createChannels(this)
        appScope.launch {
            // Hardware detection runs once per launch in the background (~50–150 ms).
            deviceRepository.refresh()
            // Finish or clean up any recording interrupted by a crash or process kill.
            libraryRepository.recoverInterrupted()
        }
    }
}

private object AndroidLogSink : Log.Sink {
    override fun log(level: Log.Level, tag: String, msg: String, t: Throwable?) {
        val full = "QX/$tag"
        when (level) {
            Log.Level.DEBUG -> if (BuildConfig.DEBUG) android.util.Log.d(full, msg, t)
            Log.Level.INFO -> android.util.Log.i(full, msg, t)
            Log.Level.WARN -> android.util.Log.w(full, msg, t)
            Log.Level.ERROR -> android.util.Log.e(full, msg, t)
        }
    }
}
