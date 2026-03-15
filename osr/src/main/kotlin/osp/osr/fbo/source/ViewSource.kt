package osp.osr.fbo.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES30
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import osp.osr.fbo.gl.EglHelper
import osp.osr.fbo.gl.PboTextureUploader
import osp.osr.log.OsrLog

/**
 * 📌 方式 4（View）—— 任意 Android View 截图录
 *
 * **小白一句话**：不碰 OpenGL 的 View（布局、地图容器等），我们主线程把它 draw 到 Bitmap，
 * GL 线程把 Bitmap 上传成纹理（首帧 glTexImage2D，后续 glTexSubImage2D 复用），再 captureFrame 进编码器。
 *
 * **数据流**：
 * - start() 里 launch 协程 → EglHelper 离屏 PBuffer + 一张纹理 + Bitmap/Canvas
 * - 每帧：drawViewToBitmap()（withContext(Dispatchers.Main) 切主线程 view.draw(canvas)）→ uploadBitmapToTexture() → captureCallback.captureFrame()
 * - FrameCaptureRenderer.captureFrame() 会 blit 当前绑定的 FBO；若 View 内容需要先画到当前 FBO 再 blit，需在 upload 后把纹理画到当前缓冲（见实现）。
 *
 * **V1（Bitmap）**：必须使用 [Bitmap.Config.ARGB_8888]，因为 view.draw(canvas) 需要可绘制的 Bitmap；
 * 单张 Bitmap 复用，不每帧创建，避免 GC 与对齐问题。
 * **V2（PBO）**：使用 [PboTextureUploader] 经 PBO 上传，减轻 CPU→GPU 同步阻塞，高分辨率下更稳。
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
    private var loopJob: Job? = null
    private var textureId = 0
    private var firstFrame = true

    @Volatile
    private var recording = false

    private lateinit var bitmap: Bitmap
    private lateinit var canvas: Canvas
    private var pboUploader: PboTextureUploader? = null

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

            // V1：ARGB_8888 为 view.draw(canvas) 所需；单张复用，不每帧创建
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap)
            firstFrame = true
            pboUploader = PboTextureUploader()

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

    private suspend fun drawViewToBitmap() = withContext(Dispatchers.Main) {
        val view = viewProvider()
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        view.draw(canvas)
    }

    private fun uploadBitmapToTexture() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        pboUploader?.let { uploader ->
            if (firstFrame) {
                uploader.uploadFirstFrame(bitmap)
                firstFrame = false
            } else {
                uploader.uploadSubImage(bitmap)
            }
        }
    }

    override fun stop() {
        recording = false
        loopJob?.cancel()
        OsrLog.i("ViewSource: stopped")
    }

    override fun release() {
        OsrLog.i("ViewSource: release start")
        recording = false
        loopJob?.cancel()
        pboUploader?.release()
        pboUploader = null
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (::bitmap.isInitialized && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        runCatching { eglHelper.release() }.onFailure { OsrLog.e("ViewSource: eglHelper.release failed", it) }
        scope.cancel()
        OsrLog.i("ViewSource: released")
    }
}
