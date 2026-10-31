package com.qorvexa.recorder.core.engine

import com.qorvexa.recorder.core.TestDevices
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.RecordingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EngineSelectorTest {
    @Test fun autoPicksBasicOnLowEnd() =
        assertEquals(EngineType.BASIC, EngineSelector.select(RecordingSettings(), TestDevices.lowEnd))

    @Test fun autoPicksAdvancedOnFlagship() =
        assertEquals(EngineType.ADVANCED, EngineSelector.select(RecordingSettings(), TestDevices.flagship))

    @Test fun watermarkForcesOpenGl() =
        assertEquals(EngineType.OPENGL, EngineSelector.select(RecordingSettings(watermark = true, engine = EngineType.BASIC), TestDevices.lowEnd))

    @Test fun vulkanIsNotSelectable() {
        val v = EngineSelector.compatibility(TestDevices.flagship).first { it.engine == EngineType.VULKAN }
        assertFalse(v.selectable)
        assertEquals(EngineType.ADVANCED, EngineSelector.select(RecordingSettings(engine = EngineType.VULKAN), TestDevices.flagship))
    }

    @Test fun fallbackChainEndsAtBasic() {
        assertEquals(EngineType.ADVANCED, EngineSelector.fallbackFor(EngineType.OPENGL))
        assertEquals(EngineType.BASIC, EngineSelector.fallbackFor(EngineType.ADVANCED))
        assertEquals(null, EngineSelector.fallbackFor(EngineType.BASIC))
    }
}
