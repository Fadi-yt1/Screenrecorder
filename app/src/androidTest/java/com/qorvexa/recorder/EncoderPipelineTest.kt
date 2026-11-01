package com.qorvexa.recorder

import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qorvexa.recorder.core.model.BitrateMode
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.core.model.VideoCodec
import com.qorvexa.recorder.core.recording.PresentationClock
import com.qorvexa.recorder.core.video.ResolvedVideoConfig
import com.qorvexa.recorder.recording.encoder.AudioEncoder
import com.qorvexa.recorder.recording.encoder.MuxerSink
import com.qorvexa.recorder.recording.encoder.VideoEncoder
import com.qorvexa.recorder.recording.gl.EglCore
import com.qorvexa.recorder.storage.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end test of the encode path without MediaProjection (which needs user consent):
 * GPU frames → VideoEncoder surface, sine wave → AudioEncoder, both → MuxerSink → MP4,
 * including a pause in the middle. Verifies the file with MediaExtractor.
 */
@RunWith(AndroidJUnit4::class)
class EncoderPipelineTest {

    private fun record(async: Boolean, pauseMidway: Boolean): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "pipeline_${async}_$pauseMidway.mp4").apply { delete() }
        val clock = PresentationClock()
        val sink = MuxerSink(OutputTarget(null, null, file.absolutePath, file.name), expectAudio = true, clock = clock)
        val cfg = ResolvedVideoConfig(Size(640, 360), 30, 2_000_000, VideoCodec.H264, null, false, BitrateMode.VBR, 1, emptyList())
        val video = VideoEncoder(cfg, sink, async, lowLatency = false, onError = { throw AssertionError(it) })
        video.prepare()
        val audio = AudioEncoder(48_000, 2, 128_000, sink)
        val egl = EglCore()
        egl.makeWindowSurfaceCurrent(video.inputSurface)
        video.start()
        val startUs = System.nanoTime() / 1000
        clock.start(startUs)
        val pcm = ShortArray(960 * 2)
        var audioFrames = 0L
        val frames = 60
        for (i in 0 until frames) {
            if (pauseMidway && i == 20) {
                clock.pause(System.nanoTime() / 1000)
                Thread.sleep(500)
                clock.resume(System.nanoTime() / 1000)
                video.requestKeyFrame()
            }
            val k = i / frames.toFloat()
            GLES20.glClearColor(k, 0.2f, 1f - k, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            egl.setPresentationTime(System.nanoTime())
            egl.swap()
            // ~33 ms of audio per video frame (1.6 × 960 frames)
            repeat(2) {
                for (j in 0 until 960) {
                    val v = (sin(2 * PI * 440 * (audioFrames + j) / 48_000) * 8000).toInt().toShort()
                    pcm[2 * j] = v; pcm[2 * j + 1] = v
                }
                audio.encode(pcm, pcm.size, System.nanoTime() / 1000)
                audioFrames += 960
            }
            Thread.sleep(33)
        }
        video.stop()
        audio.finish(System.nanoTime() / 1000)
        audio.release()
        egl.release()
        assertTrue("muxer should produce a valid file", sink.stop())
        return file
    }

    private fun inspect(file: File): Triple<Int, Long, Set<String>> {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var duration = 0L
        val mimes = HashSet<String>()
        for (t in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(t)
            mimes += f.getString(MediaFormat.KEY_MIME) ?: ""
            if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION))
        }
        val count = ex.trackCount
        ex.release()
        return Triple(count, duration, mimes)
    }

    @Test fun syncEncoderProducesPlayableMp4() {
        val (tracks, duration, mimes) = inspect(record(async = false, pauseMidway = false))
        assertEquals(2, tracks)
        assertTrue("video/avc" in mimes && "audio/mp4a-latm" in mimes)
        assertTrue("duration $duration", duration in 1_200_000..4_000_000)
    }

    @Test fun asyncEncoderProducesPlayableMp4() {
        val (tracks, duration, _) = inspect(record(async = true, pauseMidway = false))
        assertEquals(2, tracks)
        assertTrue("duration $duration", duration > 1_200_000)
    }

    @Test fun pauseLeavesNoGap() {
        val (_, withPause, _) = inspect(record(async = false, pauseMidway = true))
        // The 500 ms pause must be removed from the timeline.
        assertTrue("duration $withPause should not include pause", withPause < 3_000_000)
    }
}
