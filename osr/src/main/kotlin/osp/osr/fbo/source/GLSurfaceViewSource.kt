package osp.osr.fbo.source

import android.opengl.GLSurfaceView
import osp.osr.log.OsrLog
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 📌 方式 2（GLSurfaceView）—— 边显示边录
 *
 * **小白一句话**：你有一个 GLSurfaceView 和自己的 Renderer，我们包一层「装饰器」：
 * 先调你的 onDrawFrame（画面照常上屏），再调 captureFrame（同一帧进编码器），两不耽误。
 *
 * **数据流**：
 * - init 里 surface.setRenderer(decoratorRenderer) → GLSurfaceView 之后用的就是我们
 * - 每帧：decoratorRenderer.onDrawFrame 先 userRenderer.onDrawFrame(gl)（你画的到默认缓冲/屏幕）→ 再 captureCallback.captureFrame()（FrameCaptureRenderer 会 blit 当前缓冲到 FBO → 滤镜 → 编码器）
 */
class GLSurfaceViewSource(
    private val surface: GLSurfaceView,
    private val userRenderer: GLSurfaceView.Renderer,
    private val captureCallback: FrameCaptureCallback
) : FrameSource {

    @Volatile
    private var recording = false

    private val decoratorRenderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            userRenderer.onSurfaceCreated(gl, config)
            OsrLog.d("GLSurfaceViewSource: onSurfaceCreated (decorator)")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            userRenderer.onSurfaceChanged(gl, width, height)
            OsrLog.d("GLSurfaceViewSource: onSurfaceChanged ${width}x${height}")
        }

        /** 先让你的画面画完（上屏），再录这一帧 */
        override fun onDrawFrame(gl: GL10?) {
            userRenderer.onDrawFrame(gl)
            if (recording) {
                captureCallback.captureFrame()
            }
        }
    }

    init {
        surface.setRenderer(decoratorRenderer)
    }

    override fun start() {
        recording = true
        OsrLog.i("GLSurfaceViewSource: started")
    }

    override fun stop() {
        recording = false
        OsrLog.i("GLSurfaceViewSource: stopped")
    }

    override fun release() {
        recording = false
    }
}
