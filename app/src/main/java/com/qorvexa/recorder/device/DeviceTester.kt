package com.qorvexa.recorder.device

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.opengl.GLES20
import com.qorvexa.recorder.core.audio.AudioDsp
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.video.BitrateCalculator
import com.qorvexa.recorder.permissions.Permissions
import com.qorvexa.recorder.recording.gl.EglCore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

data class EncoderTestResult(val codec: VideoCodec, val size: Size, val targetFps: Int, val achievedFps: Double, val encoderName: String, val error: String? = null) {
    val passed: Boolean get() = error == null && achievedFps >= targetFps * 0.9
}

data class DeviceTestReport(
    val encoderResults: List<EncoderTestResult>,
    val micPeakDb: Float?,
    val micError: String?,
    val storageWriteMbPerSec: Double?,
    val recommendation: String,
)

/**
 * Short, safe capability checks. A few seconds of encoding shows peak throughput;
 * it does NOT prove the device can sustain that for a long recording (heat, throttling).
 */
@Singleton
class DeviceTester @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun run(tests: List<Triple<VideoCodec, Size, Int>>, onProgress: (String) -> Unit): DeviceTestReport = withContext(Dispatchers.Default) {
        val enc = tests.map { (codec, size, fps) ->
            onProgress("Encoding ${size.shortSide}p ${fps} FPS with ${codec.label}…")
            encoderThroughput(codec, size, fps)
        }
        onProgress("Testing microphone…")
        val (mic, micErr) = micLevel()
        onProgress("Testing storage speed…")
        val storage = storageSpeed()
        val best = enc.filter { it.passed }.maxByOrNull { it.size.pixels * it.targetFps }
        val rec = if (best != null) {
            "Stable start point: ${best.size.shortSide}p at ${best.targetFps} FPS with ${best.codec.label}. " +
                "This was a short test; long recordings can get slower as the phone warms up."
        } else "Your device struggled in this short test. Use Battery Saver mode (720p, 30 FPS)."
        DeviceTestReport(enc, mic, micErr, storage, rec)
    }

    /**
     * Renders frames with the GPU into a real encoder input surface for ~2 s and counts encoded frames.
     */
    private fun encoderThroughput(codec: VideoCodec, size: Size, fps: Int): EncoderTestResult {
        var mc: MediaCodec? = null
        var egl: EglCore? = null
        try {
            val format = MediaFormat.createVideoFormat(codec.mime, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BitrateCalculator.recommend(size.width, size.height, fps, codec))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(codec.mime).also { mc = it }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            val core = EglCore().also { egl = it }
            core.makeWindowSurfaceCurrent(surface)
            val info = MediaCodec.BufferInfo()
            val frameNs = 1_000_000_000L / fps
            val startNs = System.nanoTime()
            val durationNs = 2_000_000_000L
            var produced = 0
            var submitted = 0
            var t = startNs
            while (System.nanoTime() - startNs < durationNs) {
                // Changing colour each frame so the encoder can't skip identical frames.
                val k = (submitted % 60) / 60f
                GLES20.glClearColor(k, 1f - k, (k * 3) % 1f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                core.setPresentationTime(t)
                core.swap()
                submitted++
                t += frameNs
                produced += drain(c, info)
                val sleep = (startNs + submitted * frameNs - System.nanoTime()) / 1_000_000
                if (sleep > 0) Thread.sleep(sleep)
            }
            val elapsed = (System.nanoTime() - startNs) / 1e9
            c.signalEndOfInputStream()
            val end = System.nanoTime() + 500_000_000L
            while (System.nanoTime() < end) produced += drain(c, info, 10_000)
            return EncoderTestResult(codec, size, fps, produced / elapsed, c.name)
        } catch (e: Exception) {
            Log.w("DeviceTester", "encoder test failed", e)
            return EncoderTestResult(codec, size, fps, 0.0, "", e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { egl?.release() }
            runCatching { mc?.stop() }
            runCatching { mc?.release() }
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo, timeoutUs: Long = 0): Int {
        var n = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, timeoutUs)
            if (idx < 0) return n
            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) n++
            c.releaseOutputBuffer(idx, false)
        }
    }

    @SuppressLint("MissingPermission")
    fun micLevel(durationMs: Long = 1200): Pair<Float?, String?> {
        if (!Permissions.micGranted(context)) return null to "Microphone permission not granted"
        val rate = 44_100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null to "Microphone not available"
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        } catch (e: Exception) {
            return null to (e.message ?: "Microphone error")
        }
        return try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return null to "Microphone is busy or unavailable"
            rec.startRecording()
            val buf = ShortArray(min)
            var peak = -90f
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) peak = maxOf(peak, AudioDsp.rmsDb(buf, n))
            }
            peak to null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Writes 32 MB to app cache (same storage as recordings) and syncs to measure sustained write speed. */
    private fun storageSpeed(): Double? {
        val f = File(context.externalCacheDir ?: context.cacheDir, "qx_speed.tmp")
        return try {
            val chunk = ByteArray(1024 * 1024) { it.toByte() }
            val start = System.nanoTime()
            RandomAccessFile(f, "rw").use { raf ->
                repeat(32) { raf.write(chunk) }
                raf.fd.sync()
            }
            val sec = (System.nanoTime() - start) / 1e9
            32 / sec
        } catch (e: Exception) {
            null
        } finally {
            f.delete()
        }
    }
}
