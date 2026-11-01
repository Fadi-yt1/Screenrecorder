package com.qorvexa.recorder.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qorvexa.recorder.core.device.DeviceProfile
import com.qorvexa.recorder.core.device.GamingPresets
import com.qorvexa.recorder.core.device.ProfileCalculator
import com.qorvexa.recorder.core.engine.EngineSelector
import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.GamingPreset
import com.qorvexa.recorder.core.model.OptimizationMode
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.video.ResolvedVideoConfig
import com.qorvexa.recorder.core.video.VideoConfigResolver
import com.qorvexa.recorder.device.DeviceRepository
import com.qorvexa.recorder.library.LibraryItem
import com.qorvexa.recorder.library.LibraryRepository
import com.qorvexa.recorder.overlay.OverlayService
import com.qorvexa.recorder.recording.RecorderController
import com.qorvexa.recorder.recording.RecorderStatus
import com.qorvexa.recorder.settings.OverlaySettings
import com.qorvexa.recorder.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared state for the main screens: settings, hardware, recorder status and the library. */
@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val deviceRepo: DeviceRepository,
    private val library: LibraryRepository,
    val controller: RecorderController,
) : ViewModel() {

    val settings: StateFlow<RecordingSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RecordingSettings())
    val overlay: StateFlow<OverlaySettings> = settingsRepo.overlay.stateIn(viewModelScope, SharingStarted.Eagerly, OverlaySettings())
    val caps: StateFlow<DeviceCapabilities?> = deviceRepo.capabilities
    val status: StateFlow<RecorderStatus> = controller.status
    val libraryItems: StateFlow<List<LibraryItem>> = library.items

    /** What the next recording will actually use, after validating against hardware. */
    val preview: StateFlow<Preview?> = combine(settings, caps) { s, c ->
        c?.let {
            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            Preview(VideoConfigResolver.resolve(s, it, landscape), EngineSelector.select(s, it), ProfileCalculator.profile(it))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class Preview(val config: ResolvedVideoConfig, val engine: EngineType, val profile: DeviceProfile)

    init {
        viewModelScope.launch {
            library.reload()
            // Bring the floating button back if the user had it on.
            if (settingsRepo.currentOverlay().enabled) OverlayService.start(context)
        }
    }

    fun update(manualVideoChange: Boolean = false, transform: (RecordingSettings) -> RecordingSettings) = viewModelScope.launch {
        settingsRepo.update {
            val next = transform(it)
            if (manualVideoChange) next.copy(optimization = OptimizationMode.CUSTOM) else next
        }
    }

    fun updateOverlay(transform: (OverlaySettings) -> OverlaySettings) = viewModelScope.launch { settingsRepo.updateOverlay(transform) }

    fun applyOptimization(mode: OptimizationMode) = viewModelScope.launch {
        val c = deviceRepo.get()
        settingsRepo.update { ProfileCalculator.recommend(mode, c, it) }
    }

    fun applyGamingPreset(preset: GamingPreset) = viewModelScope.launch {
        val c = deviceRepo.get()
        settingsRepo.update { GamingPresets.apply(preset, c, it).copy(optimization = OptimizationMode.GAMING) }
    }

    fun setGamingMode(on: Boolean) = viewModelScope.launch {
        if (on) applyGamingPreset(settings.value.gamingPreset)
        else {
            val c = deviceRepo.get()
            settingsRepo.update { ProfileCalculator.recommend(OptimizationMode.AUTOMATIC, c, it.copy(gamingMode = false)) }
        }
    }

    fun resetSettings() = viewModelScope.launch {
        settingsRepo.reset()
        val c = deviceRepo.get()
        settingsRepo.update { ProfileCalculator.recommend(OptimizationMode.AUTOMATIC, c, it) }
    }

    fun refreshDevice() = viewModelScope.launch(Dispatchers.IO) { deviceRepo.quickRefresh() }
    fun redetect() = viewModelScope.launch { deviceRepo.refresh() }
    fun reloadLibrary() = viewModelScope.launch { library.reload() }

    fun setOverlayEnabled(on: Boolean) = viewModelScope.launch {
        settingsRepo.updateOverlay { it.copy(enabled = on) }
        if (on) OverlayService.start(context) else OverlayService.stop(context)
    }

    fun startRecording(ctx: Context) = controller.requestStart(ctx)
    fun stopRecording(ctx: Context) = controller.stop(ctx)
    fun pause(ctx: Context) = controller.pause(ctx)
    fun resume(ctx: Context) = controller.resume(ctx)
    fun screenshot(ctx: Context) = controller.requestScreenshot(ctx)
    fun dismissMessages() = controller.clearMessages()
}
