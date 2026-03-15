package osp.osr.fbo

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import osp.osr.fbo.filter.FilterPipeline
import osp.osr.fbo.gl.EglSurfaceManager
import osp.osr.fbo.gl.GlUtil
import osp.osr.fbo.gl.TextureProgram
import osp.osr.fbo.source.FrameCaptureCallback
import osp.osr.fbo.source.SourceSizeSink
import osp.osr.log.OsrLog

/**
 * 🎯 FBO 帧捕获核心（实现 FrameCaptureCallback + SourceSizeSink）
 *
 * **小白一句话**：谁在「画」（地图/View/离屏），每画完一帧就调我 captureFrame()；
 * 我负责把当前画面拷到自己的 FBO → 走一遍滤镜链 → 画到编码器 Surface，这一帧就进视频了。
 *
 * **一整帧的数据流**（类+方法级别）：
 * 1. 各种 FrameSource（如 CaptureRendererSource.onDrawFrame）里调 captureCallback.captureFrame()
 *    → 进到本类 captureFrame()
 * 2. 保存宿主 EGL 状态（display/draw/read/context），方便最后恢复
 * 3. glGetIntegerv(GL_FRAMEBUFFER_BINDING) 拿到当前画的是哪个 FBO，glGetIntegerv(GL_VIEWPORT) 拿到尺寸
 * 4. glBlitFramebuffer：从当前 FBO 拷到我们的 fboId（GPU 内拷贝，不读回 CPU）
 * 5. filterPipeline.render(fboTexId) → BlurFilter/WaterRippleFilter 等依次处理 → 返回最终纹理 ID
 * 6. eglSurfaceManager.makeCurrent() 切到编码器 Surface
 * 7. textureProgram.draw(outputTex) → GlUtil.drawTexture → 把最终图画到编码器
 * 8. eglSurfaceManager.swapBuffers() → 这一帧提交给 MediaCodec
 * 9. finally 里 EGL14.eglMakeCurrent(宿主) 恢复，宿主可以继续画下一帧
 *
 * **skipEglRestore**：方式 3/4 没有「宿主 GL 线程」要恢复，所以为 true 时不保存/恢复 EGL，避免无效调用。
 *
 * **SourceSizeSink**：方式 1/2 下，CaptureRendererSource/GLSurfaceViewSource 在 onSurfaceChanged 中调用 setSourceSize，
 * captureFrame 优先用该尺寸作为 blit 源矩形，避免 GL_VIEWPORT 被宿主局部修改导致录制裁剪/偏移。
 */

