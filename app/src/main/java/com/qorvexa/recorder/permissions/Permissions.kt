package com.qorvexa.recorder.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.qorvexa.recorder.core.model.SupportStatus

/** Central place for runtime-permission checks across API levels. */
object Permissions {
    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Overlay permission is granted at install time before Android 6.0. */
    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            appSettingsIntent(context)
        }

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun micGranted(context: Context) = granted(context, Manifest.permission.RECORD_AUDIO)

    fun notificationsGranted(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** Permission needed to list recordings made before a reinstall. */
    fun mediaReadPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun mediaReadGranted(context: Context) = mediaReadPermissions().all { granted(context, it) }

    /** "Show taps" is a developer option apps can read but not change. */
    fun showTouchesEnabled(context: Context): Boolean =
        runCatching { Settings.System.getInt(context.contentResolver, "show_touches", 0) == 1 }.getOrDefault(false)

    fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    fun status(granted: Boolean): SupportStatus = if (granted) SupportStatus.SUPPORTED else SupportStatus.PERMISSION_REQUIRED
}
