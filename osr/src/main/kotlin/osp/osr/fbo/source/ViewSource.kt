package osp.osr.fbo.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import osp.osr.fbo.gl.EglHelper
import osp.osr.log.OsrLog
import kotlin.coroutines.resume

/**
 * 📌 方式 4（View）—— 任意 Android View 截图录
 *
 * **小白一句话**：不碰 OpenGL 的 View（布局、地图容器等），我们主线程把它 draw 到 Bitmap，
 * GL 线程把 Bitmap 上传成纹理（首帧 glTexImage2D，后续 glTexSubImage2D 复用），再 captureFrame 进编码器。
 *
 * **数据流**：
 * - start() 里 launch 协程 → EglHelper 离屏 PBuffer + 一张纹理 + Bitmap/Canvas
 * - 每帧：drawViewToBitmap()（mainHandler 到主线程 view.draw(canvas)）→ uploadBitmapToTexture() → captureCallback.captureFrame()
 * - FrameCaptureRenderer.captureFrame() 会 blit 当前绑定的 FBO；若 View 内容需要先画到当前 FBO 再 blit，需在 upload 后把纹理画到当前缓冲（见实现）。
 */
class ViewSource(
    private val viewProvider: () -> View,
    private val captureCallback: FrameCaptureCallback,
    private val width: Int,
    private val height: Int,
    private val fps: Int
) : FrameSource {

    private val eglHelper = EglHelper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loopJob: Job? = null
    private var textureId = 0
    private var firstFrame = true

    @Volatile
    private var recording = false

    private lateinit var bitmap: Bitmap
    private lateinit var canvas: Canvas

    override fun start() {
        recording = true
        loopJob = scope.launch {
            eglHelper.init()
            eglHelper.createPbufferSurface(width, height)
            eglHelper.makeCurrent()

            val texIds = IntArray(1)
            GLES30.glGenTextures(1, texIds, 0)
            textureId = texIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap)
            firstFrame = true

            val frameIntervalMs = 1000L / fps
            OsrLog.i("ViewSource: render loop started ${width}x${height} @${fps}fps")

            while (isActive && recording) {
                val start = System.currentTimeMillis()
                drawViewToBitmap()
                uploadBitmapToTexture()
                captureCallback.captureFrame()
                val elapsed = System.currentTimeMillis() - start
                val sleepMs = (frameIntervalMs - elapsed).coerceAtLeast(0)
                if (sleepMs > 0) delay(sleepMs)
            }
            OsrLog.i("ViewSource: render loop ended")
        }
    }

    private suspend fun drawViewToBitmap() {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val view = viewProvider()
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                view.draw(canvas)
                cont.resume(Unit)
            }
        }
    }

    private fun uploadBitmapToTexture() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        if (firstFrame) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            firstFrame = false
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    override fun stop() {
        recording = false
        loopJob?.cancel()
        OsrLog.i("ViewSource: stopped")
    }

    override fun release() {
        recording = false
        loopJob?.cancel()
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (::bitmap.isInitialized && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        runCatching { eglHelper.release() }
        scope.cancel()
    }
}
