package com.qorvexa.recorder.recording.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.qorvexa.recorder.core.common.Log
import com.qorvexa.recorder.core.common.RecorderError
import com.qorvexa.recorder.core.model.EngineType
import com.qorvexa.recorder.core.model.Size
import com.qorvexa.recorder.recording.gl.EglCore
import com.qorvexa.recorder.recording.gl.QuadRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class EngineParams(
    val projection: MediaProjection,
    val encoderSurface: Surface,
    val size: Size,
    val densityDpi: Int,
    val fps: Int,
    val watermark: Boolean,
    val handler: Handler,
)

/**
 * A capture engine connects MediaProjection to the encoder's input surface.
 * MediaProjection is always the capture source and MediaCodec always the encoder;
 * engines differ only in what happens in between.
 */
interface CaptureEngine {
    val type: EngineType
    /** Whether the encoder should run in callback (async) mode. */
    val asyncEncoder: Boolean
    fun start(p: EngineParams)
    fun pause()
    fun resume()
    suspend fun screenshot(): Bitmap?
    fun stop()

    companion object {
        fun create(type: EngineType): CaptureEngine = when (type) {
            EngineType.OPENGL -> GlEngine()
            EngineType.ADVANCED -> DirectEngine(EngineType.ADVANCED, async = true)
            else -> DirectEngine(EngineType.BASIC, async = false)
        }
    }
}

/**
 * Basic and Advanced engines: the virtual display renders straight into the encoder surface
 * (zero copies). Advanced additionally runs the encoder in async mode with realtime priority
 * and operating-rate hints (set in VideoEncoder).
 */
class DirectEngine(override val type: EngineType, async: Boolean) : CaptureEngine {
    override val asyncEncoder = async
    private var vd: VirtualDisplay? = null
    private lateinit var params: EngineParams

    override fun start(p: EngineParams) {
        params = p
        vd = try {
            p.projection.createVirtualDisplay(
                "QORVEXA", p.size.width, p.size.height, p.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, p.encoderSurface, null, p.handler,
            )
        } catch (e: Exception) {
            throw RecorderError.ProjectionFailure("couldn't create the virtual display", e)
        } ?: throw RecorderError.ProjectionFailure("virtual display was null")
    }

    @Volatile private var paused = false

    // Detaching the surface stops frame production entirely while paused (saves power).
    override fun pause() {
        paused = true
        vd?.surface = null
    }

    override fun resume() {
        paused = false
        vd?.surface = params.encoderSurface
    }

    /** Briefly points the display at an ImageReader; the video holds the last frame for ~1–3 frames. */
    override suspend fun screenshot(): Bitmap? {
        val display = vd ?: return null
        val reader = ImageReader.newInstance(params.size.width, params.size.height, PixelFormat.RGBA_8888, 2)
        val result = CompletableDeferred<Bitmap?>()
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (!result.isCompleted) result.complete(imageToBitmap(img.planes[0].buffer, img.planes[0].rowStride, img.planes[0].pixelStride, img.width, img.height))
            } finally {
                img.close()
            }
        }, params.handler)
        display.surface = reader.surface
        val bmp = withTimeoutOrNull(1500) { result.await() }
        display.surface = if (paused) null else params.encoderSurface
        reader.close()
        return bmp
    }

    override fun stop() {
        runCatching { vd?.release() }
        vd = null
    }
}

/**
 * OpenGL ES engine: virtual display → SurfaceTexture → GPU → encoder surface.
 * Gives an exact frame-rate cap on every API level, a watermark burned into the video,
 * and screenshots from the GPU frame without interrupting the video.
 */
class GlEngine : CaptureEngine {
    override val type = EngineType.OPENGL
    override val asyncEncoder = true

