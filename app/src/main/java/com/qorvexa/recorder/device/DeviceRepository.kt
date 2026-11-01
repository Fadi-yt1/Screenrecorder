package com.qorvexa.recorder.device

import com.qorvexa.recorder.core.device.DeviceProfile
import com.qorvexa.recorder.core.device.ProfileCalculator
import com.qorvexa.recorder.core.model.DeviceCapabilities
import com.qorvexa.recorder.core.model.OptimizationMode
import com.qorvexa.recorder.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    private val detector: DeviceCapabilityDetector,
    private val settings: SettingsRepository,
) {
    private val _caps = MutableStateFlow<DeviceCapabilities?>(null)
    val capabilities: StateFlow<DeviceCapabilities?> = _caps.asStateFlow()
    private val mutex = Mutex()

    val profile: DeviceProfile? get() = _caps.value?.let { ProfileCalculator.profile(it) }

    suspend fun refresh(): DeviceCapabilities = mutex.withLock {
        val caps = withContext(Dispatchers.IO) { detector.detect() }
        _caps.value = caps
        val state = settings.appState.first()
        val current = settings.current()
        // First launch, or user opted out of remembering: start from the hardware recommendation.
        if (!state.onboardingDone || !current.rememberLastConfig) {
            settings.update { ProfileCalculator.recommend(OptimizationMode.AUTOMATIC, caps, it) }
            settings.setOnboardingDone()
        }
        caps
    }

    /** Returns cached capabilities, detecting them if this is the first call. */
    suspend fun get(): DeviceCapabilities = _caps.value ?: refresh()

    suspend fun awaitDetected(): DeviceCapabilities = capabilities.filterNotNull().first()

    /** Live values (storage, battery, thermal) without re-running the full detection. */
    fun quickRefresh() {
        val c = _caps.value ?: return
        val (free, total) = detector.storageInfo()
        val (battery, charging) = detector.batteryInfo()
        _caps.value = c.copy(storageFreeBytes = free, storageTotalBytes = total, batteryPercent = battery, charging = charging, thermalStatus = detector.thermalStatus())
    }
}
