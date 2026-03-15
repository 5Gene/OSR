package osp.osr.fbo

import android.content.Context
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import osp.osr.RecorderSession
import osp.osr.core.audio.AudioMixer
import osp.osr.core.encoder.EncoderController
import osp.osr.core.muxer.MuxerController
import osp.osr.core.util.PtsNormalizer
import osp.osr.core.util.SessionNotifier
import osp.osr.dsl.RecorderConfig
import osp.osr.fbo.filter.FilterPipeline
import osp.osr.fbo.source.CaptureRendererSource
import osp.osr.fbo.source.FrameSource
import osp.osr.fbo.source.GLSurfaceViewSource
import osp.osr.fbo.source.OffscreenSource
import osp.osr.fbo.source.ViewSource
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError
import osp.osr.model.RecorderState
import java.util.concurrent.atomic.AtomicReference

/**
 * 🎬 FBO 策略的录制会话编排（小白看这里）
 *
 * **状态机**：IDLE → PREPARED → RECORDING → STOPPING → RELEASED，和 Presentation 方案一致。
 *
 * **数据流简图**：
 * - 用户调 OSR.recorder(context, config)，config 里已经 fbo { } 过 → RenderStrategy 是 FboStrategy
 * - FboStrategy.createSession 调我们 prepare() → 根据 sourceConfig 造出 FrameSource + FrameCaptureRenderer
 * - 用户调 startRecord() → captureRenderer.initGL()、encoderController.start()、encoderController.launchEncoderLoop()、frameSource.start()
 * - 之后每一帧：FrameSource 侧「画完一帧」就调 captureRenderer.captureFrame() → FBO+滤镜→编码器 Surface → MediaCodec 编码 → onFrame 回调里 muxerController.writeSampleData
 * - 用户调 stopRecord() → frameSource.stop()、signalEndOfStream、等 encoder done、停音频、muxer.stop()、notifier 回调
 */
