package com.qorvexa.recorder.recording

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.recording.RecordingState
import com.qorvexa.recorder.core.recording.StopReason
import com.qorvexa.recorder.device.DeviceCapabilityDetector
import com.qorvexa.recorder.device.DeviceRepository
import com.qorvexa.recorder.library.LibraryRepository
import com.qorvexa.recorder.library.db.RecordingDao
import com.qorvexa.recorder.recording.engine.imageToBitmap
import com.qorvexa.recorder.settings.SettingsRepository
import com.qorvexa.recorder.storage.MediaStorage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Foreground service that owns the MediaProjection for the lifetime of a recording.
 * Declared with foregroundServiceType mediaProjection|microphone (required on Android 14+).
 */
@AndroidEntryPoint
class RecorderService : Service() {

    @Inject lateinit var controller: RecorderController
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var deviceRepo: DeviceRepository
    @Inject lateinit var detector: DeviceCapabilityDetector
    @Inject lateinit var storage: MediaStorage
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var dao: RecordingDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: RecordingSession? = null
    private var controlsInNotification = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_SCREENSHOT_ONESHOT -> handleOneShotScreenshot(intent)
            ACTION_PAUSE -> scope.launch { session?.pause() }
            ACTION_RESUME -> scope.launch { session?.resume() }
            ACTION_STOP -> scope.launch { session?.stop(StopReason.USER) ?: stopSelfSafely() }
            ACTION_SCREENSHOT -> scope.launch { session?.screenshot() }
            else -> if (session == null) stopSelfSafely()
        }
        // Never restart automatically: a new MediaProjection always needs fresh user consent.
        return START_NOT_STICKY
    }

    private fun foregroundTypes(withMic: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return t
    }

    private fun goForeground(withMic: Boolean, title: String): Boolean = try {
        ServiceCompat.startForeground(this, RecordingNotifications.ID_RECORDING, RecordingNotifications.working(this, title), foregroundTypes(withMic))
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        false
    }

    private fun projectionFrom(intent: Intent): MediaProjection? {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java) ?: return null
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return runCatching { mpm.getMediaProjection(code, data) }.onFailure { Log.e(TAG, "getMediaProjection failed", it) }.getOrNull()
    }

    private fun handleStart(intent: Intent) {
        if (session != null) {
            Log.w(TAG, "start ignored: already recording")
            return
        }
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        scope.launch {
            val settings = settingsRepo.current()
            controlsInNotification = settings.notificationControls
            val needsMic = settings.audioSource != AudioSource.NONE && micGranted
            // Must be in the foreground with the mediaProjection type BEFORE getMediaProjection (Android 14+).
            val ok = withContext(Dispatchers.Main) { goForeground(needsMic, "Preparing recording…") }
            if (!ok) {
                controller.fail("Android didn't allow the recording service to start.")
                stopSelfSafely(); return@launch
            }
            val projection = withContext(Dispatchers.Main) { projectionFrom(intent) }
            if (projection == null) {
                controller.fail("Screen capture permission was not granted.")
                stopSelfSafely(); return@launch
            }
            val caps = deviceRepo.get()
            val s = RecordingSession(
                context = this@RecorderService, projection = projection, settings = settings, caps = caps,
                micAllowed = micGranted, controller = controller, storage = storage, settingsRepo = settingsRepo,
                library = library, dao = dao, detector = detector, scope = scope,
                onNotify = { updateNotification() },
                onFinished = { scope.launch(Dispatchers.Main) { session = null; stopSelfSafely() } },
            )
            session = s
            s.start()
        }
    }

    private fun updateNotification() {
        val st = controller.status.value
        if (st.state == RecordingState.IDLE || st.state == RecordingState.COMPLETED || st.state == RecordingState.ERROR) return
        RecordingNotifications.notify(this, RecordingNotifications.ID_RECORDING, RecordingNotifications.recording(this, st, controlsInNotification))
    }

    /** Screenshot when not recording: one frame through an ImageReader, then the projection is stopped. */
    private fun handleOneShotScreenshot(intent: Intent) {
        if (session != null) {
            scope.launch { session?.screenshot() }
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.Main) { goForeground(false, "Taking screenshot…") }
            if (!ok) { stopSelfSafely(); return@launch }
            val projection = withContext(Dispatchers.Main) { projectionFrom(intent) } ?: run { stopSelfSafely(); return@launch }
            val caps = deviceRepo.get()
            val thread = HandlerThread("qx-shot").apply { start() }
            val handler = Handler(thread.looper)
            val w = caps.displayWidth
            val h = caps.displayHeight
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val result = CompletableDeferred<android.graphics.Bitmap?>()
            projection.registerCallback(object : MediaProjection.Callback() {}, handler)
            delay(350) // let the consent dialog disappear so it isn't in the screenshot
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (!result.isCompleted) {
                        val p = img.planes[0]
                        result.complete(imageToBitmap(p.buffer, p.rowStride, p.pixelStride, img.width, img.height))
                    }
                } finally {
                    img.close()
                }
            }, handler)
            val vd = runCatching {
                projection.createVirtualDisplay("QORVEXA-shot", w, h, caps.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
            }.getOrNull()
            val bmp = withTimeoutOrNull(3000) { result.await() }
            runCatching { vd?.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
            thread.quitSafely()
            if (bmp != null) {
                val name = "QORVEXA_Screenshot_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val uri = withContext(Dispatchers.IO) { storage.saveScreenshot(bmp, name) }
                bmp.recycle()
                controller.update { it.copy(lastScreenshotUri = uri) }
                if (uri != null) RecordingNotifications.saved(this@RecorderService, "Screenshot saved", name)
                library.reload()
            }
            withContext(Dispatchers.Main) { stopSelfSafely() }
        }
    }

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        // If the system kills the service mid-recording, try to finalise the file.
        session?.let { s -> kotlinx.coroutines.runBlocking { withTimeoutOrNull(4000) { s.stop(StopReason.ERROR) } } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecorderService"
        const val ACTION_START = "com.qorvexa.recorder.START"
        const val ACTION_PAUSE = "com.qorvexa.recorder.PAUSE"
        const val ACTION_RESUME = "com.qorvexa.recorder.RESUME"
        const val ACTION_STOP = "com.qorvexa.recorder.STOP"
        const val ACTION_SCREENSHOT = "com.qorvexa.recorder.SCREENSHOT"
        const val ACTION_SCREENSHOT_ONESHOT = "com.qorvexa.recorder.SCREENSHOT_ONESHOT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
