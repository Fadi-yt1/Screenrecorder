package com.qorvexa.recorder.recording.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.qorvexa.recorder.core.audio.AudioDsp
import com.qorvexa.recorder.core.audio.Biquad
import com.qorvexa.recorder.core.audio.LinearResampler
import com.qorvexa.recorder.core.audio.PcmRingBuffer
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.common.RecorderError
import com.qorvexa.recorder.core.model.AudioSource
import com.qorvexa.recorder.core.model.ChannelMode
import com.qorvexa.recorder.core.model.RecordingSettings
import com.qorvexa.recorder.core.recording.SampleClock
import com.qorvexa.recorder.recording.encoder.AudioEncoder
import kotlin.math.abs

/** Which processing effects actually got enabled (reported to the UI; never assumed). */
data class ActiveEffects(val noiseSuppressor: Boolean, val echoCanceler: Boolean, val autoGain: Boolean, val voiceEnhance: Boolean)

/** Output format the encoder will receive. */
data class AudioPlan(val source: AudioSource, val sampleRate: Int, val channels: Int) {
    companion object {
        fun from(settings: RecordingSettings, internalAllowed: Boolean, micAllowed: Boolean): AudioPlan {
            var src = settings.audioSource
            if (src.usesInternal && !internalAllowed) src = if (src.usesMic && micAllowed) AudioSource.MIC else AudioSource.NONE
            if (src.usesMic && !micAllowed) src = if (src.usesInternal && internalAllowed) AudioSource.INTERNAL else AudioSource.NONE
            val channels = when (settings.channels) {
                ChannelMode.MONO -> 1
                ChannelMode.STEREO -> 2
                // Internal audio is stereo; a phone mic is effectively mono.
                ChannelMode.AUTO -> if (src.usesInternal) 2 else 1
            }
            return AudioPlan(src, settings.sampleRate.hz, channels)
        }
    }
}

/**
 * Captures microphone and/or internal audio, converts both to the target format,
 * mixes them with independent gains and a soft limiter, and feeds the AAC encoder.
 */
