package com.qorvexa.recorder.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Allocation-free PCM16 helpers used on the audio thread.
 * All buffers are interleaved 16-bit signed samples.
 */
object AudioDsp {

    /** Converts interleaved PCM between mono and stereo. Returns frames written. */
    fun convertChannels(src: ShortArray, srcFrames: Int, srcChannels: Int, dst: ShortArray, dstChannels: Int): Int {
        if (srcChannels == dstChannels) {
            System.arraycopy(src, 0, dst, 0, srcFrames * srcChannels)
            return srcFrames
        }
        if (srcChannels == 1 && dstChannels == 2) {
            for (i in 0 until srcFrames) {
                val s = src[i]
                dst[2 * i] = s
                dst[2 * i + 1] = s
            }
        } else if (srcChannels == 2 && dstChannels == 1) {
            for (i in 0 until srcFrames) {
                dst[i] = ((src[2 * i].toInt() + src[2 * i + 1].toInt()) / 2).toShort()
            }
        } else {
            throw IllegalArgumentException("Unsupported channel conversion $srcChannels -> $dstChannels")
        }
        return srcFrames
    }

    /** Applies gain (1.0 = unchanged) with hard clamp, in place. */
    fun applyGain(buf: ShortArray, samples: Int, gain: Float) {
        if (gain == 1f) return
        for (i in 0 until samples) {
            val v = (buf[i] * gain).toInt()
            buf[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Mixes [a] and [b] (same layout) into [out] with independent gains.
     * Uses a soft limiter above ~-3 dBFS so loud game audio plus voice doesn't hard-clip.
     */
    fun mix(a: ShortArray, gainA: Float, b: ShortArray, gainB: Float, samples: Int, out: ShortArray) {
        for (i in 0 until samples) {
            val sum = a[i] * gainA + b[i] * gainB
            out[i] = softClip(sum)
        }
    }

    private const val KNEE = 0.7f * Short.MAX_VALUE
    private const val HEADROOM = Short.MAX_VALUE - KNEE

    fun softClip(x: Float): Short {
        val ax = abs(x)
        if (ax <= KNEE) return x.toInt().toShort()
        val over = (ax - KNEE) / HEADROOM
        val y = KNEE + HEADROOM * tanh(over.toDouble()).toFloat()
        val signed = if (x < 0) -y else y
        return signed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** RMS level of the buffer in dBFS (-90 for silence). */
    fun rmsDb(buf: ShortArray, samples: Int): Float {
        if (samples <= 0) return -90f
        var sum = 0.0
        for (i in 0 until samples) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / samples) / Short.MAX_VALUE
        return if (rms <= 0.0000316) -90f else (20 * log10(rms)).toFloat()
    }

    fun peak(buf: ShortArray, samples: Int): Float {
        var p = 0
        for (i in 0 until samples) p = max(p, abs(buf[i].toInt()))
        return p / Short.MAX_VALUE.toFloat()
    }
}

/**
 * Streaming linear resampler for interleaved PCM16. Keeps fractional position
 * across calls so there are no clicks at buffer boundaries.
 */
class LinearResampler(private val inRate: Int, private val outRate: Int, private val channels: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private val last = ShortArray(channels)
    private var hasLast = false

    val isPassthrough: Boolean get() = inRate == outRate

    /** Max output frames for [inFrames] input frames. */
    fun maxOutFrames(inFrames: Int): Int = ((inFrames + 1) / step).toInt() + 2

    fun process(src: ShortArray, inFrames: Int, dst: ShortArray): Int {
        if (isPassthrough) {
            System.arraycopy(src, 0, dst, 0, inFrames * channels)
            return inFrames
        }
        if (inFrames == 0) return 0
        var outFrames = 0
        // Virtual input = [last frame] + src; index -1 is `last`.
        while (true) {
            val idx = pos.toInt() // position relative to virtual start where 0 == last
            val frac = pos - idx
            val i0 = idx - 1
            val i1 = idx
            if (i1 >= inFrames) break
            for (c in 0 until channels) {
                val s0 = if (i0 < 0) (if (hasLast) last[c] else src[c]) else src[i0 * channels + c]
                val s1 = src[i1 * channels + c]
                dst[outFrames * channels + c] = (s0 + (s1 - s0) * frac).toInt().toShort()
            }
            outFrames++
            pos += step
        }
        pos -= inFrames
        for (c in 0 until channels) last[c] = src[(inFrames - 1) * channels + c]
        hasLast = true
        return outFrames
    }
}

/**
 * RBJ biquad filter on interleaved PCM16 (independent state per channel).
 * "Voice enhancement" uses a 100 Hz high-pass to remove rumble and handling noise.
 */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double, private val channels: Int,
) {
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)

    fun process(buf: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val i = f * channels + c
                val x = buf[i].toDouble()
                val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                x2[c] = x1[c]; x1[c] = x
                y2[c] = y1[c]; y1[c] = y
                buf[i] = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }

    companion object {
        fun highPass(sampleRate: Int, cutoffHz: Double, channels: Int, q: Double = 0.707): Biquad {
            val w0 = 2 * Math.PI * cutoffHz / sampleRate
            val alpha = Math.sin(w0) / (2 * q)
            val cos = Math.cos(w0)
            val a0 = 1 + alpha
            return Biquad(
                b0 = (1 + cos) / 2 / a0, b1 = -(1 + cos) / a0, b2 = (1 + cos) / 2 / a0,
                a1 = -2 * cos / a0, a2 = (1 - alpha) / a0, channels = channels,
            )
        }
    }
}
