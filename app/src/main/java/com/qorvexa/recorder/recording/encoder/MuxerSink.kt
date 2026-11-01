package com.qorvexa.recorder.recording.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.common.RecorderError
import com.qorvexa.recorder.core.recording.PresentationClock
import com.qorvexa.recorder.storage.OutputTarget
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe wrapper around MediaMuxer.
 *
 * - Starts only after every expected track has reported its format.
 * - Buffers early samples (e.g. audio that arrives before the first video frame).
 * - Rebases timestamps through [PresentationClock] so pauses leave no gap.
 * - Tracks bytes written for file-size limits.
 */
class MuxerSink(target: OutputTarget, expectAudio: Boolean, private val clock: PresentationClock) {

    @Volatile private var expectAudio = expectAudio

    private val muxer: MediaMuxer = try {
        val fd = target.fd
        if (fd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(requireNotNull(target.path) { "No output path" }, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    } catch (e: Exception) {
        throw RecorderError.StorageFailure("Couldn't create MP4 file", e)
    }

    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false
    private val pending = ArrayList<Pending>()
    private var pendingBytes = 0
    private val written = AtomicLong(0)
    @Volatile var videoSamples = 0L; private set

    val bytesWritten: Long get() = written.get()

    private class Pending(val track: Int, val data: ByteArray, val ptsUs: Long, val flags: Int)

    fun setOrientationHint(degrees: Int) = synchronized(lock) { if (!started) muxer.setOrientationHint(degrees) }

    /** Called when audio capture fails to start, so the file is written video-only. */
    fun disableAudio() = synchronized(lock) {
        expectAudio = false
        maybeStart()
    }

    fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
        if (videoTrack >= 0 || stopped) return
        videoTrack = muxer.addTrack(format)
        maybeStart()
    }

    fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
        if (audioTrack >= 0 || stopped) return
        audioTrack = muxer.addTrack(format)
        maybeStart()
    }

    private fun maybeStart() {
        if (started) return
        if (videoTrack < 0 || (expectAudio && audioTrack < 0)) return
        muxer.start()
        started = true
        // Drain buffered samples in arrival order.
        val tmpInfo = MediaCodec.BufferInfo()
        for (p in pending) {
            val idx = if (p.track == TRACK_VIDEO) videoTrack else audioTrack
            tmpInfo.set(0, p.data.size, p.ptsUs, p.flags)
            muxer.writeSampleData(idx, ByteBuffer.wrap(p.data), tmpInfo)
            written.addAndGet(p.data.size.toLong())
        }
        pending.clear()
        pendingBytes = 0
        Log.i(TAG, "Muxer started (video=$videoTrack audio=$audioTrack)")
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(TRACK_VIDEO, buffer, info)
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(TRACK_AUDIO, buffer, info)

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        val pts = clock.map(track, info.presentationTimeUs)
        if (pts < 0) return
        synchronized(lock) {
            if (stopped) return
            // The first video sample must be a key frame or players show garbage.
            if (track == TRACK_VIDEO && videoSamples == 0L && (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            if (!started) {
                if (pendingBytes + info.size > MAX_PENDING_BYTES) return // drop rather than grow unbounded
                val copy = ByteArray(info.size)
                buffer.get(copy)
                pending += Pending(track, copy, pts, info.flags)
                pendingBytes += info.size
            } else {
                val out = MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, info.flags) }
                muxer.writeSampleData(if (track == TRACK_VIDEO) videoTrack else audioTrack, buffer, out)
                written.addAndGet(info.size.toLong())
            }
            if (track == TRACK_VIDEO) videoSamples++
        }
    }

    /**
     * Finalises the MP4 (writes the index). Returns true when the file is valid.
     * Safe to call more than once.
     */
    fun stop(): Boolean = synchronized(lock) {
        if (stopped) return started && videoSamples > 0
        stopped = true
        var ok = false
        try {
            if (started && videoSamples > 0) {
                muxer.stop()
                ok = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "muxer.stop failed", e)
        } finally {
            runCatching { muxer.release() }
        }
        ok
    }

    companion object {
        const val TRACK_VIDEO = 0
        const val TRACK_AUDIO = 1
        private const val MAX_PENDING_BYTES = 8 * 1024 * 1024
        private const val TAG = "Muxer"
    }
}
