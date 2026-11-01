package com.qorvexa.recorder.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Invisible activity that collects runtime permissions and the screen-capture consent,
 * then hands the result to [RecorderService]. Used by the app, the floating button and shake-to-start,
 * because consent dialogs can only be launched from an activity.
 */
@AndroidEntryPoint
class ProjectionRequestActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var controller: RecorderController

    private var mode = MODE_RECORD

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestProjection()
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val action = if (mode == MODE_SCREENSHOT) RecorderService.ACTION_SCREENSHOT_ONESHOT else RecorderService.ACTION_START
            val i = Intent(this, RecorderService::class.java)
                .setAction(action)
                .putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(RecorderService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(this, i)
        } else if (mode == MODE_RECORD) {
            controller.cancelRequest()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_RECORD)
        if (savedInstanceState != null) return // results will be delivered to the registered callbacks
        lifecycleScope.launch {
            val needed = mutableListOf<String>()
            if (mode == MODE_RECORD && settings.current().audioSource != AudioSource.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
                needed += Manifest.permission.RECORD_AUDIO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
            if (needed.isEmpty()) requestProjection() else permissions.launch(needed.toTypedArray())
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            controller.fail("Storage permission is required to save recordings on this Android version.")
            finish(); return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            controller.fail("Screen capture isn't available on this device.")
            finish()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_RECORD = 0
        const val MODE_SCREENSHOT = 1

        fun intent(context: Context, mode: Int): Intent =
            Intent(context, ProjectionRequestActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
