package osp.osr.fbo

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
import osp.osr.log.OsrLog

/**
 * 🎯 FBO 帧捕获核心（实现 FrameCaptureCallback）
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
 */

class FrameCaptureRenderer(
    private val width: Int,
    private val height: Int,
    encoderSurface: Surface,
    private val filterPipeline: FilterPipeline,
    private val skipEglRestore: Boolean = false
) : FrameCaptureCallback {

    @Volatile
    private var initialized = false

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
        checkGles30Support()

        // 我们自己的「中转 FBO」：先 blit 一帧进来，再给滤镜链当输入
        val pair = GlUtil.createFboWithTexture(width, height)
        fboId = pair.first
        fboTexId = pair.second

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

        try {
            // 当前绑定的 FBO（可能是 0=默认缓冲，也可能是地图/View 的 FBO）
            val srcFbo = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, srcFbo, 0)

            val viewport = IntArray(4)
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)

            // 从「当前画面」拷到我们的 FBO；用 GL_LINEAR 做缩放时插值
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo[0])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fboId)
            GLES30.glBlitFramebuffer(
                0, 0, viewport[2], viewport[3],
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
                EGL14.eglMakeCurrent(hostDisplay, hostDrawSurface, hostReadSurface, hostContext)
            }
        }
    }

    fun release() {
        if (!initialized) return
        initialized = false
        recording = false

        runCatching { filterPipeline.release() }
        runCatching { textureProgram?.release() }
        runCatching { eglSurfaceManager?.release() }
        runCatching {
            if (fboId != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES30.glDeleteTextures(1, intArrayOf(fboTexId), 0)
            }
        }
        fboId = 0
        fboTexId = 0
        OsrLog.i("FrameCaptureRenderer: released")
    }

    private fun checkGles30Support() {
        val version = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
        if (version[0] < 3) {
            throw RuntimeException("FBO recording requires OpenGL ES 3.0+, current: ${version[0]}")
        }
    }
}
