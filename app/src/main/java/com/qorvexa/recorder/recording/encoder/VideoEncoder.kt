package com.qorvexa.recorder.recording.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.common.RecorderError
import com.qorvexa.recorder.core.model.BitrateMode
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.video.ResolvedVideoConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Surface-input hardware video encoder.
 *
 * [asyncMode] uses MediaCodec callbacks on a dedicated high-priority thread (API 23+);
 * otherwise a synchronous drain loop is used (lowest overhead, works on API 21).
 */
class VideoEncoder(
    private val config: ResolvedVideoConfig,
    private val sink: MuxerSink,
    private val asyncMode: Boolean,
    private val lowLatency: Boolean,
    private val onError: (Throwable) -> Unit,
) {
    private var codec: MediaCodec? = null
    lateinit var inputSurface: Surface
        private set
    var size: Size = config.size
        private set
    var encoderName: String = ""
        private set

    private val thread = HandlerThread("qx-venc", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val eos = CountDownLatch(1)
    @Volatile private var running = false
    private var drainThread: Thread? = null

    /** Configures the encoder, trying progressively more compatible options. */
    fun prepare() {
        val attempts = buildList {
            config.encoderName?.let { add(it to config.size) }
            add(null to config.size)
            // Some older encoders need 16-pixel alignment.
            val aligned = Size(config.size.width / 16 * 16, config.size.height / 16 * 16)
            if (aligned != config.size) {
                config.encoderName?.let { add(it to aligned) }
                add(null to aligned)
            }
            if (config.codec == VideoCodec.H264) {
                add("c2.android.avc.encoder" to aligned)
                add("OMX.google.h264.encoder" to aligned)
            }
        }
        var last: Throwable? = null
        for ((name, sz) in attempts) {
            val c = try {
                if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(config.codec.mime)
            } catch (e: Exception) {
                last = e; continue
            }
            try {
                if (asyncMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) c.setCallback(callback, handler)
                c.configure(format(sz), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                codec = c
                size = sz
                encoderName = c.name
                Log.i(TAG, "Configured ${c.name} ${sz} @${config.fps}fps ${config.bitrate / 1000}kbps async=$asyncMode")
                return
            } catch (e: Exception) {
                Log.w(TAG, "configure failed for ${name ?: "default"} $sz", e)
                last = e
                runCatching { c.release() }
            }
        }
        throw RecorderError.EncoderFailure("no encoder accepted ${config.codec.label} at ${config.size}", last)
    }

    private fun format(sz: Size): MediaFormat = MediaFormat.createVideoFormat(config.codec.mime, sz.width, sz.height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSec)
        setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            if (config.bitrateMode == BitrateMode.CBR) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Caps how many frames per second reach the encoder from the virtual display.
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.fps.toFloat())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && asyncMode) {
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (config.fps > 30) setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lowLatency) {
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
    }

    fun start() {
        val c = codec ?: throw IllegalStateException("prepare() not called")
        c.start()
        running = true
        if (!(asyncMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)) {
            drainThread = Thread({ syncDrain(c) }, "qx-venc-drain").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null) sink.writeVideo(buf, info)
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                fail(e); return
            }
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos.countDown()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = sink.addVideoTrack(format)
    }

    private fun syncDrain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.addVideoTrack(c.outputFormat)
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null) sink.writeVideo(buf, info)
                        c.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                    !running && eosRequested && System.nanoTime() > eosDeadline -> break
                }
            }
        } catch (e: Exception) {
            if (running) fail(e)
        } finally {
            eos.countDown()
        }
    }

    @Volatile private var eosRequested = false
    @Volatile private var eosDeadline = 0L

    private fun fail(e: Throwable) {
        if (!running) return
        running = false
        Log.e(TAG, "encoder error", e)
        eos.countDown()
        onError(RecorderError.EncoderFailure(e.message ?: e.javaClass.simpleName, e))
    }

    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Drops incoming frames while paused (no gap is left thanks to the presentation clock). */
    fun setSuspended(suspended: Boolean) {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0) }) }
        if (!suspended) requestKeyFrame()
    }

    /** Signals end of stream, waits for the final buffers, then releases everything. */
    fun stop() {
        val c = codec ?: return
        eosRequested = true
        eosDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        try {
            if (running) c.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
        }
        if (!eos.await(2500, TimeUnit.MILLISECONDS)) Log.w(TAG, "EOS timeout; finalising with what we have")
        running = false
        drainThread?.join(1000)
        runCatching { c.stop() }
        runCatching { c.release() }
        runCatching { inputSurface.release() }
        codec = null
        thread.quitSafely()
    }

    /** Release without draining (used when start fails). */
    fun release() {
        running = false
        runCatching { codec?.release() }
        if (this::inputSurface.isInitialized) runCatching { inputSurface.release() }
        codec = null
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
