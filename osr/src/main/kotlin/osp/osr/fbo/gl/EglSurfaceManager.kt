package osp.osr.fbo.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import osp.osr.log.OsrLog

/**
 * 🎬 编码器 Surface 的 EGL 管家（小白必读）
 *
 * **和 EglHelper 的区别**：
 * - EglHelper：自己造一套「离屏」环境（PBuffer），给方式 3/4 用。
 * - 我：不造新环境，用**宿主已经有的** EGL Display + Context，只造一块「窗口型」Surface，
 *   这块 Surface 是 MediaCodec 的 InputSurface——画上去的内容会直接进编码器变成视频帧。
 *
 * **数据流**：
 * - 谁造我：FrameCaptureRenderer.initGL()，传入「当前线程的 display/context」和 encoder 的 Surface。
 * - makeCurrent()：把当前线程的绘制目标切到编码器 Surface；之后 glClear、TextureProgram.draw 都画到这里。
 * - swapBuffers()：把这一帧提交给编码器（类似普通 Surface 的 swap），编码器才能把这一帧拿走编码。
 *
 * **典型一帧流程**（在 FrameCaptureRenderer.captureFrame 里）：
 * 1. 宿主 GL 线程已经把地图/场景画到当前 FBO/默认缓冲
 * 2. glBlitFramebuffer 拷到我们的 FBO → FilterPipeline 处理 → 得到 outputTex
 * 3. 我们 makeCurrent() → 画到编码器 Surface → swapBuffers()
 * 4. 宿主再 makeCurrent 回原来的 Surface（在 FrameCaptureRenderer 的 finally 里）
 */

class EglSurfaceManager(
    val display: EGLDisplay,
    private val context: EGLContext,
    encoderSurface: Surface
) {
    private val eglSurface: EGLSurface

    init {
        // 优先选 RECORDABLE Config，增强 MediaCodec InputSurface 兼容性；失败再回退普通窗口 Config
        val recordableConfig = chooseConfig(recordable = true)
        val config = recordableConfig ?: chooseConfig(recordable = false)
            ?: throw RuntimeException("eglChooseConfig failed for encoder")
        if (recordableConfig == null) {
            OsrLog.w("EglSurfaceManager: EGL_RECORDABLE_ANDROID unavailable, fallback normal config")
        }

        // 关键：用 Android 的 Surface（来自 MediaCodec.createInputSurface()）创建 EGL WindowSurface。
        // 这样 GPU 画到这个 eglSurface 的内容，会直接喂给编码器。
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            OsrLog.e("EglSurfaceManager: eglCreateWindowSurface failed for encoder")
            throw RuntimeException("eglCreateWindowSurface failed for encoder")
        }
        OsrLog.i("EglSurfaceManager: encoder EGLSurface created")
    }

    /**
     * 把当前线程的绘制目标切到「编码器 Surface」。
     * 之后：FrameCaptureRenderer 里会 glViewport、glClear、TextureProgram.draw(outputTex)，
     * 所以这一帧的最终图会画到编码器，再 swapBuffers 就进 MediaCodec 了。
     */
    fun makeCurrent() {
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    /**
     * 把当前 Surface 的「后备缓冲」提交出去。
     * 对 WindowSurface 来说就是「这一帧画完了，给编码器/屏幕用」；编码器会在另一侧 dequeue 到这一帧并编码。
     * 写入纳秒 PTS，避免部分平台硬件编码器丢帧或延迟出首帧。
     */
    fun swapBuffers(nsecs: Long = System.nanoTime()) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        OsrLog.i("EglSurfaceManager: release")
        EGL14.eglDestroySurface(display, eglSurface)
    }

    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        // 0x3142 = EGL_RECORDABLE_ANDROID，部分机型不支持时回退无此属性的 Config
        val configAttribs = if (recordable) {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        return if (ok && numConfigs[0] > 0) configs[0] else null
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
