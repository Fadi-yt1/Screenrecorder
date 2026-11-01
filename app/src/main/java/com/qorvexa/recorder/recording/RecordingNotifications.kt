package com.qorvexa.recorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.qorvexa.recorder.MainActivity
import com.qorvexa.recorder.R
import com.qorvexa.recorder.core.recording.RecordingState

object RecordingNotifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_OVERLAY = "overlay"
    const val ID_RECORDING = 1001
    const val ID_ALERT = 1002
    const val ID_OVERLAY = 1003
    const val ID_SAVED = 1004

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_recording_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alerts_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OVERLAY, context.getString(R.string.channel_overlay), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.channel_overlay_desc)
                setShowBadge(false)
            },
        )
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags())

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(context, code, Intent(context, RecorderService::class.java).setAction(action), flags())

    fun recording(context: Context, status: RecorderStatus, controls: Boolean): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8B3DFF.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val cfg = status.config
        val detail = cfg?.let { "${it.size.shortSide}p · ${it.fps} FPS · ${status.engine?.label ?: ""}" } ?: ""
        when (status.state) {
            RecordingState.COUNTDOWN -> b.setContentTitle(context.getString(R.string.notif_countdown, status.countdown))
            RecordingState.PAUSED -> b.setContentTitle(context.getString(R.string.notif_paused)).setContentText(detail + " · " + formatElapsed(status.elapsedMs))
            RecordingState.RECORDING, RecordingState.RESUMING -> b.setContentTitle(context.getString(R.string.notif_recording))
                .setContentText(detail)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - status.elapsedMs)
            else -> b.setContentTitle("QORVEXA").setContentText(status.state.name.lowercase().replaceFirstChar { it.uppercase() } + "…")
        }
        if (controls && (status.state == RecordingState.RECORDING || status.state == RecordingState.PAUSED)) {
            if (status.state == RecordingState.PAUSED) {
                b.addAction(R.drawable.ic_ov_play, context.getString(R.string.action_resume), serviceAction(context, RecorderService.ACTION_RESUME, 1))
            } else {
                b.addAction(R.drawable.ic_ov_pause, context.getString(R.string.action_pause), serviceAction(context, RecorderService.ACTION_PAUSE, 2))
            }
            b.addAction(R.drawable.ic_ov_stop, context.getString(R.string.action_stop), serviceAction(context, RecorderService.ACTION_STOP, 3))
            b.addAction(R.drawable.ic_ov_camera, context.getString(R.string.action_screenshot), serviceAction(context, RecorderService.ACTION_SCREENSHOT, 4))
        } else if (status.state.isActive) {
            b.addAction(R.drawable.ic_ov_stop, context.getString(R.string.action_stop), serviceAction(context, RecorderService.ACTION_STOP, 3))
        }
        return b.build()
    }

    fun working(context: Context, title: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    fun alert(context: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, ID_ALERT, n)
    }

    fun saved(context: Context, title: String, name: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, ID_SAVED, n)
    }

    fun notify(context: Context, id: Int, n: Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(id, n) } // SecurityException if POST_NOTIFICATIONS was denied
    }

    fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
}
