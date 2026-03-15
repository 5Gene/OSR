package osp.osr.fbo.source

import android.opengl.GLSurfaceView
import osp.osr.log.OsrLog
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 📌 方式 1（CaptureRenderer）—— 挂到别人的 GL 环境里录
 *
 * **小白一句话**：地图/自定义 GL 让你「传一个 Renderer 进来」时，你就传我们这个 renderer；
 * 我们会在每帧 onDrawFrame 里调 captureCallback.captureFrame()，画面就进 FBO 再进编码器。
 *
 * **数据流**：
 * - 构造时 init { attach(renderer) } → 用户代码里会把 renderer 设成地图的 customRenderer（例如高德 mapView.setCustomRenderer(renderer)）
 * - 地图每帧画完会回调 onDrawFrame(gl) → 我们里 if (recording) captureCallback.captureFrame() → FrameCaptureRenderer.captureFrame()
 *
 * **注意**：captureFrame 里会读当前 GL 的 FRAMEBUFFER_BINDING 和 VIEWPORT，所以地图必须已经画到当前 FBO/默认缓冲上。
 */
class CaptureRendererSource(
    private val attach: (GLSurfaceView.Renderer) -> Unit,
    private val captureCallback: FrameCaptureCallback
) : FrameSource {

    @Volatile
    private var recording = false

    private val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            OsrLog.d("CaptureRendererSource: onSurfaceCreated")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            (captureCallback as? SourceSizeSink)?.setSourceSize(width, height)
            OsrLog.i("CaptureRendererSource: onSurfaceChanged ${width}x${height}")
        }

        /** 每帧画完时 GL 线程回调这里；我们在这时把「当前画面」交给 FrameCaptureRenderer 去拷 FBO、走滤镜、写编码器 */
        override fun onDrawFrame(gl: GL10?) {
            if (recording) {
                captureCallback.captureFrame()
            }
        }
    }

    init {
        attach(renderer)
    }

    override fun start() {
        recording = true
        OsrLog.i("CaptureRendererSource: started")
    }

    override fun stop() {
        recording = false
        OsrLog.i("CaptureRendererSource: stopped")
    }

    override fun release() {
        recording = false
    }
}