    private val thread = HandlerThread("qx-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val gl = Handler(thread.looper)
    private var egl: EglCore? = null
    private var renderer: QuadRenderer? = null
    private var oesTex = 0
    private var watermarkTex = 0
    private var watermarkSize = Size(0, 0)
    private var surfaceTexture: SurfaceTexture? = null
    private var vdSurface: Surface? = null
    private var vd: VirtualDisplay? = null
    private val stMatrix = FloatArray(16)
    private lateinit var params: EngineParams
    @Volatile private var paused = false
    private var lastFrameNs = 0L
    private var frameIntervalNs = 0L
    @Volatile private var pendingShot: CompletableDeferred<Bitmap?>? = null

    override fun start(p: EngineParams) {
        params = p
        frameIntervalNs = 1_000_000_000L / p.fps.coerceAtLeast(1)
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        gl.post {
            try {
                val core = EglCore().also { egl = it }
                core.makeWindowSurfaceCurrent(p.encoderSurface)
                val r = QuadRenderer().also { renderer = it }
                oesTex = r.createOesTexture()
                if (p.watermark) createWatermark(r)
                val st = SurfaceTexture(oesTex).apply {
                    setDefaultBufferSize(p.size.width, p.size.height)
                    setOnFrameAvailableListener({ onFrame() }, gl)
                }
                surfaceTexture = st
                vdSurface = Surface(st)
            } catch (t: Throwable) {
                error = t
            } finally {
                ready.countDown()
            }
        }
        if (!ready.await(3, TimeUnit.SECONDS)) error = IllegalStateException("GL init timeout")
        error?.let {
            stop()
            throw RecorderError.EncoderFailure("OpenGL engine failed to start: ${it.message}", it)
        }
        vd = try {
            p.projection.createVirtualDisplay(
                "QORVEXA-GL", p.size.width, p.size.height, p.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, vdSurface, null, p.handler,
            )
        } catch (e: Exception) {
            stop()
            throw RecorderError.ProjectionFailure("couldn't create the virtual display", e)
        }
    }

    private fun createWatermark(r: QuadRenderer) {
        val h = (params.size.shortSide * 0.045f).toInt().coerceAtLeast(24)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 200
            textSize = h * 0.8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
            setShadowLayer(h * 0.08f, 0f, 0f, Color.argb(160, 139, 61, 255))
        }
        val text = "QORVEXA"
        val w = (paint.measureText(text) + h * 0.4f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, h * 0.2f, h * 0.78f, paint)
        watermarkTex = r.uploadBitmap(bmp)
        watermarkSize = Size(w, h)
        bmp.recycle()
    }

    private fun onFrame() {
        val st = surfaceTexture ?: return
        val core = egl ?: return
        val r = renderer ?: return
        try {
            st.updateTexImage() // always consume, or the virtual display stalls
            val ts = st.timestamp
            // Exact FPS cap: skip frames that arrive before the next slot (5% tolerance).
            val encode = !paused && (lastFrameNs == 0L || ts - lastFrameNs >= frameIntervalNs * 95 / 100)
            if (!encode && pendingShot == null) return
            if (encode) lastFrameNs = ts
            st.getTransformMatrix(stMatrix)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            r.drawOes(oesTex, stMatrix, 0, 0, params.size.width, params.size.height)
            if (watermarkTex != 0) {
                val m = params.size.shortSide / 40
                r.drawOverlay(watermarkTex, params.size.width - watermarkSize.width - m, m, watermarkSize.width, watermarkSize.height)
            }
            pendingShot?.let { shot ->
                pendingShot = null
                shot.complete(readPixels())
            }
            if (encode) {
                core.setPresentationTime(ts)
                core.swap()
            }
        } catch (e: Exception) {
            Log.e("GlEngine", "frame failed", e)
        }
    }

    private fun readPixels(): Bitmap {
        val w = params.size.width
        val h = params.size.height
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glViewport(0, 0, w, h)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        // GL origin is bottom-left; flip to top-down.
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
        bmp.recycle()
        return flipped
    }

    override fun pause() { paused = true }
    override fun resume() {
        lastFrameNs = 0
        paused = false
    }

    override suspend fun screenshot(): Bitmap? {
        val d = CompletableDeferred<Bitmap?>()
        pendingShot = d
        return withTimeoutOrNull(1500) { d.await() }.also { pendingShot = null }
    }

    override fun stop() {
        runCatching { vd?.release() }
        vd = null
        val done = CountDownLatch(1)
        gl.post {
            try {
                runCatching { surfaceTexture?.release() }
                runCatching { vdSurface?.release() }
                runCatching { renderer?.release() }
                runCatching { egl?.release() }
            } finally {
                surfaceTexture = null; vdSurface = null; renderer = null; egl = null
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }
}

/** Converts an RGBA_8888 Image plane (which may have row padding) into a Bitmap. */
fun imageToBitmap(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): Bitmap {
    val padded = rowStride / pixelStride
    val bmp = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    bmp.copyPixelsFromBuffer(buffer)
    if (padded == width) return bmp
    val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
    bmp.recycle()
    return cropped
}
