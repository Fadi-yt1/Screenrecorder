package com.qorvexa.recorder.recording

import android.content.Context
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.common.RecorderError
import com.qorvexa.recorder.core.engine.EngineSelector
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.model.ThermalStatus
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.recording.GuardSnapshot
import com.qorvexa.recorder.core.recording.PresentationClock
import com.qorvexa.recorder.core.recording.RecordingGuard
import com.qorvexa.recorder.core.recording.RecordingState
import com.qorvexa.recorder.core.recording.StopReason
import com.qorvexa.recorder.core.storage.FileNaming
import com.qorvexa.recorder.core.video.BitrateCalculator
import com.qorvexa.recorder.core.video.ResolvedVideoConfig
import com.qorvexa.recorder.core.video.VideoConfigResolver
import com.qorvexa.recorder.device.DeviceCapabilityDetector
import com.qorvexa.recorder.library.LibraryRepository
import com.qorvexa.recorder.library.db.RecordingDao
import com.qorvexa.recorder.library.db.RecordingEntity
import com.qorvexa.recorder.library.db.RecordingStatus
import com.qorvexa.recorder.recording.audio.AudioCapture
import com.qorvexa.recorder.recording.audio.AudioPlan
import com.qorvexa.recorder.recording.encoder.AudioEncoder
import com.qorvexa.recorder.recording.encoder.MuxerSink
import com.qorvexa.recorder.recording.encoder.VideoEncoder
import com.qorvexa.recorder.recording.engine.CaptureEngine
import com.qorvexa.recorder.recording.engine.EngineParams
import com.qorvexa.recorder.settings.SettingsRepository
import com.qorvexa.recorder.storage.MediaStorage
import com.qorvexa.recorder.storage.OutputTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * One recording from start to finished MP4. Owns every native resource
 * (virtual display, encoders, audio records, muxer) and releases them in order.
 */
class RecordingSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val settings: RecordingSettings,
    private val caps: DeviceCapabilities,
    private val micAllowed: Boolean,
    private val controller: RecorderController,
    private val storage: MediaStorage,
    private val settingsRepo: SettingsRepository,
    private val library: LibraryRepository,
    private val dao: RecordingDao,
    private val detector: DeviceCapabilityDetector,
    private val scope: CoroutineScope,
    private val onNotify: () -> Unit,
    private val onFinished: () -> Unit,
) {
    private val clock = PresentationClock()
    private val guard = RecordingGuard(settings)
    private val lock = Mutex()
    private val handlerThread = HandlerThread("qx-projection").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var target: OutputTarget? = null
    private var sink: MuxerSink? = null
    private var video: VideoEncoder? = null
    private var audio: AudioCapture? = null
    private var engine: CaptureEngine? = null
    private var config: ResolvedVideoConfig? = null
    private var engineType: EngineType = EngineType.BASIC
    private var guardJob: Job? = null
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by system/user")
            scope.launch { stop(StopReason.PROJECTION_ENDED) }
        }
    }

    suspend fun start() {
        try {
            // Android 14+ requires the callback before createVirtualDisplay.
            projection.registerCallback(projectionCallback, handler)

            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val cfg = VideoConfigResolver.resolve(settings, caps, landscape)
            cfg.notes.forEach { controller.addWarning(it) }
            engineType = EngineSelector.select(settings, caps)

            // Storage pre-check
            val (free, _) = detector.storageInfo()
            val bps = BitrateCalculator.bytesPerSecond(cfg.bitrate, if (settings.audioSource == AudioSource.NONE) 0 else settings.audioBitrate.bps)
            if (!guard.canStart(free, bps)) throw RecorderError.InsufficientStorage()

            // Countdown (the overlay shows big digits by observing controller status)
            if (settings.countdownSec > 0) {
                controller.move(RecordingState.COUNTDOWN)
                for (i in settings.countdownSec downTo 1) {
                    controller.update { it.copy(countdown = i) }
                    onNotify()
                    delay(1000)
                    if (stopping) return
                }
                controller.update { it.copy(countdown = 0) }
            }
            lock.withLock { initialize(cfg, landscape) }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            val msg = (e as? RecorderError)?.userMessage ?: "Couldn't start recording: ${e.message}"
            cleanup(success = false)
            controller.fail(msg)
            RecordingNotifications.alert(context, "Recording failed", msg)
            onFinished()
        }
    }

    /** Creates and wires every resource. Runs under [lock] so stop() can't interleave. */
    private suspend fun initialize(resolved: ResolvedVideoConfig, landscape: Boolean) {
        if (stopping) return
        var cfg = resolved
        run {
            controller.move(RecordingState.INITIALIZING)
            onNotify()

            withContext(Dispatchers.IO) {
                val now = LocalDateTime.now()
                val counter = settingsRepo.nextCounter(FileNaming.nextCounter(storage.allRecordingNames()))
                val base = FileNaming.expand(settings.fileNamePattern, counter, now, "${cfg.size.shortSide}p", cfg.fps)
                target = storage.createVideoOutput(base, now)
            }
            val t = target!!
            // Recovery bookkeeping: if the process dies, the next launch finishes or cleans this file.
            dao.upsert(entityFor(t, cfg, RecordingStatus.IN_PROGRESS, 0, 0, t.uri?.toString() ?: "file://${t.path}"))

            val plan = AudioPlan.from(settings, internalAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && micAllowed, micAllowed = micAllowed && caps.micAvailable)
            if (settings.audioSource != AudioSource.NONE && plan.source != settings.audioSource) {
                controller.addWarning(
                    if (!micAllowed) "Microphone permission is off, so audio can't be recorded."
                    else "Internal audio needs Android 10+. Recording ${plan.source.label.lowercase()}.",
                )
            }
            val muxer = MuxerSink(t, expectAudio = plan.source != AudioSource.NONE, clock = clock)
            sink = muxer

            // Video encoder, with codec fallback to H.264 if HEVC/AV1 can't be configured.
            val async = engineType != EngineType.BASIC
            var enc = VideoEncoder(cfg, muxer, async, settings.gamingMode, ::onEncoderError)
            try {
                enc.prepare()
            } catch (e: RecorderError.EncoderFailure) {
                enc.release()
                if (cfg.codec == VideoCodec.H264) throw e
                controller.addWarning("${cfg.codec.label} encoder failed to start; using H.264.")
                cfg = VideoConfigResolver.resolve(settings.copy(codec = VideoCodec.H264), caps, landscape)
                enc = VideoEncoder(cfg, muxer, async, settings.gamingMode, ::onEncoderError)
                enc.prepare()
            }
            video = enc
            config = cfg.copy(size = enc.size)

            // Audio
            if (plan.source != AudioSource.NONE) {
                try {
                    val aEnc = AudioEncoder(plan.sampleRate, plan.channels, settings.audioBitrate.bps, muxer)
                    val cap = AudioCapture(
                        context, settings, plan, projection, aEnc,
                        onLevel = { db -> controller.update { s -> s.copy(micLevelDb = db) } },
                        onWarning = { controller.addWarning(it) },
                    )
                    cap.prepare()
                    audio = cap
                    controller.update { it.copy(effects = cap.activeEffects) }
                } catch (e: Exception) {
                    // Keep recording video; the muxer must then not wait for an audio track.
                    Log.w(TAG, "audio unavailable, recording video only", e)
                    muxer.disableAudio()
                    controller.addWarning("Audio couldn't be started (${(e as? RecorderError)?.userMessage ?: e.message}). Recording video only.")
                }
            }

            // Engine, with automatic fallback (OpenGL → Advanced → Basic).
            var type = engineType
            while (true) {
                val eng = CaptureEngine.create(type)
                try {
                    eng.start(EngineParams(projection, enc.inputSurface, enc.size, caps.densityDpi.coerceAtLeast(160), cfg.fps, settings.watermark, handler))
                    engine = eng
                    engineType = type
                    break
                } catch (e: RecorderError) {
                    eng.stop()
                    val next = EngineSelector.fallbackFor(type) ?: throw e
                    controller.addWarning("${type.label} engine failed (${e.message}); switched to ${next.label}.")
                    type = next
                }
            }

            clock.start(System.nanoTime() / 1000)
            enc.start()
            audio?.start()
            acquireWakeLock()
            controller.update { it.copy(config = config, engine = engineType, encoderName = enc.encoderName) }
            controller.move(RecordingState.RECORDING)
            onNotify()
            startGuard()
            Log.i(TAG, "Recording ${t.displayName}: ${config} engine=$engineType audio=${plan}")
        }
    }

    private fun onEncoderError(t: Throwable) {
        controller.addWarning((t as? RecorderError)?.userMessage ?: "Encoder error")
        scope.launch { stop(StopReason.ERROR) }
    }

    private fun startGuard() {
        guardJob = scope.launch(Dispatchers.Default) {
            var tick = 0
            var thermalWarned = false
            var storageWarned = false
            while (isActive) {
                delay(1000)
                val elapsed = clock.elapsedUs(System.nanoTime() / 1000) / 1000
                val bytes = sink?.bytesWritten ?: 0
                controller.update { it.copy(elapsedMs = elapsed, bytesWritten = bytes) }
                onNotify()
                if (tick % 3 == 0) {
                    val (free, _) = detector.storageInfo()
                    val (battery, charging) = detector.batteryInfo()
                    guard.check(GuardSnapshot(elapsed, bytes, free, battery, charging))?.let { reason ->
                        stop(reason); return@launch
                    }
                    if (!storageWarned && guard.shouldWarnStorage(free)) {
                        storageWarned = true
                        RecordingNotifications.alert(context, "Storage running low", "Less than ${free / (1024 * 1024)} MB left. Recording will stop automatically before storage fills up.")
                    }
                    val thermal = detector.thermalStatus()
                    if (thermal.isHot && !thermalWarned) {
                        thermalWarned = true
                        val text = "Your device is ${thermal.label.lowercase()} hot. Recording continues at your settings. " +
                            "For long sessions try 720p/30 FPS (Battery Saver mode) for your next recording."
                        controller.update { it.copy(thermalWarning = true) }
                        controller.addWarning(text)
                        RecordingNotifications.alert(context, "Device is heating up", text)
                    } else if (thermal.level < ThermalStatus.MODERATE.level) {
                        controller.update { it.copy(thermalWarning = false) }
                    }
                }
                // Size limit is checked every second (fast-growing at high bitrates).
                if (settings.maxFileSizeMb > 0 && bytes >= settings.maxFileSizeMb * RecordingGuard.MB) {
                    stop(StopReason.MAX_FILE_SIZE); return@launch
                }
                tick++
            }
        }
    }

    suspend fun pause() = lock.withLock {
        if (!controller.move(RecordingState.PAUSED)) return@withLock
        val now = System.nanoTime() / 1000
        clock.pause(now)
        engine?.pause()
        video?.setSuspended(true)
        audio?.pause()
        onNotify()
    }

    suspend fun resume() = lock.withLock {
        if (!controller.move(RecordingState.RESUMING)) return@withLock
        clock.resume(System.nanoTime() / 1000)
        video?.setSuspended(false)
        audio?.resume()
        engine?.resume()
        controller.move(RecordingState.RECORDING)
        onNotify()
    }

    suspend fun screenshot(): Uri? {
        val eng = engine ?: return null
        val bmp = eng.screenshot() ?: return null
        val now = LocalDateTime.now()
        val name = "QORVEXA_Screenshot_" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val uri = withContext(Dispatchers.IO) { storage.saveScreenshot(bmp, name) }
        bmp.recycle()
        controller.update { it.copy(lastScreenshotUri = uri) }
        if (uri != null) RecordingNotifications.saved(context, "Screenshot saved", name)
        library.reload()
        return uri
    }

    /** Stops and finalises. Idempotent: a second call while stopping is ignored. */
    suspend fun stop(reason: StopReason) = lock.withLock {
        if (stopping) return@withLock
        stopping = true
        if (controller.machine.current == RecordingState.COUNTDOWN) {
            controller.move(RecordingState.IDLE)
            cleanup(success = false)
            onFinished()
            return@withLock
        }
        if (!controller.move(RecordingState.STOPPING)) {
            // Start failed or never finished initialising.
            cleanup(success = false)
            onFinished()
            return@withLock
        }
        onNotify()
        guardJob?.cancel()
        val elapsedMs = clock.elapsedUs(System.nanoTime() / 1000) / 1000
        controller.move(RecordingState.FINALIZING)
        onNotify()
        val savedUri = withContext(Dispatchers.IO) { cleanup(success = true) }
        if (savedUri != null) {
            val t = target!!
            val cfg = config!!
            val size = sink?.bytesWritten ?: 0
            dao.delete(t.uri?.toString() ?: "file://${t.path}")
            library.recordCompleted(entityFor(t, cfg, RecordingStatus.COMPLETE, elapsedMs, size, savedUri.toString()))
            controller.update { it.copy(lastSavedUri = savedUri, lastSavedName = t.displayName, elapsedMs = elapsedMs) }
            controller.move(RecordingState.COMPLETED)
            val title = if (reason == StopReason.USER) "Recording saved" else reason.message
            RecordingNotifications.saved(context, title, t.displayName)
            if (reason != StopReason.USER && reason != StopReason.PROJECTION_ENDED) controller.addWarning(reason.message)
        } else {
            target?.let { dao.delete(it.uri?.toString() ?: "file://${it.path}") }
            controller.fail(if (reason == StopReason.ERROR) "Recording failed and nothing could be saved." else "The recording was too short to save.")
        }
        onFinished()
    }

    /**
     * Releases everything in dependency order: stop frames → drain encoders → finalise MP4 → publish file.
     * Returns the published URI when the file is valid.
     */
    private suspend fun cleanup(success: Boolean): Uri? {
        releaseWakeLock()
        runCatching { engine?.stop() }                // 1. no more frames
        runCatching { video?.stop() }                 // 2. drain video
        runCatching { audio?.stop() }                 // 3. drain audio (sends EOS)
        val valid = runCatching { sink?.stop() ?: false }.getOrDefault(false) // 4. write MP4 index
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        handlerThread.quitSafely()
        engine = null; video = null; audio = null
        val t = target ?: return null
        return storage.finalize(t, success && valid)
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (!settings.keepScreenOn) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "qorvexa:recording").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun entityFor(t: OutputTarget, cfg: ResolvedVideoConfig, status: Int, durationMs: Long, size: Long, uri: String) = RecordingEntity(
        uri = uri,
        displayName = t.displayName,
        filePath = t.path,
        width = cfg.size.width,
        height = cfg.size.height,
        fps = cfg.fps,
        videoBitrate = cfg.bitrate,
        audioBitrate = if (settings.audioSource == AudioSource.NONE) 0 else settings.audioBitrate.bps,
        codec = cfg.codec.label,
        engine = engineType.label,
        audioSource = settings.audioSource.label,
        durationMs = durationMs,
        sizeBytes = size,
        createdAt = System.currentTimeMillis(),
        gaming = settings.gamingMode,
        status = status,
    )

    companion object {
        private const val TAG = "Session"
    }
}
