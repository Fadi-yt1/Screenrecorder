package com.qorvexa.recorder.core.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeHistoryTest {
    private fun s() = Stroke(DrawTool.PEN, 0xFFFF0000.toInt(), 8f, listOf(StrokePoint(0f, 0f)))

    @Test fun undoRedo() {
        val h = StrokeHistory()
        h.add(s()); h.add(s())
        assertTrue(h.undo())
        assertEquals(1, h.strokes.size)
        assertTrue(h.redo())
        assertEquals(2, h.strokes.size)
        assertFalse(h.redo())
    }

    @Test fun newStrokeClearsRedo() {
        val h = StrokeHistory()
        h.add(s()); h.undo(); h.add(s())
        assertFalse(h.canRedo)
    }

    @Test fun clearIsRestorable() {
        val h = StrokeHistory()
        h.add(s()); h.add(s()); h.clear()
        assertEquals(0, h.strokes.size)
        assertTrue(h.restoreCleared())
        assertEquals(2, h.strokes.size)
    }

    @Test fun highlighterIsTranslucent() = assertEquals(0x66, s().copy(tool = DrawTool.HIGHLIGHTER).alpha)
}
