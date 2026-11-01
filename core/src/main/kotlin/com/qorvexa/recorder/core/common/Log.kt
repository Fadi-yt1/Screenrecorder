package com.qorvexa.recorder.core.common

/** Structured logging facade. The app installs an android.util.Log sink; tests use stdout. */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, msg: String, t: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { level, tag, msg, t ->
        println("$level/$tag: $msg" + (t?.let { " ($it)" } ?: ""))
    }

    fun d(tag: String, msg: String) = sink.log(Level.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = sink.log(Level.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = sink.log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = sink.log(Level.ERROR, tag, msg, t)
}

/** Error model shared by the recording pipeline and the UI. */
sealed class RecorderError(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
    class PermissionDenied(what: String) : RecorderError("$what permission was denied.")
    class EncoderFailure(detail: String, cause: Throwable? = null) : RecorderError("The video encoder failed: $detail", cause)
    class AudioFailure(detail: String, cause: Throwable? = null) : RecorderError("Audio capture failed: $detail", cause)
    class StorageFailure(detail: String, cause: Throwable? = null) : RecorderError("Couldn't save the recording: $detail", cause)
    class InsufficientStorage : RecorderError("Not enough free storage to start recording.")
    class ProjectionFailure(detail: String, cause: Throwable? = null) : RecorderError("Screen capture failed: $detail", cause)
    class AlreadyRecording : RecorderError("A recording is already in progress.")
}
