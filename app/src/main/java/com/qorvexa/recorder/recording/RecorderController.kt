package com.qorvexa.recorder.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.recording.RecordingState
import com.qorvexa.recorder.core.recording.RecordingStateMachine
import com.qorvexa.recorder.core.video.ResolvedVideoConfig
import com.qorvexa.recorder.recording.audio.ActiveEffects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class RecorderStatus(
    val state: RecordingState = RecordingState.IDLE,
    val elapsedMs: Long = 0,
    val countdown: Int = 0,
    val config: ResolvedVideoConfig? = null,
    val engine: EngineType? = null,
    val encoderName: String? = null,
    val bytesWritten: Long = 0,
    val micLevelDb: Float = -90f,
    val effects: ActiveEffects? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val lastSavedUri: Uri? = null,
    val lastSavedName: String? = null,
    val lastScreenshotUri: Uri? = null,
    val thermalWarning: Boolean = false,
)

/**
 * Single source of truth for recording status, shared by the UI, the foreground
 * service, the notification and the floating overlay (all in one process).
 */
@Singleton
class RecorderController @Inject constructor() {
    val machine = RecordingStateMachine()
    private val _status = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = _status.asStateFlow()

    /** Moves the state machine and publishes the new state. Returns false if the move isn't allowed. */
    fun move(to: RecordingState): Boolean {
        val ok = machine.moveTo(to)
        syncState()
        return ok
    }

    fun fail(message: String) {
        machine.fail()
        _status.update { it.copy(state = machine.current, error = message) }
    }

    fun update(transform: (RecorderStatus) -> RecorderStatus) {
        _status.update { transform(it).copy(state = machine.current) }
    }

    fun syncState() = _status.update { it.copy(state = machine.current) }

    fun addWarning(msg: String) = update { if (msg in it.warnings) it else it.copy(warnings = it.warnings + msg) }
    fun clearMessages() = update { it.copy(warnings = emptyList(), error = null) }

    // ---- Commands (safe to call from UI, overlay or notification) ----

    /** Starts the consent flow. Ignored when a recording is already running (prevents double start). */
    fun requestStart(context: Context) {
        if (machine.current.isActive) return
        if (!machine.moveTo(RecordingState.REQUESTING_PERMISSION)) {
            machine.reset()
            if (!machine.moveTo(RecordingState.REQUESTING_PERMISSION)) return
        }
        syncState()
        update { it.copy(error = null, warnings = emptyList(), elapsedMs = 0, bytesWritten = 0) }
        context.startActivity(ProjectionRequestActivity.intent(context, ProjectionRequestActivity.MODE_RECORD))
    }

    /** One-off screenshot. While recording it is taken from the running capture without a new consent. */
    fun requestScreenshot(context: Context) {
        if (machine.current == RecordingState.RECORDING || machine.current == RecordingState.PAUSED) {
            send(context, RecorderService.ACTION_SCREENSHOT)
        } else if (!machine.current.isActive) {
            context.startActivity(ProjectionRequestActivity.intent(context, ProjectionRequestActivity.MODE_SCREENSHOT))
        }
    }

    fun pause(context: Context) = send(context, RecorderService.ACTION_PAUSE)
    fun resume(context: Context) = send(context, RecorderService.ACTION_RESUME)
    fun stop(context: Context) = send(context, RecorderService.ACTION_STOP)

    fun cancelRequest() {
        if (machine.current == RecordingState.REQUESTING_PERMISSION) move(RecordingState.IDLE)
    }

    private fun send(context: Context, action: String) {
        if (!machine.current.isActive) return
        // The service is already in the foreground while recording, so a plain start is allowed.
        val i = Intent(context, RecorderService::class.java).setAction(action)
        runCatching { context.startService(i) }.onFailure { ContextCompat.startForegroundService(context, i) }
    }
}