class AudioCapture(
    private val context: Context,
    private val settings: RecordingSettings,
    private val plan: AudioPlan,
    private val projection: MediaProjection?,
    private val encoder: AudioEncoder,
    private val onLevel: (Float) -> Unit,
    private val onWarning: (String) -> Unit,
) {
    private val rate = plan.sampleRate
    private val outCh = plan.channels
    private val chunkFrames = rate / 50 // 20 ms

    private var mic: Source? = null
    private var internal: Source? = null
    private val effects = ArrayList<AudioEffect>()
    var activeEffects = ActiveEffects(false, false, false, false); private set

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var resumeRequested = false
    private var thread: Thread? = null
    private var micThread: Thread? = null
    private var lastPts = 0L

    private val voiceFilter = if (settings.micProcessing.voiceEnhancement) Biquad.highPass(rate, 100.0, outCh) else null

    /** One AudioRecord plus its conversion chain into the target format. */
    private inner class Source(val record: AudioRecord, val name: String) {
        val inCh = record.channelCount
        val inRate = record.sampleRate
        val resampler = LinearResampler(inRate, rate, outCh)
        val raw = ShortArray(maxOf(rate, inRate) / 50 * inCh * 2 + 64)
        val converted = ShortArray(raw.size / inCh * outCh + 64)
        val out = ShortArray(resampler.maxOutFrames(raw.size / inCh) * outCh + 64)

        /** Blocking read; returns frames in [out] (target format) or <0 on error. */
        fun read(): Int {
            val wantFrames = maxOf(1, chunkFrames * inRate / rate)
            val n = record.read(raw, 0, wantFrames * inCh)
            if (n < 0) return n
            val frames = n / inCh
            AudioDsp.convertChannels(raw, frames, inCh, converted, outCh)
            return resampler.process(converted, frames, out)
        }
    }

    @SuppressLint("MissingPermission")
    fun prepare() {
        if (plan.source.usesMic) mic = Source(createMic(), "mic")
        if (plan.source.usesInternal) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
                try {
                    internal = Source(createInternal(projection), "internal")
                } catch (e: Exception) {
                    Log.w(TAG, "internal capture unavailable", e)
                    onWarning("Internal audio capture isn't available right now; recording microphone only.")
                    if (mic == null) throw RecorderError.AudioFailure("internal audio unavailable", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMic(): AudioRecord {
        val proc = settings.micProcessing
        // VOICE_COMMUNICATION engages the platform's tuned echo-cancelling path.
        val src = if (proc.echoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val rates = listOf(rate, 48_000, 44_100, 16_000).distinct()
        var last: Exception? = null
        for (r in rates) {
            try {
                val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val rec = AudioRecord(src, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, r / 5 * 2))
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release(); continue
                }
                selectMicDevice(rec)
                attachEffects(rec.audioSessionId)
                return rec
            } catch (e: Exception) {
                last = e
            }
        }
        throw RecorderError.AudioFailure("microphone could not be opened", last)
    }

    private fun selectMicDevice(rec: AudioRecord) {
        if (settings.micDeviceId == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dev: AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == settings.micDeviceId }
        if (dev == null) onWarning("Selected microphone isn't connected; using the default microphone.")
        else rec.setPreferredDevice(dev)
    }

    private fun attachEffects(sessionId: Int) {
        val p = settings.micProcessing
        var ns = false; var aec = false; var agc = false
        if (p.noiseSuppression && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let { it.enabled = true; ns = it.enabled; effects += it }
        }
        if (p.echoCancellation && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; aec = it.enabled; effects += it }
        }
        if (p.autoGain && AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.let { it.enabled = true; agc = it.enabled; effects += it }
        }
        activeEffects = ActiveEffects(ns, aec, agc, p.voiceEnhancement)
        if (p.noiseSuppression && !ns) onWarning("Noise suppression isn't supported on this device.")
        if (p.echoCancellation && !aec) onWarning("Echo cancellation isn't supported on this device.")
        if (p.autoGain && !agc) onWarning("Automatic gain control isn't supported on this device.")
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createInternal(projection: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 4, rate / 5 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("playback capture AudioRecord not initialised")
        }
        return rec
    }

    fun start() {
        if (mic == null && internal == null) return
        running = true
        mic?.record?.startRecording()
        internal?.record?.startRecording()
        val both = mic != null && internal != null
        val ring = if (both) PcmRingBuffer(rate * outCh) else null // 1 s of headroom
        if (both) {
            micThread = Thread({ micLoop(ring!!) }, "qx-mic").apply { start() }
        }
        thread = Thread({ mainLoop(ring) }, "qx-audio").apply { start() }
    }

    /** Mic reader when mixing: pushes processed mic audio into the ring buffer. */
    private fun micLoop(ring: PcmRingBuffer) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val m = mic ?: return
        val gain = settings.micGainPercent / 100f
        while (running) {
            val frames = m.read()
            if (frames < 0) {
                handleMicError(frames); continue
            }
            voiceFilter?.process(m.out, frames)
            AudioDsp.applyGain(m.out, frames * outCh, gain)
            ring.write(m.out, frames * outCh)
        }
    }

    private var micErrorReported = false
    private fun handleMicError(code: Int) {
        if (!micErrorReported) {
            micErrorReported = true
            onWarning("Microphone disconnected or unavailable (error $code). Recording continues; the mic track will be silent.")
        }
        Thread.sleep(20)
    }

    private fun mainLoop(ring: PcmRingBuffer?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val clock = SampleClock(rate)
        val primary = internal ?: mic!!
        val primaryIsMic = primary === mic
        val micGain = settings.micGainPercent / 100f
        val internalGain = settings.internalVolumePercent / 100f
        val micBuf = ShortArray(primary.out.size)
        val mixed = ShortArray(primary.out.size)
        var levelCounter = 0
        try {
            while (running) {
                val frames = primary.read()
                val now = System.nanoTime() / 1000
                if (frames < 0) {
                    if (primaryIsMic) handleMicError(frames) else Thread.sleep(20)
                    continue
                }
                if (frames == 0) continue
                val samples = frames * outCh
                if (paused) {
                    ring?.read(micBuf, minOf(samples, micBuf.size), padSilence = false)
                    continue
                }
                if (resumeRequested) {
                    clock.reanchor(now - frames * 1_000_000L / rate)
                    resumeRequested = false
                }
                val pcm: ShortArray
                if (ring != null) {
                    ring.read(micBuf, samples, padSilence = true)
                    AudioDsp.mix(primary.out, internalGain, micBuf, 1f, samples, mixed) // mic gain applied in micLoop
                    pcm = mixed
                } else {
                    if (primaryIsMic) {
                        voiceFilter?.process(primary.out, frames)
                        AudioDsp.applyGain(primary.out, samples, micGain)
                    } else {
                        AudioDsp.applyGain(primary.out, samples, internalGain)
                    }
                    pcm = primary.out
                }
                // Timestamp = when the first sample of this chunk was captured.
                var pts = clock.ptsFor(frames, now - frames * 1_000_000L / rate)
                val drift = pts - (now - frames * 1_000_000L / rate)
                if (abs(drift) > 60_000) { // keep audio locked to the video clock over long recordings
                    clock.reanchor(now - frames * 1_000_000L / rate)
                    pts = clock.ptsFor(frames, now)
                }
                lastPts = pts
                encoder.encode(pcm, samples, pts)
                if (++levelCounter % 5 == 0) {
                    val levelSrc = if (ring != null) micBuf else pcm
                    onLevel(AudioDsp.rmsDb(levelSrc, samples))
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "audio loop failed", e)
                onWarning("Audio stopped unexpectedly: ${e.message}. Video recording continues.")
            }
        }
    }

    fun pause() { paused = true }

    fun resume() {
        resumeRequested = true
        paused = false
    }

    /** Stops capture and sends end-of-stream to the encoder (call before stopping the muxer). */
    fun stop() {
        running = false
        runCatching { mic?.record?.stop() }
        runCatching { internal?.record?.stop() }
        micThread?.join(1000)
        thread?.join(1500)
        encoder.finish(lastPts + 1)
        release()
    }

    fun release() {
        running = false
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { mic?.record?.release() }
        runCatching { internal?.record?.release() }
        mic = null
        internal = null
        encoder.release()
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
