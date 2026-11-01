package com.qorvexa.recorder.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.qorvexa.recorder.R
import com.qorvexa.recorder.core.drawing.DrawTool
import com.qorvexa.recorder.core.drawing.Stroke
import com.qorvexa.recorder.core.drawing.StrokeHistory
import com.qorvexa.recorder.core.drawing.StrokePoint
import com.qorvexa.recorder.settings.BubbleIcon
import com.qorvexa.recorder.settings.BubbleShape
import com.qorvexa.recorder.settings.OverlaySettings

internal fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
internal fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()

/** The floating button. Drawn on a Canvas: no layout inflation, tiny memory footprint. */
class FloatingBubbleView(context: Context) : View(context) {
    var settings: OverlaySettings = OverlaySettings()
        set(value) { field = value; invalidate() }
    var recording = false
        set(value) { field = value; invalidate() }
    var paused = false
        set(value) { field = value; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val logo: Bitmap? = runCatching { BitmapFactory.decodeResource(resources, R.drawable.qorvexa_mark) }.getOrNull()
    private val rect = RectF()
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(2f, 2f, w - 2f, h - 2f)
        val radius = if (settings.shape == BubbleShape.CIRCLE) w / 2 else w * 0.28f
        bgPaint.color = settings.backgroundColor
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        ringPaint.strokeWidth = w * 0.06f
        ringPaint.color = when {
            recording && paused -> 0xFFFFC23D.toInt()
            recording -> 0xFFFF2D46.toInt()
            else -> 0xFF8B3DFF.toInt()
        }
        val inset = ringPaint.strokeWidth / 2 + 2f
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, ringPaint)

        val iconSize = w * settings.iconScalePercent / 100f
        val l = (w - iconSize) / 2
        val t = (h - iconSize) / 2
        if (recording) {
            // Stop square / pause bars make the current state obvious at a glance.
            iconPaint.color = settings.iconColor
            if (paused) {
                val bw = iconSize * 0.22f
                canvas.drawRect(l + iconSize * 0.2f, t + iconSize * 0.15f, l + iconSize * 0.2f + bw, t + iconSize * 0.85f, iconPaint)
                canvas.drawRect(l + iconSize * 0.58f, t + iconSize * 0.15f, l + iconSize * 0.58f + bw, t + iconSize * 0.85f, iconPaint)
            } else {
                val s = iconSize * 0.5f
                canvas.drawRoundRect(w / 2 - s / 2, h / 2 - s / 2, w / 2 + s / 2, h / 2 + s / 2, s * 0.15f, s * 0.15f, iconPaint)
            }
            return
        }
        when (settings.icon) {
            BubbleIcon.LOGO -> logo?.let {
                canvas.save()
                clip.reset()
                clip.addRoundRect(RectF(l, t, l + iconSize, t + iconSize), iconSize * 0.2f, iconSize * 0.2f, Path.Direction.CW)
                canvas.clipPath(clip)
                canvas.drawBitmap(it, null, RectF(l, t, l + iconSize, t + iconSize), iconPaint)
                canvas.restore()
            }
            BubbleIcon.RECORD -> {
                iconPaint.color = 0xFFFF2D46.toInt()
                canvas.drawCircle(w / 2, h / 2, iconSize * 0.38f, iconPaint)
            }
            BubbleIcon.CAMERA -> ContextCompat.getDrawable(context, R.drawable.ic_notification)?.let { d ->
                d.setTint(settings.iconColor)
                d.setBounds(l.toInt(), t.toInt(), (l + iconSize).toInt(), (t + iconSize).toInt())
                d.draw(canvas)
            }
        }
    }
}

/** Builds the compact control menu shown when the bubble is tapped. */
class ControlPanel(context: Context, private val onAction: (Action) -> Unit) : LinearLayout(context) {
    enum class Action { RECORD, PAUSE, RESUME, STOP, SCREENSHOT, DRAW, SETTINGS, CLOSE }

