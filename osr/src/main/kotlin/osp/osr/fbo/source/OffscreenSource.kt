package osp.osr.fbo.source

import android.opengl.GLSurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import osp.osr.fbo.gl.EglHelper
import osp.osr.log.OsrLog
import javax.microedition.khronos.opengles.GL10

/**
 * 📌 方式 3（Offscreen）—— 纯后台录，不显示
 *
 * **小白一句话**：没有 GLSurfaceView，只有你的 Renderer（例如自己画地图/场景），
 * 我们在协程里自建 EGL PBuffer，按 fps 循环调 onDrawFrame + captureFrame，画面只进视频不进屏幕。
 *
 * **数据流**：
 * - start() 里 launch 协程 → EglHelper.init() + createPbufferSurface + makeCurrent()（当前线程变成「我们的 GL 线程」）
 * - renderer.onSurfaceCreated/onSurfaceChanged/onDrawFrame 在你的实现里画到当前 FBO/默认缓冲
 * - captureCallback.captureFrame() → FrameCaptureRenderer 会 blit 当前缓冲 → 滤镜 → 编码器 Surface（skipEglRestore=true 不恢复宿主）
 * - 按 frameIntervalMs 限帧，避免跑满 CPU
 */
class OffscreenSource(
    private val renderer: GLSurfaceView.Renderer,
    private val captureCallback: FrameCaptureCallback,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val glInit: (() -> Unit)? = null
) : FrameSource {

    private val eglHelper = EglHelper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    @Volatile
    private var recording = false

    override fun start() {
        recording = true
        loopJob = scope.launch {
            eglHelper.init()
            eglHelper.createPbufferSurface(width, height)
            eglHelper.makeCurrent()

            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, width, height)

            glInit?.invoke()

            val frameIntervalMs = 1000L / fps
            OsrLog.i("OffscreenSource: render loop started ${width}x${height} @${fps}fps")

            while (isActive && recording) {
                val start = System.currentTimeMillis()
                renderer.onDrawFrame(null as GL10?)
                captureCallback.captureFrame()
                val elapsed = System.currentTimeMillis() - start
                val sleepMs = (frameIntervalMs - elapsed).coerceAtLeast(0)
                if (sleepMs > 0) delay(sleepMs)
            }
            OsrLog.i("OffscreenSource: render loop ended")
        }
    }

    override fun stop() {
        recording = false
        loopJob?.cancel()
        OsrLog.i("OffscreenSource: stopped")
    }

    override fun release() {
        OsrLog.i("OffscreenSource: release start")
        recording = false
        loopJob?.cancel()
        runCatching { eglHelper.release() }.onFailure { OsrLog.e("OffscreenSource: eglHelper.release failed", it) }
        scope.cancel()
        OsrLog.i("OffscreenSource: released")
    }
}
