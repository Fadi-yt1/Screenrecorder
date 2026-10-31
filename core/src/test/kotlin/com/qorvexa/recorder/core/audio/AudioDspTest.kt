package com.qorvexa.recorder.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioDspTest {
    @Test fun monoToStereoDuplicates() {
        val src = shortArrayOf(1, 2, 3)
        val dst = ShortArray(6)
        AudioDsp.convertChannels(src, 3, 1, dst, 2)
        assertEquals(listOf<Short>(1, 1, 2, 2, 3, 3), dst.toList())
    }

    @Test fun stereoToMonoAverages() {
        val dst = ShortArray(2)
        AudioDsp.convertChannels(shortArrayOf(100, 200, -100, -300), 2, 2, dst, 1)
        assertEquals(listOf<Short>(150, -200), dst.toList())
    }

    @Test fun mixNeverWrapsAround() {
        val a = ShortArray(4) { Short.MAX_VALUE }
        val b = ShortArray(4) { Short.MAX_VALUE }
        val out = ShortArray(4)
        AudioDsp.mix(a, 1f, b, 1f, 4, out)
        out.forEach { assertTrue("clipped to positive", it > 30000) }
        val n = ShortArray(4)
        AudioDsp.mix(ShortArray(4) { Short.MIN_VALUE }, 1f, ShortArray(4) { Short.MIN_VALUE }, 1f, 4, n)
        n.forEach { assertTrue(it < -30000) }
    }

    @Test fun quietSignalsMixLinearly() {
        val out = ShortArray(1)
        AudioDsp.mix(shortArrayOf(1000), 1f, shortArrayOf(2000), 0.5f, 1, out)
        assertEquals(2000.toShort(), out[0])
    }

    @Test fun gainClamps() {
        val buf = shortArrayOf(20000, -20000)
        AudioDsp.applyGain(buf, 2, 3f)
        assertEquals(Short.MAX_VALUE, buf[0])
        assertEquals(Short.MIN_VALUE, buf[1])
    }

    @Test fun rmsOfSilenceIsFloor() = assertEquals(-90f, AudioDsp.rmsDb(ShortArray(100), 100))

    @Test fun rmsOfFullScaleSineIsAboutMinus3dB() {
        val n = 4800
        val buf = ShortArray(n) { (sin(2 * PI * 1000 * it / 48000) * Short.MAX_VALUE).toInt().toShort() }
        val db = AudioDsp.rmsDb(buf, n)
        assertTrue("got $db", db in -3.3f..-2.7f)
    }

    @Test fun resamplerProducesExpectedLength() {
        val r = LinearResampler(44100, 48000, 1)
        val src = ShortArray(4410) { 1000 }
        val dst = ShortArray(r.maxOutFrames(4410))
        var total = 0
        repeat(10) { total += r.process(src, 4410, dst) }
        // 10 × 0.1 s at 48 kHz ≈ 48000 frames
        assertTrue("got $total", total in 47990..48010)
        assertEquals(1000.toShort(), dst[10])
    }

    @Test fun resamplerPassthrough() {
        val r = LinearResampler(48000, 48000, 2)
        val dst = ShortArray(4)
        assertEquals(2, r.process(shortArrayOf(1, 2, 3, 4), 2, dst))
        assertEquals(listOf<Short>(1, 2, 3, 4), dst.toList())
    }

    @Test fun ringBufferDropsOldestWhenFull() {
        val rb = PcmRingBuffer(4)
        assertEquals(2, rb.write(shortArrayOf(1, 2, 3, 4, 5, 6), 6))
        val out = ShortArray(6)
        assertEquals(4, rb.read(out, 6, padSilence = true))
        assertEquals(listOf<Short>(3, 4, 5, 6, 0, 0), out.toList())
    }
}