    val timer = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(context.dpi(10), 0, context.dpi(6), 0)
        gravity = Gravity.CENTER_VERTICAL
    }
    private val buttons = HashMap<Action, View>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = context.dpi(6)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(22f)
            setColor(0xF0151325.toInt())
            setStroke(context.dpi(1), 0x668B3DFF)
        }
        addView(timer)
        add(Action.RECORD, R.drawable.ic_ov_record, 0xFFFF2D46.toInt(), R.string.action_record)
        add(Action.PAUSE, R.drawable.ic_ov_pause, Color.WHITE, R.string.action_pause)
        add(Action.RESUME, R.drawable.ic_ov_play, Color.WHITE, R.string.action_resume)
        add(Action.STOP, R.drawable.ic_ov_stop, 0xFFFF2D46.toInt(), R.string.action_stop)
        add(Action.SCREENSHOT, R.drawable.ic_ov_camera, Color.WHITE, R.string.action_screenshot)
        add(Action.DRAW, R.drawable.ic_ov_pen, 0xFF22E4FF.toInt(), R.string.action_draw)
        add(Action.SETTINGS, R.drawable.ic_ov_settings, Color.WHITE, R.string.action_settings)
        add(Action.CLOSE, R.drawable.ic_ov_close, 0xFFB8B5C9.toInt(), R.string.action_close)
    }

    private fun add(action: Action, icon: Int, tint: Int, label: Int) {
        val b = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(tint)
            contentDescription = context.getString(label)
            background = null
            val p = context.dpi(9)
            setPadding(p, p, p, p)
            setOnClickListener { onAction(action) }
        }
        buttons[action] = b
        addView(b, LayoutParams(context.dpi(44), context.dpi(44)))
    }

    fun bind(recording: Boolean, paused: Boolean, timerText: String) {
        buttons[Action.RECORD]?.visibility = if (recording) GONE else VISIBLE
        buttons[Action.PAUSE]?.visibility = if (recording && !paused) VISIBLE else GONE
        buttons[Action.RESUME]?.visibility = if (recording && paused) VISIBLE else GONE
        buttons[Action.STOP]?.visibility = if (recording) VISIBLE else GONE
        timer.visibility = if (recording) VISIBLE else GONE
        timer.text = timerText
    }
}

/** Full-screen canvas for drawing over any app. Strokes are stored normalised so they survive rotation. */
class DrawingView(context: Context) : View(context) {
    val history = StrokeHistory()
    var tool = DrawTool.PEN
    var color = 0xFFFF2D46.toInt()
    var strokeWidthDp = 6f
    /** Temporary mode: strokes fade out a few seconds after being drawn. */
    var temporary = false
    var onHistoryChanged: () -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eraser = Paint(paint).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private var current: MutableList<StrokePoint>? = null
    private val path = Path()
    private val fadeAt = HashMap<Stroke, Long>()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null) // needed for eraser CLEAR blending
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = StrokeHistory.normalise(e.x, e.y, width, height)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = mutableListOf(p)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) current?.add(StrokeHistory.normalise(e.getHistoricalX(i), e.getHistoricalY(i), width, height))
                current?.add(p)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { pts ->
                    val widthPx = context.dp(if (tool == DrawTool.HIGHLIGHTER) strokeWidthDp * 3 else if (tool == DrawTool.ERASER) strokeWidthDp * 4 else strokeWidthDp)
                    val s = Stroke(tool, color, widthPx, pts.toList())
                    history.add(s)
                    if (temporary && tool != DrawTool.ERASER) {
                        fadeAt[s] = System.currentTimeMillis() + 3000
                        postDelayed({ fadeTick() }, 3100)
                    }
                    onHistoryChanged()
                }
                current = null
            }
        }
        invalidate()
        return true
    }

    private fun fadeTick() {
        val now = System.currentTimeMillis()
        val expired = fadeAt.filterValues { it <= now }.keys
        if (expired.isEmpty()) return
        // Temporary strokes are removed by rebuilding history without them.
        val keep = history.strokes.filterNot { it in expired }
        history.clear()
        keep.forEach { history.add(it) }
        expired.forEach { fadeAt.remove(it) }
        onHistoryChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (s in history.strokes) drawStroke(canvas, s)
        current?.let { drawStroke(canvas, Stroke(tool, color, context.dp(if (tool == DrawTool.HIGHLIGHTER) strokeWidthDp * 3 else if (tool == DrawTool.ERASER) strokeWidthDp * 4 else strokeWidthDp), it)) }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        if (s.points.isEmpty()) return
        path.reset()
        s.points.forEachIndexed { i, pt ->
            val x = pt.x * width
            val y = pt.y * height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (s.points.size == 1) path.lineTo(s.points[0].x * width + 0.1f, s.points[0].y * height)
        val p = if (s.tool == DrawTool.ERASER) eraser else paint
        p.strokeWidth = s.width
        if (s.tool != DrawTool.ERASER) {
            p.color = s.color
            p.alpha = s.alpha
        }
        canvas.drawPath(path, p)
    }
}

