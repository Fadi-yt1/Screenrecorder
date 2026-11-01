package com.qorvexa.recorder.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import com.qorvexa.recorder.permissions.Permissions
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.qorvexa.recorder.MainActivity
import com.qorvexa.recorder.R
import com.qorvexa.recorder.core.device.ShakeDetector
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.recording.RecordingState
import com.qorvexa.recorder.recording.RecorderController
import com.qorvexa.recorder.recording.RecorderStatus
import com.qorvexa.recorder.recording.RecordingNotifications
import com.qorvexa.recorder.settings.BubbleSide
import com.qorvexa.recorder.settings.OverlaySettings
import com.qorvexa.recorder.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * Floating controls above other apps (needs "Display over other apps").
 * Windows: bubble, expanded panel (+ touch-blocking scrim), drawing canvas + toolbar, countdown.
 */
@AndroidEntryPoint
class OverlayService : Service(), SensorEventListener {

    @Inject lateinit var controller: RecorderController
    @Inject lateinit var settingsRepo: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager

    private var bubble: FloatingBubbleView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: ControlPanel? = null
    private var scrim: View? = null
    private var drawing: DrawingView? = null
    private var drawingParams: WindowManager.LayoutParams? = null
    private var toolbar: DrawingToolbar? = null
    private var countdown: CountdownView? = null

