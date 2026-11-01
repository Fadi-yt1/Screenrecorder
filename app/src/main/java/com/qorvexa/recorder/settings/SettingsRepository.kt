package com.qorvexa.recorder.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qorvexa.recorder.core.model.AudioBitrate
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.model.BitrateMode
import com.qorvexa.recorder.core.model.BitrateOption
import com.qorvexa.recorder.core.model.ChannelMode
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.FpsOption
import com.qorvexa.recorder.core.model.GamingPreset
import com.qorvexa.recorder.core.model.MicPreset
import com.qorvexa.recorder.core.model.MicProcessing
import com.qorvexa.recorder.core.model.OptimizationMode
import com.qorvexa.recorder.core.model.OrientationMode
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.model.ResolutionPreset
import com.qorvexa.recorder.core.model.SampleRate
import com.qorvexa.recorder.core.model.ThemeMode
import com.qorvexa.recorder.core.model.VideoCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "qorvexa_settings")

enum class BubbleShape { CIRCLE, ROUNDED_SQUARE }
enum class BubbleIcon { LOGO, RECORD, CAMERA }
enum class BubbleSide { LEFT, RIGHT }

data class OverlaySettings(
    val enabled: Boolean = false,
    val sizeDp: Int = 56,
    val iconScalePercent: Int = 60,
    val opacityPercent: Int = 90,
    val backgroundColor: Int = 0xFF151325.toInt(),
    val iconColor: Int = 0xFFFFFFFF.toInt(),
    val shape: BubbleShape = BubbleShape.CIRCLE,
    val icon: BubbleIcon = BubbleIcon.LOGO,
    val side: BubbleSide = BubbleSide.RIGHT,
    val yFraction: Float = 0.35f,
    val snapToEdge: Boolean = true,
    val animate: Boolean = true,
    val autoHide: Boolean = true,
)

