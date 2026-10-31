package com.qorvexa.recorder.core.drawing

enum class DrawTool { PEN, HIGHLIGHTER, ERASER }

data class StrokePoint(val x: Float, val y: Float)

data class Stroke(
    val tool: DrawTool,
    val color: Int,
    val width: Float,
    val points: List<StrokePoint>,
) {
    /** Highlighter strokes are translucent and wider. */
    val alpha: Int get() = if (tool == DrawTool.HIGHLIGHTER) 0x66 else 0xFF
}

/** Undo/redo stack for the drawing overlay. Strokes are stored in normalised (0..1) coordinates so they survive rotation. */
class StrokeHistory {
    private val done = ArrayList<Stroke>()
    private val undone = ArrayList<Stroke>()

    val strokes: List<Stroke> get() = done
    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    fun add(stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        done += stroke
        undone.clear()
    }

    fun undo(): Boolean {
        if (done.isEmpty()) return false
        undone += done.removeAt(done.lastIndex)
        return true
    }

    fun redo(): Boolean {
        if (undone.isEmpty()) return false
        done += undone.removeAt(undone.lastIndex)
        return true
    }

    /** Clear is undoable as a single step. */
    private var cleared: List<Stroke>? = null

    fun clear() {
        if (done.isEmpty()) return
        cleared = ArrayList(done)
        done.clear()
        undone.clear()
    }

    fun restoreCleared(): Boolean {
        val c = cleared ?: return false
        done.addAll(c)
        cleared = null
        return true
    }

    companion object {
        /** Converts screen pixels to normalised coordinates. */
        fun normalise(x: Float, y: Float, w: Int, h: Int) = StrokePoint(x / w.coerceAtLeast(1), y / h.coerceAtLeast(1))
    }
}
