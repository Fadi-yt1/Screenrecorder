package com.qorvexa.recorder.core.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingState {
    IDLE, REQUESTING_PERMISSION, COUNTDOWN, INITIALIZING, RECORDING, PAUSED, RESUMING,
    STOPPING, FINALIZING, COMPLETED, ERROR;

    val isActive: Boolean
        get() = this == COUNTDOWN || this == INITIALIZING || this == RECORDING || this == PAUSED ||
            this == RESUMING || this == STOPPING || this == FINALIZING
}

/**
 * Guards every recording lifecycle transition so double-start, double-stop and
 * out-of-order calls are rejected instead of corrupting encoder state.
 */
class RecordingStateMachine(initial: RecordingState = RecordingState.IDLE) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<RecordingState> = _state.asStateFlow()
    val current: RecordingState get() = _state.value

    private val allowed: Map<RecordingState, Set<RecordingState>> = mapOf(
        RecordingState.IDLE to setOf(RecordingState.REQUESTING_PERMISSION, RecordingState.COUNTDOWN, RecordingState.INITIALIZING),
        RecordingState.REQUESTING_PERMISSION to setOf(RecordingState.COUNTDOWN, RecordingState.INITIALIZING, RecordingState.IDLE, RecordingState.ERROR),
        RecordingState.COUNTDOWN to setOf(RecordingState.INITIALIZING, RecordingState.IDLE, RecordingState.ERROR),
        RecordingState.INITIALIZING to setOf(RecordingState.RECORDING, RecordingState.ERROR, RecordingState.STOPPING),
        RecordingState.RECORDING to setOf(RecordingState.PAUSED, RecordingState.STOPPING, RecordingState.ERROR),
        RecordingState.PAUSED to setOf(RecordingState.RESUMING, RecordingState.STOPPING, RecordingState.ERROR),
        RecordingState.RESUMING to setOf(RecordingState.RECORDING, RecordingState.STOPPING, RecordingState.ERROR),
        RecordingState.STOPPING to setOf(RecordingState.FINALIZING, RecordingState.ERROR),
        RecordingState.FINALIZING to setOf(RecordingState.COMPLETED, RecordingState.ERROR),
        RecordingState.COMPLETED to setOf(RecordingState.IDLE, RecordingState.REQUESTING_PERMISSION, RecordingState.COUNTDOWN, RecordingState.INITIALIZING),
        RecordingState.ERROR to setOf(RecordingState.IDLE, RecordingState.REQUESTING_PERMISSION, RecordingState.COUNTDOWN, RecordingState.INITIALIZING),
    )

    fun canMove(to: RecordingState): Boolean = to in (allowed[current] ?: emptySet())

    /** Atomically moves to [to] if allowed. Returns false (and changes nothing) otherwise. */
    @Synchronized
    fun moveTo(to: RecordingState): Boolean {
        if (!canMove(to)) return false
        _state.value = to
        return true
    }

    /** Errors are always accepted so a failure can never leave us stuck in RECORDING. */
    @Synchronized
    fun fail() {
        _state.value = RecordingState.ERROR
    }

    @Synchronized
    fun reset() {
        _state.value = RecordingState.IDLE
    }
}
