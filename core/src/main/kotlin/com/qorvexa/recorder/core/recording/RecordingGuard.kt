package com.qorvexa.recorder.core.recording

import com.qorvexa.recorder.core.model.RecordingSettings

enum class StopReason(val message: String) {
    USER("Recording stopped"),
    MAX_DURATION("Maximum recording duration reached"),
    MAX_FILE_SIZE("Maximum file size reached"),
    LOW_STORAGE("Stopped to protect storage: free space is running out"),
    LOW_BATTERY("Stopped to protect battery"),
    PROJECTION_ENDED("Screen sharing was stopped by the system or user"),
    ERROR("Recording stopped because of an error"),
}

data class GuardSnapshot(
    val elapsedMs: Long,
    val bytesWritten: Long,
    val freeBytes: Long,
    val batteryPercent: Int,
    val charging: Boolean,
)

/** Decides when a running recording must stop automatically. Pure logic, polled by the service. */
class RecordingGuard(private val settings: RecordingSettings) {

    fun check(s: GuardSnapshot): StopReason? {
        if (settings.maxDurationMin > 0 && s.elapsedMs >= settings.maxDurationMin * 60_000L) return StopReason.MAX_DURATION
        if (settings.maxFileSizeMb > 0 && s.bytesWritten >= settings.maxFileSizeMb * MB) return StopReason.MAX_FILE_SIZE
        if (settings.lowStorageProtection && s.freeBytes in 0 until settings.minFreeStorageMb.coerceAtLeast(100) * MB) return StopReason.LOW_STORAGE
        if (settings.batteryProtection && !s.charging && s.batteryPercent in 0..settings.batteryStopPercent) return StopReason.LOW_BATTERY
        return null
    }

    /** True when free space is getting low but not yet at the stop threshold (warn the user). */
    fun shouldWarnStorage(freeBytes: Long): Boolean =
        settings.lowStorageProtection && freeBytes in 0 until settings.minFreeStorageMb.coerceAtLeast(100) * MB * 2

    /** Refuse to even start when there isn't room for ~1 minute of video above the reserve. */
    fun canStart(freeBytes: Long, bytesPerSecond: Long): Boolean {
        if (!settings.lowStorageProtection) return freeBytes > 50 * MB
        return freeBytes > settings.minFreeStorageMb.coerceAtLeast(100) * MB + bytesPerSecond * 60
    }

    companion object {
        const val MB = 1024L * 1024L
    }
}