data class AppState(
    val onboardingDone: Boolean = false,
    val recordingCounter: Int = 0,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val store get() = context.dataStore

    val settings: Flow<RecordingSettings> = store.data.map { it.toSettings() }
    val overlay: Flow<OverlaySettings> = store.data.map { it.toOverlay() }
    val appState: Flow<AppState> = store.data.map {
        AppState(it[K.onboarding] ?: false, it[K.counter] ?: 0)
    }

    suspend fun current(): RecordingSettings = settings.first()
    suspend fun currentOverlay(): OverlaySettings = overlay.first()

    suspend fun update(transform: (RecordingSettings) -> RecordingSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    suspend fun updateOverlay(transform: (OverlaySettings) -> OverlaySettings) {
        store.edit { prefs -> prefs.writeOverlay(transform(prefs.toOverlay())) }
    }

    suspend fun reset() {
        store.edit { prefs ->
            val overlay = prefs.toOverlay()
            val counter = prefs[K.counter] ?: 0
            prefs.clear()
            prefs[K.onboarding] = true
            prefs[K.counter] = counter
            prefs.writeOverlay(overlay.copy(enabled = false))
        }
    }

    suspend fun setOnboardingDone() = store.edit { it[K.onboarding] = true }

    /** Returns the next recording counter and persists it. */
    suspend fun nextCounter(minimum: Int): Int {
        var value = 0
        store.edit {
            value = maxOf((it[K.counter] ?: 0) + 1, minimum)
            it[K.counter] = value
        }
        return value
    }

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val counter = intPreferencesKey("recording_counter")
        val resolution = stringPreferencesKey("resolution")
        val customW = intPreferencesKey("custom_w")
        val customH = intPreferencesKey("custom_h")
        val fps = stringPreferencesKey("fps")
        val bitrate = stringPreferencesKey("bitrate")
        val customBitrate = intPreferencesKey("custom_bitrate_mbps")
        val codec = stringPreferencesKey("codec")
        val bitrateMode = stringPreferencesKey("bitrate_mode")
        val keyframe = intPreferencesKey("keyframe_sec")
        val orientation = stringPreferencesKey("orientation")
        val preferHw = booleanPreferencesKey("prefer_hw")
        val audioSource = stringPreferencesKey("audio_source")
        val audioBitrate = stringPreferencesKey("audio_bitrate")
        val sampleRate = stringPreferencesKey("sample_rate")
        val channels = stringPreferencesKey("channels")
        val micPreset = stringPreferencesKey("mic_preset")
        val ns = booleanPreferencesKey("mic_ns")
        val aec = booleanPreferencesKey("mic_aec")
        val agc = booleanPreferencesKey("mic_agc")
        val voice = booleanPreferencesKey("mic_voice")
        val micGain = intPreferencesKey("mic_gain")
        val internalVol = intPreferencesKey("internal_volume")
        val micDevice = intPreferencesKey("mic_device")
        val engine = stringPreferencesKey("engine")
        val optimization = stringPreferencesKey("optimization")
        val countdown = intPreferencesKey("countdown")
        val touches = booleanPreferencesKey("touches_hint")
        val watermark = booleanPreferencesKey("watermark")
        val maxDuration = intPreferencesKey("max_duration")
        val maxSize = intPreferencesKey("max_size")
        val lowStorage = booleanPreferencesKey("low_storage")
        val minFree = intPreferencesKey("min_free")
        val battery = booleanPreferencesKey("battery_protect")
        val batteryLevel = intPreferencesKey("battery_level")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val hideOverlay = booleanPreferencesKey("hide_overlay")
        val shake = booleanPreferencesKey("shake_start")
        val fileName = stringPreferencesKey("file_pattern")
        val remember = booleanPreferencesKey("remember_last")
        val notifControls = booleanPreferencesKey("notif_controls")
        val gaming = booleanPreferencesKey("gaming_mode")
        val gamingPreset = stringPreferencesKey("gaming_preset")
        val theme = stringPreferencesKey("theme")

        val ovEnabled = booleanPreferencesKey("ov_enabled")
        val ovSize = intPreferencesKey("ov_size")
        val ovIconScale = intPreferencesKey("ov_icon_scale")
        val ovOpacity = intPreferencesKey("ov_opacity")
        val ovBg = intPreferencesKey("ov_bg")
        val ovIconColor = intPreferencesKey("ov_icon_color")
        val ovShape = stringPreferencesKey("ov_shape")
        val ovIcon = stringPreferencesKey("ov_icon")
        val ovSide = stringPreferencesKey("ov_side")
        val ovY = intPreferencesKey("ov_y_permille")
        val ovSnap = booleanPreferencesKey("ov_snap")
        val ovAnimate = booleanPreferencesKey("ov_animate")
        val ovAutoHide = booleanPreferencesKey("ov_autohide")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun Preferences.toSettings(): RecordingSettings {
        val d = RecordingSettings()
        return RecordingSettings(
            resolution = enum(K.resolution, d.resolution),
            customWidth = this[K.customW] ?: d.customWidth,
            customHeight = this[K.customH] ?: d.customHeight,
            fps = enum(K.fps, d.fps),
            bitrate = enum(K.bitrate, d.bitrate),
            customBitrateMbps = this[K.customBitrate] ?: d.customBitrateMbps,
            codec = enum(K.codec, d.codec),
            bitrateMode = enum(K.bitrateMode, d.bitrateMode),
            keyframeIntervalSec = this[K.keyframe] ?: d.keyframeIntervalSec,
            orientation = enum(K.orientation, d.orientation),
            preferHardwareEncoder = this[K.preferHw] ?: d.preferHardwareEncoder,
            audioSource = enum(K.audioSource, d.audioSource),
            audioBitrate = enum(K.audioBitrate, d.audioBitrate),
            sampleRate = enum(K.sampleRate, d.sampleRate),
            channels = enum(K.channels, d.channels),
            micPreset = enum(K.micPreset, d.micPreset),
            micProcessing = MicProcessing(
                noiseSuppression = this[K.ns] ?: d.micProcessing.noiseSuppression,
                echoCancellation = this[K.aec] ?: d.micProcessing.echoCancellation,
                autoGain = this[K.agc] ?: d.micProcessing.autoGain,
                voiceEnhancement = this[K.voice] ?: d.micProcessing.voiceEnhancement,
            ),
            micGainPercent = this[K.micGain] ?: d.micGainPercent,
            internalVolumePercent = this[K.internalVol] ?: d.internalVolumePercent,
            micDeviceId = this[K.micDevice] ?: d.micDeviceId,
            engine = enum(K.engine, d.engine),
            optimization = enum(K.optimization, d.optimization),
            countdownSec = this[K.countdown] ?: d.countdownSec,
            showTouchesHint = this[K.touches] ?: d.showTouchesHint,
            watermark = this[K.watermark] ?: d.watermark,
            maxDurationMin = this[K.maxDuration] ?: d.maxDurationMin,
            maxFileSizeMb = this[K.maxSize] ?: d.maxFileSizeMb,
            lowStorageProtection = this[K.lowStorage] ?: d.lowStorageProtection,
            minFreeStorageMb = this[K.minFree] ?: d.minFreeStorageMb,
            batteryProtection = this[K.battery] ?: d.batteryProtection,
            batteryStopPercent = this[K.batteryLevel] ?: d.batteryStopPercent,
            keepScreenOn = this[K.keepOn] ?: d.keepScreenOn,
            hideOverlayWhileRecording = this[K.hideOverlay] ?: d.hideOverlayWhileRecording,
            shakeToStart = this[K.shake] ?: d.shakeToStart,
            fileNamePattern = this[K.fileName] ?: d.fileNamePattern,
            rememberLastConfig = this[K.remember] ?: d.rememberLastConfig,
            notificationControls = this[K.notifControls] ?: d.notificationControls,
            gamingMode = this[K.gaming] ?: d.gamingMode,
            gamingPreset = enum(K.gamingPreset, d.gamingPreset),
            theme = enum(K.theme, d.theme),
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: RecordingSettings) {
        this[K.resolution] = s.resolution.name
        this[K.customW] = s.customWidth
        this[K.customH] = s.customHeight
        this[K.fps] = s.fps.name
        this[K.bitrate] = s.bitrate.name
        this[K.customBitrate] = s.customBitrateMbps
        this[K.codec] = s.codec.name
        this[K.bitrateMode] = s.bitrateMode.name
        this[K.keyframe] = s.keyframeIntervalSec
        this[K.orientation] = s.orientation.name
        this[K.preferHw] = s.preferHardwareEncoder
        this[K.audioSource] = s.audioSource.name
        this[K.audioBitrate] = s.audioBitrate.name
        this[K.sampleRate] = s.sampleRate.name
        this[K.channels] = s.channels.name
        this[K.micPreset] = s.micPreset.name
        this[K.ns] = s.micProcessing.noiseSuppression
        this[K.aec] = s.micProcessing.echoCancellation
        this[K.agc] = s.micProcessing.autoGain
        this[K.voice] = s.micProcessing.voiceEnhancement
        this[K.micGain] = s.micGainPercent
        this[K.internalVol] = s.internalVolumePercent
        this[K.micDevice] = s.micDeviceId
        this[K.engine] = s.engine.name
        this[K.optimization] = s.optimization.name
        this[K.countdown] = s.countdownSec
        this[K.touches] = s.showTouchesHint
        this[K.watermark] = s.watermark
        this[K.maxDuration] = s.maxDurationMin
        this[K.maxSize] = s.maxFileSizeMb
        this[K.lowStorage] = s.lowStorageProtection
        this[K.minFree] = s.minFreeStorageMb
        this[K.battery] = s.batteryProtection
        this[K.batteryLevel] = s.batteryStopPercent
        this[K.keepOn] = s.keepScreenOn
        this[K.hideOverlay] = s.hideOverlayWhileRecording
        this[K.shake] = s.shakeToStart
        this[K.fileName] = s.fileNamePattern
        this[K.remember] = s.rememberLastConfig
        this[K.notifControls] = s.notificationControls
        this[K.gaming] = s.gamingMode
        this[K.gamingPreset] = s.gamingPreset.name
        this[K.theme] = s.theme.name
    }

    private fun Preferences.toOverlay(): OverlaySettings {
        val d = OverlaySettings()
        return OverlaySettings(
            enabled = this[K.ovEnabled] ?: d.enabled,
            sizeDp = this[K.ovSize] ?: d.sizeDp,
            iconScalePercent = this[K.ovIconScale] ?: d.iconScalePercent,
            opacityPercent = this[K.ovOpacity] ?: d.opacityPercent,
            backgroundColor = this[K.ovBg] ?: d.backgroundColor,
            iconColor = this[K.ovIconColor] ?: d.iconColor,
            shape = enum(K.ovShape, d.shape),
            icon = enum(K.ovIcon, d.icon),
            side = enum(K.ovSide, d.side),
            yFraction = (this[K.ovY] ?: (d.yFraction * 1000).toInt()) / 1000f,
            snapToEdge = this[K.ovSnap] ?: d.snapToEdge,
            animate = this[K.ovAnimate] ?: d.animate,
            autoHide = this[K.ovAutoHide] ?: d.autoHide,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeOverlay(o: OverlaySettings) {
        this[K.ovEnabled] = o.enabled
        this[K.ovSize] = o.sizeDp
        this[K.ovIconScale] = o.iconScalePercent
        this[K.ovOpacity] = o.opacityPercent
        this[K.ovBg] = o.backgroundColor
        this[K.ovIconColor] = o.iconColor
        this[K.ovShape] = o.shape.name
        this[K.ovIcon] = o.icon.name
        this[K.ovSide] = o.side.name
        this[K.ovY] = (o.yFraction * 1000).toInt()
        this[K.ovSnap] = o.snapToEdge
        this[K.ovAnimate] = o.animate
        this[K.ovAutoHide] = o.autoHide
    }
}
