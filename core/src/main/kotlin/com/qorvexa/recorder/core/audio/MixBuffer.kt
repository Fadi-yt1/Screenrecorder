package com.qorvexa.recorder.core.audio

/**
 * Single-producer/single-consumer ring buffer of PCM16 samples.
 * Used to line up microphone and internal audio, which arrive in different
 * chunk sizes and at slightly different times. Thread-safe via a simple lock;
 * contention is negligible at audio rates.
 */
class PcmRingBuffer(capacitySamples: Int) {
    private val data = ShortArray(capacitySamples)
    private var read = 0
    private var write = 0
    private var size = 0
    private val lock = Any()

    val capacity: Int get() = data.size
    fun available(): Int = synchronized(lock) { size }

    /** Writes samples; if full, the oldest samples are dropped (keeps latency bounded). Returns dropped count. */
    fun write(src: ShortArray, count: Int): Int = synchronized(lock) {
        var dropped = 0
        for (i in 0 until count) {
            if (size == data.size) {
                read = (read + 1) % data.size
                size--
                dropped++
            }
            data[write] = src[i]
            write = (write + 1) % data.size
            size++
        }
        dropped
    }

    /** Reads up to [count] samples; missing samples are filled with silence when [padSilence]. Returns samples read from buffer. */
    fun read(dst: ShortArray, count: Int, padSilence: Boolean): Int = synchronized(lock) {
        val n = minOf(count, size)
        for (i in 0 until n) {
            dst[i] = data[read]
            read = (read + 1) % data.size
        }
        size -= n
        if (padSilence) for (i in n until count) dst[i] = 0
        n
    }

    fun clear() = synchronized(lock) { read = 0; write = 0; size = 0 }
}