internal class FboRecorderSession(
    private val context: Context,
    private val config: RecorderConfig,
    private val fboConfig: FboConfig
) : RecorderSession {

    private val state = AtomicReference(RecorderState.IDLE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ptsNormalizer = PtsNormalizer()
    private val notifier = SessionNotifier(config.listenerConfig)

    private val encoderController = EncoderController(config.videoConfig)
    private val muxerController = MuxerController(
        config.outputConfig.file ?: throw RecorderError.MuxerError("未设置输出文件")
    )
    private val audioMixer: AudioMixer? = config.audioConfig.file?.let {
        AudioMixer(it, muxerController)
    }

    private var frameSource: FrameSource? = null
    private var captureRenderer: FrameCaptureRenderer? = null
    private val filterPipeline = FilterPipeline()
    private var isOffscreenMode = false

    /**
     * 🛠️ 搭好管线：滤镜、编码器、Muxer、音频（可选）、根据 sourceConfig 创建 FrameSource + FrameCaptureRenderer。
     * 谁调我：FboStrategy.createSession()，在返回 session 之前调一次。
     */
    suspend fun prepare() {
        val outputPath = config.outputConfig.file?.absolutePath ?: ""
        OsrLog.i("FboSession: prepare start output=$outputPath")
        checkAndTransition(RecorderState.IDLE, RecorderState.PREPARED)

        try {
            // 用户 fbo { filters { blur{} ... } } 时，这里把滤镜列表塞进 FilterPipeline，后面 init 时再创建 GL 资源
            fboConfig.filterConfig?.let { fc ->
                if (fc.filters.isNotEmpty()) {
                    filterPipeline.setFilters(fc.filters)
                }
            }

            // 编码器先 prepare，拿到 InputSurface，后面 FrameCaptureRenderer 画的内容就是进这个 Surface
            val surface = encoderController.prepare()
            OsrLog.i("FboSession: encoder surface ready")
            muxerController.prepare()
            OsrLog.i("FboSession: muxer ready")
            audioMixer?.prepare()

            val w = config.videoConfig.width
            val h = config.videoConfig.height
            val fps = config.videoConfig.fps
            val sourceConfig = fboConfig.sourceConfig
                ?: throw RecorderError.ConfigError("未配置帧源（renderer/glSurfaceView/offscreen/view）")

            // 方式 3/4 没有「宿主 GL 线程」需要恢复，skipEglRestore=true 避免无效的 eglMakeCurrent
            val isOffscreen = sourceConfig is FrameSourceConfig.Offscreen
                    || sourceConfig is FrameSourceConfig.ViewCapture
            isOffscreenMode = isOffscreen

            val renderer = FrameCaptureRenderer(
                width = w,
                height = h,
                encoderSurface = surface,
                filterPipeline = filterPipeline,
                skipEglRestore = isOffscreen,
                context = context
            )
            captureRenderer = renderer

            val session: RecorderSession = this
            frameSource = when (sourceConfig) {
                is FrameSourceConfig.CaptureRenderer ->
                    CaptureRendererSource(
                        attach = { r -> sourceConfig.attach(r, session) },
                        captureCallback = renderer
                    )

                is FrameSourceConfig.GlSurfaceView ->
                    GLSurfaceViewSource(sourceConfig.surface, sourceConfig.renderer, renderer).also {
                        sourceConfig.onSessionReady?.invoke(session)
                    }

                is FrameSourceConfig.Offscreen -> {
                    val userRenderer = sourceConfig.factory(session)
                    OffscreenSource(userRenderer, renderer, w, h, fps, glInit = { captureRenderer?.initGL() })
                }

                is FrameSourceConfig.ViewCapture -> {
                    var cachedView: View = sourceConfig.factory(session)
                    ViewSource(
                        viewProvider = cachedView,
                        captureCallback = renderer,
                        width = w, height = h, fps = fps,
                        glInit = { captureRenderer?.initGL() }
                    )
                }
            }

            OsrLog.i("FboSession: prepare done, source=${sourceConfig::class.simpleName}")
        } catch (e: Exception) {
            OsrLog.e("FboSession: prepare failed", e)
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifier.notifyError(wrapError(e))
            throw e
        }
    }

    /**
     * ▶️ 开始录：先让 FrameCaptureRenderer 在 GL 侧建好 FBO/编码器 Surface，再启动编码循环和帧源。
     * 之后每一帧由 FrameSource 驱动 captureFrame()，编码器 dequeue 到的数据在 onFrame 里写 Muxer。
     */
    override fun startRecord() {
        OsrLog.i("FboSession: startRecord PREPARED -> RECORDING")
        checkAndTransition(RecorderState.PREPARED, RecorderState.RECORDING)

        try {
            // 方式 1/2：必须在 GL 线程调 initGL；方式 3/4 在 ViewSource/OffscreenSource 协程里 makeCurrent 后再调
            if (!isOffscreenMode) captureRenderer?.initGL()

            encoderController.start()
            encoderController.launchEncoderLoop(
                scope = scope,
                onFormatChanged = { format ->
                    muxerController.addVideoTrack(format)
                    muxerController.start()
                    audioMixer?.startMixing(scope)
                    OsrLog.i("FboSession: muxer started, video track added")
                },
                onFrame = { buffer, info ->
                    ptsNormalizer.normalize(info)
                    muxerController.writeSampleData(
                        muxerController.getVideoTrackIndex(), buffer, info
                    )
                }
            )

            // 帧源开始「产帧」；例如 CaptureRendererSource 里 recording=true，onDrawFrame 里就会 captureFrame()
            frameSource?.start()
            notifier.notifyStart()
        } catch (e: Exception) {
            OsrLog.e("FboSession: startRecord failed", e)
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifier.notifyError(wrapError(e))
            throw e
        }
    }

    /**
     * ⏹️ 停止录：先停帧源和 capture，再让编码器收 EOS，等编码循环结束，停音频，最后 stop Muxer，通知 onStop/onSaved。
     */
    override fun stopRecord() {
        OsrLog.i("FboSession: stopRecord RECORDING -> STOPPING lastPts=${ptsNormalizer.lastPts}us")
        checkAndTransition(RecorderState.RECORDING, RecorderState.STOPPING)

        scope.launch {
            try {
                frameSource?.stop()
                captureRenderer?.stopCapture()

                encoderController.signalEndOfStream()
                OsrLog.i("FboSession: waiting encoder done")
                encoderController.done.await()
                OsrLog.i("FboSession: encoder done")

                audioMixer?.stopMixing()
                muxerController.stop()
                OsrLog.i("FboSession: muxer stopped")

                notifier.notifyStop()
                config.outputConfig.file?.let {
                    OsrLog.i("FboSession: saved ${it.absolutePath}")
                    notifier.notifySaved(it)
                }
            } catch (e: Exception) {
                OsrLog.e("FboSession: stopRecord failed", e)
                notifier.notifyError(wrapError(e))
            } finally {
                state.set(RecorderState.RELEASED)
                releaseResources()
                OsrLog.i("FboSession: stopRecord done RELEASED")
            }
        }
    }

    override fun release() {
        val prev = state.getAndSet(RecorderState.RELEASED)
        OsrLog.i("FboSession: release $prev -> RELEASED")
        if (prev == RecorderState.RELEASED) return
        releaseResources()
    }

    override fun getState(): RecorderState = state.get()

    /** 逆序释放：先停「产帧」、再停「编码/混流」、最后 cancel 协程 */
    private fun releaseResources() {
        OsrLog.i("FboSession: releaseResources start")
        runCatching { frameSource?.release() }.onFailure { OsrLog.e("FboSession: frameSource.release failed", it) }
        runCatching { captureRenderer?.release() }.onFailure { OsrLog.e("FboSession: captureRenderer.release failed", it) }
        runCatching { encoderController.release() }.onFailure { OsrLog.e("FboSession: encoderController.release failed", it) }
        runCatching { audioMixer?.release() }.onFailure { OsrLog.e("FboSession: audioMixer.release failed", it) }
        runCatching { muxerController.release() }.onFailure { OsrLog.e("FboSession: muxerController.release failed", it) }
        scope.cancel()
        OsrLog.i("FboSession: releaseResources done")
    }

    private fun checkAndTransition(expected: RecorderState, next: RecorderState) {
        if (!state.compareAndSet(expected, next)) {
            val msg = "非法状态转换: 期望 $expected, 实际 ${state.get()}"
            OsrLog.e("FboSession: $msg")
            throw RecorderError.EncoderError(msg)
        }
    }

    private fun wrapError(e: Exception): RecorderError =
        if (e is RecorderError) e else RecorderError.EncoderError("FBO录制异常", e)
}