/** Toolbar for the drawing overlay. */
class DrawingToolbar(
    context: Context,
    private val drawing: DrawingView,
    private val onHideToggle: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val toolButtons = HashMap<DrawTool, ImageButton>()
    private val undo: ImageButton
    private val redo: ImageButton
    private val tempBtn: ImageButton

    init {
        orientation = VERTICAL
        val pad = context.dpi(8)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(20f)
            setColor(0xF0151325.toInt())
            setStroke(context.dpi(1), 0x668B3DFF)
        }
        val row1 = row()
        toolButtons[DrawTool.PEN] = icon(row1, R.drawable.ic_ov_pen, "Pen") { select(DrawTool.PEN) }
        toolButtons[DrawTool.HIGHLIGHTER] = icon(row1, R.drawable.ic_ov_highlighter, "Highlighter") { select(DrawTool.HIGHLIGHTER) }
        toolButtons[DrawTool.ERASER] = icon(row1, R.drawable.ic_ov_eraser, "Eraser") { select(DrawTool.ERASER) }
        undo = icon(row1, R.drawable.ic_ov_undo, "Undo") { drawing.history.undo(); drawing.invalidate(); refresh() }
        redo = icon(row1, R.drawable.ic_ov_redo, "Redo") { drawing.history.redo(); drawing.invalidate(); refresh() }
        icon(row1, R.drawable.ic_ov_delete, "Clear") { drawing.history.clear(); drawing.invalidate(); refresh() }

        val row2 = row()
        for (c in COLORS) {
            val v = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(context.dpi(2), 0x33FFFFFF) }
                contentDescription = "Color"
                setOnClickListener { drawing.color = c; if (drawing.tool == DrawTool.ERASER) select(DrawTool.PEN) }
            }
            row2.addView(v, LayoutParams(context.dpi(26), context.dpi(26)).apply { setMargins(context.dpi(5), context.dpi(4), context.dpi(5), context.dpi(4)) })
        }

        val row3 = row()
        row3.addView(TextView(context).apply { text = "Size"; setTextColor(Color.WHITE); textSize = 12f })
        row3.addView(SeekBar(context).apply {
            max = 30
            progress = drawing.strokeWidthDp.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) { drawing.strokeWidthDp = value.coerceAtLeast(2).toFloat() }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }, LayoutParams(context.dpi(140), LayoutParams.WRAP_CONTENT))
        tempBtn = icon(row3, R.drawable.ic_ov_pin, "Persistent / temporary drawing") {
            drawing.temporary = !drawing.temporary
            refresh()
        }
        icon(row3, R.drawable.ic_ov_eye, "Hide or show drawing") { onHideToggle() }
        icon(row3, R.drawable.ic_ov_close, "Close drawing") { onClose() }

        drawing.onHistoryChanged = { refresh() }
        select(DrawTool.PEN)
    }

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        this@DrawingToolbar.addView(this)
    }

    private fun icon(parent: LinearLayout, res: Int, desc: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        contentDescription = desc
        background = null
        val p = context.dpi(8)
        setPadding(p, p, p, p)
        setOnClickListener { onClick() }
        parent.addView(this, LayoutParams(context.dpi(40), context.dpi(40)))
    }

    private fun select(tool: DrawTool) {
        drawing.tool = tool
        refresh()
    }

    private fun refresh() {
        toolButtons.forEach { (t, b) ->
            b.background = if (t == drawing.tool) GradientDrawable().apply { cornerRadius = context.dp(12f); setColor(0xFF8B3DFF.toInt()) } else null
        }
        undo.alpha = if (drawing.history.canUndo) 1f else 0.35f
        redo.alpha = if (drawing.history.canRedo) 1f else 0.35f
        tempBtn.setColorFilter(if (drawing.temporary) 0xFF22E4FF.toInt() else Color.WHITE)
        tempBtn.setImageResource(if (drawing.temporary) R.drawable.ic_ov_timer else R.drawable.ic_ov_pin)
    }

    companion object {
        val COLORS = intArrayOf(0xFFFF2D46.toInt(), 0xFFFFC23D.toInt(), 0xFF2EE59D.toInt(), 0xFF22E4FF.toInt(), 0xFF3D7BFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt())
    }
}

/** Big countdown digits in the middle of the screen (not touchable). */
class CountdownView(context: Context) : View(context) {
    var value = 0
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = context.dp(96f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(context.dp(16f), 0f, 0f, 0xFF8B3DFF.toInt())
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA0B0B12.toInt() }

    override fun onDraw(canvas: Canvas) {
        if (value <= 0) return
        val r = context.dp(80f)
        canvas.drawCircle(width / 2f, height / 2f, r, bg)
        canvas.drawText(value.toString(), width / 2f, height / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    }
}
