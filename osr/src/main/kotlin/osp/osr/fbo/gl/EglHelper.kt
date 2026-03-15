package osp.osr.fbo.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import osp.osr.log.OsrLog

/**
 * 🖥️ 离屏 EGL 小管家（小白必读）
 *
 * **EGL 是啥**：OpenGL 不能直接和屏幕打交道，需要 EGL 做「中间人」——
 * 它负责：拿到一块「显示/绘图表面」、创建 OpenGL 的「绘图上下文」、把两者绑在一起。
 * 这样 GPU 才知道「画到哪」「用哪套状态」。
 *
 * **为啥要「离屏」**：方式 3（纯后台渲染）和方式 4（View 截图）没有现成的 GLSurfaceView，
 * 所以我们自己建一套 EGL：Display + Context + **PBuffer Surface**（一块内存里的假屏幕），
 * 在这块假屏幕上画，画完可以读到纹理里，再给编码器用。
 *
 * **数据流**：
 * - 谁创建我：OffscreenSource、ViewSource 的 start() 里
 * - init() 后：OffscreenSource 里会 makeCurrent()，然后调 Renderer.onDrawFrame / ViewSource 里画 Bitmap 上传纹理
 * - 每帧画完，会通过 FrameCaptureCallback.captureFrame() 把画面交给 FrameCaptureRenderer
 *
 * **典型调用顺序**：init() → createPbufferSurface(w,h) → makeCurrent() → （你的绘制）→ release()
 */
class EglHelper {

    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    /**
     * 🚀 初始化 EGL：拿到 Display、选 Config、创建 GLES 3.0 的 Context。
     *
     * **为啥要 GLES 3**：我们用了 glBlitFramebuffer 等 3.0 的 API，所以 context 必须开 3.0。
     * **注意**：这里还没创建 Surface，所以还不能 makeCurrent；要等 createPbufferSurface 之后。
     *
     * **之后谁用**：createPbufferSurface 会用 config；makeCurrent 会用 display/surface/context。
     */
    fun init() {
        // 拿到默认显示连接（可以理解成和「图形系统」握上手了）
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        // 选一个满足我们需求的 Config：RGBA 8bit、支持 OpenGL ES、支持 PBuffer（离屏）
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        config = configs[0] ?: throw RuntimeException("eglChooseConfig failed")

        // 创建 OpenGL ES 3.0 的上下文（和 config 绑定）
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        OsrLog.d("EglHelper: offscreen EGL context created (GLES 3.0)")
    }

    /**
     * 📐 创建 PBuffer Surface：一块 width×height 的离屏「画布」。
     *
     * **为啥用 PBuffer**：没有真实窗口时，EGL 需要一块「假窗口」才能 makeCurrent，
     * PBuffer 就是内存里的一块缓冲区，GPU 往上面画，不显示在屏幕上。
     *
     * **调用时机**：在 OffscreenSource 里，init() 之后、makeCurrent() 之前调用，
     * 传的是录制分辨率（config.videoConfig 的 width/height）。
     */
    fun createPbufferSurface(width: Int, height: Int) {
        val attribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        surface = EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")
    }

    /**
     * 🔌 把当前线程的「绘图目标」切到我们的 PBuffer，并且用我们的 context。
     *
     * **之后**：所有 GL 调用（glClear、glDrawXXX 等）都会作用在这块 PBuffer 上；
     * 例如 OffscreenSource 里会调 Renderer.onDrawFrame(null)，地图/场景就画到这块离屏上了。
     */
    fun makeCurrent() {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    /**
     * 🧹 按 EGL 规范逆序释放：先解绑 → 销毁 Surface → 销毁 Context → 终止 Display。
     * 谁调我：OffscreenSource.release()、ViewSource.release()
     */
    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        OsrLog.d("EglHelper: released")
    }
}
