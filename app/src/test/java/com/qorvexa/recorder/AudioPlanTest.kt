package com.qorvexa.recorder

import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.model.ChannelMode
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.recording.audio.AudioPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlanTest {
    @Test fun mixedFallsBackToMicWithoutInternal() {
        val p = AudioPlan.from(RecordingSettings(audioSource = AudioSource.INTERNAL_AND_MIC), internalAllowed = false, micAllowed = true)
        assertEquals(AudioSource.MIC, p.source)
        assertEquals(1, p.channels)
    }

    @Test fun noPermissionMeansNoAudio() {
        val p = AudioPlan.from(RecordingSettings(audioSource = AudioSource.MIC), internalAllowed = true, micAllowed = false)
        assertEquals(AudioSource.NONE, p.source)
    }

    @Test fun autoChannelsStereoForInternal() {
        val p = AudioPlan.from(RecordingSettings(audioSource = AudioSource.INTERNAL, channels = ChannelMode.AUTO), internalAllowed = true, micAllowed = true)
        assertEquals(2, p.channels)
    }

    @Test fun explicitMonoRespected() {
        val p = AudioPlan.from(RecordingSettings(audioSource = AudioSource.INTERNAL_AND_MIC, channels = ChannelMode.MONO), internalAllowed = true, micAllowed = true)
        assertEquals(1, p.channels)
        assertEquals(AudioSource.INTERNAL_AND_MIC, p.source)
    }
}
