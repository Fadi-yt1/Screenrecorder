package com.qorvexa.recorder.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {
    @Test fun happyPath() {
        val sm = RecordingStateMachine()
        listOf(
            RecordingState.REQUESTING_PERMISSION, RecordingState.COUNTDOWN, RecordingState.INITIALIZING,
            RecordingState.RECORDING, RecordingState.PAUSED, RecordingState.RESUMING, RecordingState.RECORDING,
            RecordingState.STOPPING, RecordingState.FINALIZING, RecordingState.COMPLETED, RecordingState.IDLE,
        ).forEach { assertTrue("-> $it", sm.moveTo(it)) }
    }

    @Test fun doubleStartRejected() {
        val sm = RecordingStateMachine()
        assertTrue(sm.moveTo(RecordingState.INITIALIZING))
        assertFalse(sm.moveTo(RecordingState.INITIALIZING))
        assertTrue(sm.moveTo(RecordingState.RECORDING))
        assertFalse(sm.moveTo(RecordingState.INITIALIZING))
    }

    @Test fun doubleStopRejected() {
        val sm = RecordingStateMachine(RecordingState.RECORDING)
        assertTrue(sm.moveTo(RecordingState.STOPPING))
        assertFalse(sm.moveTo(RecordingState.STOPPING))
    }

    @Test fun pauseOnlyWhileRecording() {
        val sm = RecordingStateMachine()
        assertFalse(sm.moveTo(RecordingState.PAUSED))
    }

    @Test fun failAlwaysWorksAndRecovers() {
        val sm = RecordingStateMachine(RecordingState.FINALIZING)
        sm.fail()
        assertEquals(RecordingState.ERROR, sm.current)
        assertTrue(sm.moveTo(RecordingState.INITIALIZING))
    }
}
