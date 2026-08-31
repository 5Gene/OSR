package osp.osr.fbo

import android.content.Context
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 🎬 FBO 策略的录制会话编排
 *
 * **状态机**：IDLE → PREPARING → PREPARED → RECORDING → STOPPING → RELEASED
 *
 * UI（View 工厂）可在 prepare 未完成时调用 [startRecord]：会挂起等待 [prepared]，
 * 避免 frameSource 尚未赋值就启动导致首帧超时。
 */
internal class FboRecorderSession(
    private val context: Context,
    private val config: RecorderConfig,
    private val fboConfig: FboConfig
) : RecorderSession {

    private val state = AtomicReference(RecorderState.IDLE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prepared = CompletableDeferred<Unit>()
    private val startRequested = AtomicBoolean(false)
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
        checkAndTransition(RecorderState.IDLE, RecorderState.PREPARING)

        try {
            // 用户 fbo { filters { blur{} ... } } 时，这里把滤镜列表塞进 FilterPipeline，后面 init 时再创建 GL 资源
            fboConfig.filterConfig?.let { fc ->
                if (fc.filters.isNotEmpty()) {
                    filterPipeline.setFilters(fc.filters)
                }
            }

            // 编码器先 prepare，拿到 InputSurface，后面 FrameCaptureRenderer 画的内容就是进这个 Surface
            val surface = encoderController.prepare()
            OsrLog.i("FboSession: 🖼️ encoder surface ready ${config.videoConfig.width}x${config.videoConfig.height}")
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
                    val cachedView: View = sourceConfig.factory(session)
                    ViewSource(
                        viewProvider = cachedView,
                        captureCallback = renderer,
                        width = w, height = h, fps = fps,
                        glInit = { captureRenderer?.initGL() }
                    )
                }
            }

            checkAndTransition(RecorderState.PREPARING, RecorderState.PREPARED)
            prepared.complete(Unit)
            OsrLog.i("FboSession: ✅ prepare done PREPARED source=${sourceConfig::class.simpleName}")
        } catch (e: Exception) {
            OsrLog.e("FboSession: prepare failed", e)
            prepared.cancel(CancellationException("prepare failed", e))
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifier.notifyError(wrapError(e))
            throw e
        }
    }

    /**
     * ▶️ 申请开始录制。prepare 未完成时挂起等待；完成后进入 RECORDING。
     * View 工厂内可安全调用（session 交给 View 自主决定时机）。
     */
    override fun startRecord(onReady: (() -> Unit)?) {
        if (!startRequested.compareAndSet(false, true)) {
            throw RecorderError.EncoderError("录制已申请启动")
        }
        OsrLog.i("FboSession: startRecord requested, await prepare")
        scope.launch {
            try {
                prepared.await()
                OsrLog.i("FboSession: prepare ready → RECORDING")
                checkAndTransition(RecorderState.PREPARED, RecorderState.RECORDING)
                startRecordNow(onReady)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OsrLog.e("FboSession: startRecord failed", e)
                state.set(RecorderState.RELEASED)
                releaseResources()
                notifier.notifyError(wrapError(e))
            }
        }
    }

    private fun startRecordNow(onReady: (() -> Unit)?) {
        // 方式 1/2：禁止在此（非 GL）线程 initGL，改由 FrameCaptureRenderer.captureFrame 在宿主 GL 线程懒初始化
        // 方式 3/4：仍由 ViewSource/OffscreenSource 在 makeCurrent 后显式调 glInit，行为不变

        // 🎥 开机；NO_MEMORY 时内部会换成 MaxFS/720p 的新 InputSurface
        val result = encoderController.start()
        if (result.surfaceReplaced) {
            // 🛟 必须在 frameSource.start() / 懒 initGL 之前换目标，否则 EGL 绑到已 release 的旧 Surface
            captureRenderer?.updateEncoderTarget(
                encoderController.surface,
                config.videoConfig.width,
                config.videoConfig.height
            )
        }
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

        scope.launch {
            try {
                if (!encoderController.awaitFirstVideoFrame(FIRST_FRAME_TIMEOUT_MS)) {
                    throw RecorderError.EncoderError("FBO 等待第一帧超时")
                }
                withContext(Dispatchers.Main.immediate) {
                    onReady?.invoke()
                    notifier.notifyStart()
                    OsrLog.i("FboSession: 🎉 onReady+onStart")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OsrLog.e("FboSession: 💀 first-frame wait failed", e)
                state.set(RecorderState.RELEASED)
                releaseResources()
                notifier.notifyError(wrapError(e))
            }
        }
    }

    /**
     * ⏹️ 停止录：先停帧源和 capture，再让编码器收 EOS，等编码循环结束，停音频，最后 stop Muxer，通知 onStop/onSaved。
     */
    /**
     * ⏹️ 停录。非 RECORDING（已 STOPPING/RELEASED）直接 return，避免按钮与 onEnd 双停炸状态机。
     */
    override fun stopRecord() {
        if (!state.compareAndSet(RecorderState.RECORDING, RecorderState.STOPPING)) {
            OsrLog.w("FboSession: stopRecord ignored, state=${state.get()} (need RECORDING)")
            return
        }
        OsrLog.i("FboSession: stopRecord RECORDING -> STOPPING lastPts=${ptsNormalizer.lastPts}us")

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
        if (!prepared.isCompleted) {
            prepared.cancel(CancellationException("session released"))
        }
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

    companion object {
        /** 与 Presentation 三轮超时同量级，无 VD reattach */
        private const val FIRST_FRAME_TIMEOUT_MS = 3600L
    }
}