class FrameCaptureRenderer(
    private val width: Int,
    private val height: Int,
    encoderSurface: Surface,
    private val filterPipeline: FilterPipeline,
    private val skipEglRestore: Boolean = false,
    private val context: Context? = null
) : FrameCaptureCallback, SourceSizeSink {

    @Volatile
    private var initialized = false

    /** 宿主 Surface 真实尺寸（onSurfaceChanged 设置），用于 blit 源矩形；未设置时回退 GL_VIEWPORT */
    @Volatile
    private var sourceWidth = 0

    @Volatile
    private var sourceHeight = 0

    override fun setSourceSize(width: Int, height: Int) {
        sourceWidth = width.coerceAtLeast(0)
        sourceHeight = height.coerceAtLeast(0)
    }

    @Volatile
    private var recording = false

    private var fboId = 0
    private var fboTexId = 0
    private var textureProgram: TextureProgram? = null
    private var eglSurfaceManager: EglSurfaceManager? = null

    private val pendingEncoderSurface: Surface = encoderSurface

    /**
     * 🛠️ 在 GL 线程里调用一次，创建 FBO、编码器 EGL Surface、滤镜管线、全屏 program。
     * 谁调我：FboRecorderSession.startRecord() 里，在 frameSource.start() 之前调一次。
     */
    fun initGL() {
        OsrLog.i("FrameCaptureRenderer: initGL start ${width}x${height}, skipEglRestore=$skipEglRestore")
        checkGles30Support()

        // 我们自己的「中转 FBO」：先 blit 一帧进来，再给滤镜链当输入
        val pair = GlUtil.createFboWithTexture(width, height)
        fboId = pair.first
        fboTexId = pair.second
        OsrLog.i("FrameCaptureRenderer: FBO created fboId=$fboId fboTexId=$fboTexId")

        // 用当前线程已有的 EGL 环境，给编码器 Surface 建一块 EGL Surface（画上去 = 进编码器）
        val currentDisplay = EGL14.eglGetCurrentDisplay()
        val currentContext = EGL14.eglGetCurrentContext()
        eglSurfaceManager = EglSurfaceManager(currentDisplay, currentContext, pendingEncoderSurface)

        textureProgram = TextureProgram()
        filterPipeline.init(width, height)

        initialized = true
        recording = true
        OsrLog.i("FrameCaptureRenderer: initGL done ${width}x${height}")
    }

    fun stopCapture() {
        recording = false
        OsrLog.i("FrameCaptureRenderer: stopCapture")
    }

    /**
     * 📸 捕获当前帧并送入编码器（核心流程见类注释）。
     * 谁调我：各 FrameSource 在「每帧画完」的时机调，例如 CaptureRendererSource.onDrawFrame、GLSurfaceViewSource.onDrawFrame。
     */
    override fun captureFrame() {
        if (!initialized || !recording) return

        val hostDisplay: EGLDisplay
        val hostDrawSurface: EGLSurface
        val hostReadSurface: EGLSurface
        val hostContext: EGLContext

        if (!skipEglRestore) {
            hostDisplay = EGL14.eglGetCurrentDisplay()
            hostDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            hostReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            hostContext = EGL14.eglGetCurrentContext()
        } else {
            hostDisplay = EGL14.EGL_NO_DISPLAY
            hostDrawSurface = EGL14.EGL_NO_SURFACE
            hostReadSurface = EGL14.EGL_NO_SURFACE
            hostContext = EGL14.EGL_NO_CONTEXT
        }

        // 宿主 GL 状态：仅在不恢复 EGL 时保存/恢复，避免滤镜链和编码阶段污染宿主（viewport/program/纹理等）
        val savedViewport = IntArray(4)
        val savedFbo = IntArray(1)
        val savedProgram = IntArray(1)
        val savedActiveTexture = IntArray(1)
        val savedTextureBinding = IntArray(1)

        if (!skipEglRestore) {
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, savedViewport, 0)
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, savedFbo, 0)
            GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, savedProgram, 0)
            GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, savedActiveTexture, 0)
            GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, savedTextureBinding, 0)
        }

        try {
            // 当前绑定的 FBO（可能是 0=默认缓冲，也可能是地图/View 的 FBO）
            val srcFbo = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, srcFbo, 0)

            val viewport = IntArray(4)
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
            // 优先用宿主 onSurfaceChanged 提供的尺寸，避免 GL_VIEWPORT 被局部修改导致录制裁剪/偏移
            val srcW = if (sourceWidth > 0 && sourceHeight > 0) sourceWidth else viewport[2]
            val srcH = if (sourceWidth > 0 && sourceHeight > 0) sourceHeight else viewport[3]

            // 从「当前画面」拷到我们的 FBO；用 GL_LINEAR 做缩放时插值
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo[0])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fboId)
            GLES30.glBlitFramebuffer(
                0, 0, srcW, srcH,
                0, 0, width, height,
                GLES30.GL_COLOR_BUFFER_BIT,
                GLES30.GL_LINEAR
            )

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, srcFbo[0])

            // 滤镜链：fboTexId → 模糊/水波纹/圆角… → 返回最终纹理，下一句会把它画到编码器
            val outputTex = filterPipeline.render(fboTexId)

            eglSurfaceManager?.makeCurrent()
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            textureProgram?.draw(outputTex)
            eglSurfaceManager?.swapBuffers()
        } finally {
            if (!skipEglRestore) {
                // 恢复宿主 GL 状态，避免下一帧出现闪烁或透明度异常
                GLES30.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, savedFbo[0])
                GLES30.glUseProgram(savedProgram[0])
                GLES30.glActiveTexture(savedActiveTexture[0])
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, savedTextureBinding[0])
                EGL14.eglMakeCurrent(hostDisplay, hostDrawSurface, hostReadSurface, hostContext)
            }
        }
    }

    fun release() {
        if (!initialized) return
        OsrLog.i("FrameCaptureRenderer: release start")
        initialized = false
        recording = false

        runCatching { filterPipeline.release() }.onFailure { OsrLog.e("FrameCaptureRenderer: filterPipeline.release failed", it) }
        runCatching { textureProgram?.release() }.onFailure { OsrLog.e("FrameCaptureRenderer: textureProgram.release failed", it) }
        runCatching { eglSurfaceManager?.release() }.onFailure {
            OsrLog.e(
                "FrameCaptureRenderer: eglSurfaceManager.release failed",
                it
            )
        }
        runCatching {
            if (fboId != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES30.glDeleteTextures(1, intArrayOf(fboTexId), 0)
            }
        }.onFailure { OsrLog.e("FrameCaptureRenderer: FBO/tex delete failed", it) }
        fboId = 0
        fboTexId = 0
        OsrLog.i("FrameCaptureRenderer: released")
    }

    private fun checkGles30Support() {
        val version = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
        if (version[0] >= 3) return
        if (version[0] == 0 && context != null) {
            val reqGlEs = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
            if (reqGlEs >= 0x30000) return
            val msg = "FBO recording requires OpenGL ES 3.0+, device reports: 0x${Integer.toHexString(reqGlEs)}"
            OsrLog.e("FrameCaptureRenderer: $msg")
            throw RuntimeException(msg)
        }
        val msg = "FBO recording requires OpenGL ES 3.0+, current: ${version[0]}"
        OsrLog.e("FrameCaptureRenderer: $msg")
        throw RuntimeException(msg)
    }
}
