package com.qorvexa.recorder.recording.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.common.RecorderError
import java.nio.ByteOrder

/**
 * AAC-LC encoder fed with PCM16 from the audio thread. All calls must come from that one thread.
 */
class AudioEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int,
    private val sink: MuxerSink,
) {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var eosSent = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            throw RecorderError.AudioFailure("AAC encoder unavailable for ${sampleRate}Hz/${channels}ch", e)
        }
    }

    /** Queues [samples] interleaved PCM16 samples stamped [ptsUs]. Blocks briefly if the encoder is full. */
    fun encode(pcm: ShortArray, samples: Int, ptsUs: Long) {
        var offset = 0
        var pts = ptsUs
        while (offset < samples) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(false)
                continue
            }
            val buf = codec.getInputBuffer(idx) ?: continue
            buf.clear()
            buf.order(ByteOrder.nativeOrder())
            val n = minOf(samples - offset, buf.capacity() / 2)
            buf.asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
            offset += n
            pts += n.toLong() / channels * 1_000_000L / sampleRate
            drain(false)
        }
    }

    private fun drain(untilEos: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.addAudioTrack(codec.outputFormat)
                idx >= 0 -> {
                    codec.getOutputBuffer(idx)?.let { sink.writeAudio(it, info) }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
            }
        }
    }

    fun finish(lastPtsUs: Long) {
        if (eosSent) return
        eosSent = true
        try {
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) sink.addAudioTrack(codec.outputFormat)
                if (o >= 0) {
                    codec.getOutputBuffer(o)?.let { sink.writeAudio(it, info) }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (e: Exception) {
            Log.w("AudioEncoder", "finish failed", e)
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
