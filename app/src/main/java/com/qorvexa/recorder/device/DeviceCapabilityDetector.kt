package com.qorvexa.recorder.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.Display
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.model.AudioEffectsSupport
import com.qorvexa.recorder.core.model.AudioInputDevice
import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EncoderInfo
import com.qorvexa.recorder.core.model.ThermalStatus
import com.qorvexa.recorder.core.model.VideoCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads real hardware information. Nothing here is hardcoded per device model;
 * every value comes from platform APIs or sysfs. Must be called off the main thread.
 */
@Singleton
class DeviceCapabilityDetector @Inject constructor(@ApplicationContext private val context: Context) {

    fun detect(): DeviceCapabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gl = queryGl(am)
        val display = displayInfo()
        val (vulkan, vulkanVersion) = vulkanInfo()
        val storage = storageInfo()
        val battery = batteryInfo()
        val inputs = inputDevices()
        return DeviceCapabilities(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuMaxFreqMhz = maxCpuFreqMhz(),
            socName = socName(),
            totalRamMb = mem.totalMem / (1024 * 1024),
            availRamMb = mem.availMem / (1024 * 1024),
            lowRamDevice = am.isLowRamDevice,
            glRenderer = gl.renderer,
            glVendor = gl.vendor,
            glEsVersion = gl.version,
            vulkanSupported = vulkan,
            vulkanVersion = vulkanVersion,
            displayWidth = display.width,
            displayHeight = display.height,
            densityDpi = display.dpi,
            refreshRate = display.refresh,
            supportedRefreshRates = display.refreshRates,
            encoders = encoders(),
            playbackCaptureSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            micAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            stereoMic = inputs.any { it.stereo },
            inputDevices = inputs,
            effects = AudioEffectsSupport(
                noiseSuppressor = safe { NoiseSuppressor.isAvailable() } ?: false,
                echoCanceler = safe { AcousticEchoCanceler.isAvailable() } ?: false,
                autoGain = safe { AutomaticGainControl.isAvailable() } ?: false,
            ),
            storageFreeBytes = storage.first,
            storageTotalBytes = storage.second,
            thermalStatus = thermalStatus(),
            batteryPercent = battery.first,
            charging = battery.second,
        )
    }

    // ---------- CPU ----------

    private fun maxCpuFreqMhz(): Int {
        var max = 0
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val khz = safe { f.readText().trim().toInt() } ?: continue
            max = maxOf(max, khz / 1000)
        }
        return max
    }

    private fun socName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }
            if (soc.isNotEmpty()) return soc.joinToString(" ")
        }
        return Build.HARDWARE
    }

    // ---------- GPU ----------

    data class GlInfo(val renderer: String, val vendor: String, val version: String)

    /** Creates a throwaway 1×1 pbuffer context to read the real GL strings. */
    private fun queryGl(am: ActivityManager): GlInfo {
        val reported = am.deviceConfigurationInfo.glEsVersion ?: ""
        return safe {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(display, ver, 0, ver, 1))
            try {
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0)
                val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                val surf = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                EGL14.eglMakeCurrent(display, surf, surf, ctx)
                val info = GlInfo(
                    renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "",
                    vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "",
                    version = reported.ifBlank { GLES20.glGetString(GLES20.GL_VERSION)?.let { Regex("ES (\\d+\\.\\d+)").find(it)?.groupValues?.get(1) } ?: "2.0" },
                )
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
                info
            } finally {
                EGL14.eglTerminate(display)
            }
        } ?: GlInfo("", "", reported)
    }

    private fun vulkanInfo(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false to ""
        val pm = context.packageManager
        val feature = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?: return false to ""
        val v = feature.version
        return true to "${v shr 22}.${(v shr 12) and 0x3ff}"
    }

    // ---------- Display ----------

    data class DisplayInfo(val width: Int, val height: Int, val dpi: Int, val refresh: Float, val refreshRates: List<Float>)

    @Suppress("DEPRECATION")
    private fun displayInfo(): DisplayInfo {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val rates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display.supportedModes.map { it.refreshRate }.distinct().sorted()
        } else listOf(display.refreshRate)
        return DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.refreshRate, rates)
    }

    // ---------- Encoders ----------

    private fun encoders(): List<EncoderInfo> {
        val list = safe { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() } ?: return emptyList()
        val out = ArrayList<EncoderInfo>()
        for (info in list) {
            if (!info.isEncoder) continue
            for (codec in VideoCodec.entries) {
                if (info.supportedTypes.none { it.equals(codec.mime, ignoreCase = true) }) continue
                safe { describe(info, codec) }?.let { out += it }
            }
        }
        // Hardware first, then by name for stable ordering.
        return out.sortedWith(compareByDescending<EncoderInfo> { it.hardware }.thenBy { it.name })
    }

    private fun describe(info: MediaCodecInfo, codec: VideoCodec): EncoderInfo {
        val caps = info.getCapabilitiesForType(codec.mime)
        val vc = caps.videoCapabilities
        val ec = caps.encoderCapabilities
        fun maxFps(w: Int, h: Int): Int {
            val ok = safe { vc.isSizeSupported(w, h) } == true || safe { vc.isSizeSupported(h, w) } == true
            if (!ok) return 0
            val range = safe { vc.getSupportedFrameRatesFor(w, h) } ?: safe { vc.getSupportedFrameRatesFor(h, w) }
            return range?.upper?.toInt() ?: 0
        }
        return EncoderInfo(
            name = info.name,
            codec = codec,
            hardware = isHardware(info),
            maxWidth = vc.supportedWidths.upper,
            maxHeight = vc.supportedHeights.upper,
            maxFpsAt1080 = maxFps(1920, 1080).takeIf { it > 0 } ?: maxFps(1280, 720),
            maxFpsAt2160 = maxFps(3840, 2160),
            maxBitrate = vc.bitrateRange.upper,
            supportsCbr = ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
            supportsVbr = ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
            sizeRateCheck = { w, h, fps ->
                // Exact platform answer, tolerant of the encoder only describing one orientation.
                (safe { vc.areSizeAndRateSupported(w, h, fps.toDouble()) } == true) ||
                    (safe { vc.areSizeAndRateSupported(h, w, fps.toDouble()) } == true && safe { vc.isSizeSupported(w, h) } == true)
            },
        )
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("omx.ffmpeg"))
    }

    // ---------- Audio ----------

    private fun inputDevices(): List<AudioInputDevice> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { d ->
            val type = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB microphone"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && d.type == AudioDeviceInfo.TYPE_USB_HEADSET) "USB headset" else null
            } ?: return@mapNotNull null
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: type
            AudioInputDevice(d.id, name, type, d.channelCounts.any { it >= 2 })
        }.distinctBy { it.id }
    }

    // ---------- Storage / power ----------

    fun storageInfo(): Pair<Long, Long> = safe {
        val fs = StatFs(Environment.getExternalStorageDirectory().path)
        fs.availableBytes to fs.totalBytes
    } ?: (0L to 0L)

    fun thermalStatus(): ThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalStatus.UNKNOWN
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return ThermalStatus.fromPlatform(pm.currentThermalStatus)
    }

    fun batteryInfo(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return pct to charging
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.d("Detect", "probe failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }
}