    private var overlay = OverlaySettings()
    private var recSettings = RecordingSettings()
    private var status = RecorderStatus()
    private var autoHideJob: Job? = null
    private var shake: ShakeDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val n = NotificationCompat.Builder(this, RecordingNotifications.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_overlay))
            .setContentText(getString(R.string.notif_overlay_text))
            .setOngoing(true)
            .setContentIntent(RecordingNotifications.openApp(this))
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, RecordingNotifications.ID_OVERLAY, n, type) }
        if (!Permissions.canDrawOverlays(this)) {
            stopSelf(); return
        }
        scope.launch {
            combine(settingsRepo.overlay, settingsRepo.settings, controller.status) { o, s, st -> Triple(o, s, st) }
                .collect { (o, s, st) -> render(o, s, st) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun windowType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun render(o: OverlaySettings, s: RecordingSettings, st: RecorderStatus) {
        val firstRender = bubble == null
        overlay = o
        recSettings = s
        status = st
        if (firstRender) addBubble()
        val b = bubble ?: return
        b.settings = o
        val recording = st.state == RecordingState.RECORDING || st.state == RecordingState.PAUSED || st.state == RecordingState.RESUMING
        b.recording = recording
        b.paused = st.state == RecordingState.PAUSED
        val size = dpi(o.sizeDp)
        if (bubbleParams.width != size) {
            bubbleParams.width = size
            bubbleParams.height = size
        }
        val hide = recording && s.hideOverlayWhileRecording
        b.visibility = if (hide) View.GONE else View.VISIBLE
        b.alpha = o.opacityPercent / 100f
        // Keep the screen awake through the overlay window while recording.
        val keepOn = recording && s.keepScreenOn
        val f = bubbleParams.flags
        bubbleParams.flags = if (keepOn) f or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else f and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (firstRender) snapToSide(animate = false)
        update(b, bubbleParams)

        panel?.bind(recording, st.state == RecordingState.PAUSED, RecordingNotifications.formatElapsed(st.elapsedMs))
        renderCountdown(if (st.state == RecordingState.COUNTDOWN) st.countdown else 0)
        updateShake(s.shakeToStart && !st.state.isActive)
        scheduleAutoHide()
    }

    // ---------------- Bubble ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dpi(overlay.sizeDp)
        bubbleParams = WindowManager.LayoutParams(
            size, size, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = (screenHeight() * overlay.yFraction).toInt()
        }
        val v = FloatingBubbleView(this)
        v.contentDescription = "QORVEXA floating record button"
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    autoHideJob?.cancel(); unTuck()
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; collapsePanel() }
                    if (dragging) {
                        bubbleParams.x = (startX + dx).toInt()
                        bubbleParams.y = (startY + dy).toInt().coerceIn(0, screenHeight() - bubbleParams.height)
                        update(v, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val side = if (bubbleParams.x + bubbleParams.width / 2 < screenWidth() / 2) BubbleSide.LEFT else BubbleSide.RIGHT
                        val y = bubbleParams.y.toFloat() / screenHeight()
                        scope.launch { settingsRepo.updateOverlay { it.copy(side = side, yFraction = y) } }
                        if (overlay.snapToEdge) snapToSide(animate = overlay.animate, side = side)
                    } else {
                        v.performClick()
                        togglePanel()
                    }
                    scheduleAutoHide()
                }
            }
            true
        }
        bubble = v
        runCatching { wm.addView(v, bubbleParams) }
    }

    private fun snapToSide(animate: Boolean, side: BubbleSide = overlay.side) {
        val b = bubble ?: return
        val target = if (side == BubbleSide.LEFT) 0 else screenWidth() - bubbleParams.width
        if (!animate) {
            bubbleParams.x = target
            update(b, bubbleParams); return
        }
        ValueAnimator.ofInt(bubbleParams.x, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { bubbleParams.x = it.animatedValue as Int; update(b, bubbleParams) }
            start()
        }
    }

    /** Auto-hide: after 3 s idle the bubble tucks half off-screen and dims. */
    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        if (!overlay.autoHide || panel != null || drawing != null) return
        autoHideJob = scope.launch {
            delay(3000)
            val b = bubble ?: return@launch
            val half = bubbleParams.width / 2
            bubbleParams.x = if (overlay.side == BubbleSide.LEFT) -half else screenWidth() - half
            b.alpha = (overlay.opacityPercent / 100f) * 0.5f
            update(b, bubbleParams)
        }
    }

    private fun unTuck() {
        val b = bubble ?: return
        b.alpha = overlay.opacityPercent / 100f
        if (bubbleParams.x < 0 || bubbleParams.x > screenWidth() - bubbleParams.width) snapToSide(animate = false)
    }

    // ---------------- Panel ----------------

    private fun togglePanel() = if (panel == null) expandPanel() else collapsePanel()

    private fun expandPanel() {
        autoHideJob?.cancel()
        // A transparent scrim catches taps outside the menu so they don't hit the app underneath.
        val s = View(this).apply { setOnClickListener { collapsePanel() } }
        val sp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(s, sp) }
        scrim = s
        val p = ControlPanel(this, ::onPanelAction)
        val recording = status.state == RecordingState.RECORDING || status.state == RecordingState.PAUSED
        p.bind(recording, status.state == RecordingState.PAUSED, RecordingNotifications.formatElapsed(status.elapsedMs))
        val pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (bubbleParams.x + bubbleParams.width / 2 < screenWidth() / 2) Gravity.START else Gravity.END)
            x = bubbleParams.width + dpi(6)
            y = bubbleParams.y + bubbleParams.height / 2 - dpi(28)
        }
        runCatching { wm.addView(p, pp) }
        panel = p
        // Keep the bubble above the scrim.
        bubble?.let { runCatching { wm.removeView(it); wm.addView(it, bubbleParams) } }
    }

    private fun collapsePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        scrim?.let { runCatching { wm.removeView(it) } }
        panel = null
        scrim = null
        scheduleAutoHide()
    }

    private fun onPanelAction(a: ControlPanel.Action) {
        when (a) {
            ControlPanel.Action.RECORD -> { collapsePanel(); controller.requestStart(this) }
            ControlPanel.Action.PAUSE -> controller.pause(this)
            ControlPanel.Action.RESUME -> controller.resume(this)
            ControlPanel.Action.STOP -> { collapsePanel(); controller.stop(this) }
            ControlPanel.Action.SCREENSHOT -> {
                collapsePanel()
                // Give the panel a frame to disappear so it isn't in the screenshot.
                scope.launch { delay(250); controller.requestScreenshot(this@OverlayService) }
            }
            ControlPanel.Action.DRAW -> { collapsePanel(); openDrawing() }
            ControlPanel.Action.SETTINGS -> {
                collapsePanel()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ROUTE, "settings"))
            }
            ControlPanel.Action.CLOSE -> {
                collapsePanel()
                scope.launch { settingsRepo.updateOverlay { it.copy(enabled = false) } }
                stopSelf()
            }
        }
    }

    // ---------------- Drawing ----------------

    private fun openDrawing() {
        if (drawing != null) return
        autoHideJob?.cancel()
        val d = DrawingView(this)
        val dp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(d, dp) }
        drawing = d
        drawingParams = dp
        val t = DrawingToolbar(this, d, onHideToggle = ::toggleDrawingVisibility, onClose = ::closeDrawing)
        val tp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpi(32)
        }
        runCatching { wm.addView(t, tp) }
        toolbar = t
    }

    /** Hidden: strokes disappear and touches pass through to the app. Shown: drawing resumes. */
    private fun toggleDrawingVisibility() {
        val d = drawing ?: return
        val p = drawingParams ?: return
        val hidden = d.visibility == View.VISIBLE
        d.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        p.flags = if (hidden) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        update(d, p)
    }

    private fun closeDrawing() {
        toolbar?.let { runCatching { wm.removeView(it) } }
        drawing?.let { runCatching { wm.removeView(it) } }
        toolbar = null
        drawing = null
        drawingParams = null
        scheduleAutoHide()
    }

    // ---------------- Countdown ----------------

    private fun renderCountdown(value: Int) {
        if (value <= 0) {
            countdown?.let { runCatching { wm.removeView(it) } }
            countdown = null
            return
        }
        val c = countdown ?: CountdownView(this).also {
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { wm.addView(it, p) }
            countdown = it
        }
        c.value = value
    }

    // ---------------- Shake to start ----------------

    private var sensorRegistered = false

    private fun updateShake(enabled: Boolean) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (enabled && !sensorRegistered) {
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            shake = ShakeDetector()
            sensorRegistered = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        } else if (!enabled && sensorRegistered) {
            sm.unregisterListener(this)
            sensorRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val d = shake ?: return
        if (d.onSample(event.values[0], event.values[1], event.values[2], System.currentTimeMillis())) {
            if (!controller.status.value.state.isActive) controller.requestStart(this)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------- Helpers ----------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the bubble on screen after rotation; drawings are normalised and redraw themselves.
        bubbleParams.y = (screenHeight() * overlay.yFraction).toInt().coerceIn(0, screenHeight() - bubbleParams.height)
        snapToSide(animate = false)
        drawing?.invalidate()
    }

    @Suppress("DEPRECATION")
    private fun screenWidth(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width() else wm.defaultDisplay.width

    @Suppress("DEPRECATION")
    private fun screenHeight(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height() else wm.defaultDisplay.height

    private fun update(v: View, p: WindowManager.LayoutParams) {
        if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, p) }
    }

    override fun onDestroy() {
        updateShake(false)
        collapsePanel()
        closeDrawing()
        renderCountdown(0)
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.qorvexa.recorder.overlay.STOP"

        fun start(context: Context) {
            if (!Permissions.canDrawOverlays(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
