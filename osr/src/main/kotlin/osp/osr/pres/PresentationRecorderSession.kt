package osp.osr.pres

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import osp.osr.RecorderSession
import osp.osr.core.audio.AudioMixer
import osp.osr.core.encoder.EncoderController
import osp.osr.core.encoder.virtualDisplayDensityDpi
import osp.osr.core.muxer.MuxerController
import osp.osr.core.util.PtsNormalizer
import osp.osr.core.util.SessionNotifier
import osp.osr.dsl.RecorderConfig
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError
import osp.osr.model.RecorderState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 🎬 Presentation 方案的录制会话实现
 *
 * **状态机**：IDLE → PREPARING → PREPARED → RECORDING → STOPPING → RELEASED
 *
 * Presentation.onCreate 内可安全调用 [startRecord]：会挂起等待 show/settle 完成后再真正启动。
 */
internal class PresentationRecorderSession(
    private val context: Context,
    private val config: RecorderConfig,
    private val presentationFactory: PresentationFactory
) : RecorderSession {

    private val state = AtomicReference(RecorderState.IDLE)
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prepared = CompletableDeferred<Unit>()
    private val startRequested = AtomicBoolean(false)

    private val encoderController = EncoderController(config.videoConfig)
    private val muxerController = MuxerController(
        config.outputConfig.file ?: throw RecorderError.MuxerError("未设置输出文件")
    )
    private val displayManager = VirtualDisplayManager(context)
    private val presentationController = PresentationController(presentationFactory)
    private val audioMixer: AudioMixer? = config.audioConfig.file?.let {
        AudioMixer(it, muxerController)
    }

    private val ptsNormalizer = PtsNormalizer()
    private val notifier = SessionNotifier(config.listenerConfig)

    /**
     * 🛠️ prepare：搭好管线（encoder/muxer/display/show），完成后才进入 PREPARED 并放行 startRecord。
     *
     * 🙈 旧坑：先按整屏建 VD + show，MapView 已按大画布 layout/GL 初始化；
     *    之后 startRecord 里 codec.start() 才因 NO_MEMORY 降到 MaxFS，再 VD.resize →
     *    旧大布局投到小画布，地图裁切、文字相对放大。
     * ✅ 先 start（含 MaxFS/720p 兜底）拿到最终宽高，再按最终尺寸建 VD 并 show，
     *    MapTrackView 第一次初始化就是正确尺寸/density，无需运行中 resize。
     * 📎 InputSurface 在 prepare/start 时已 create（必须在 start 前），但 VD 仍 surface=null；
     *    UI startRecord 后 handshake 才 setSurface，show 阶段不会往编码器送帧。
     */
    suspend fun prepare() {
        val outputPath = config.outputConfig.file?.absolutePath ?: ""
        OsrLog.i("prepare start output=$outputPath")
        OsrLog.d("prepare IDLE -> PREPARING")
        checkAndTransition(RecorderState.IDLE, RecorderState.PREPARING)

        try {
            encoderController.prepare()
            OsrLog.d("encoder surface ready ${config.videoConfig.width}x${config.videoConfig.height}")
            muxerController.prepare()
            OsrLog.d("muxer ready")
            audioMixer?.prepare()
            OsrLog.d("audioMixer prepared (optional)")

            // 🎥 在 show 前开机：NO_MEMORY 时内部 MaxFS/720p 重配，宽高写回 videoConfig
            // 此时还没有 VD/Presentation，不用处理 surfaceReplaced
            encoderController.start()
            OsrLog.i(
                "🎥 encoder started before Presentation request=" +
                    "${encoderController.requestedWidth}x${encoderController.requestedHeight} " +
                    "final=${config.videoConfig.width}x${config.videoConfig.height}"
            )

            // ⚠️ encodeW 必须在 start 之后读，否则拿到的是降档前的尺寸
            val encodeW = config.videoConfig.width
            val screenW = encoderController.requestedWidth
            val densityDpi = virtualDisplayDensityDpi(
                deviceDpi = config.videoConfig.densityDpi,
                encodeW = encodeW,
                screenW = screenW
            )
            OsrLog.i(
                "📏 dpi baseDpi=${config.videoConfig.densityDpi} encodeW=$encodeW screenW=$screenW → densityDpi=$densityDpi"
            )
            // surface=null：等 UI startRecord 握手再绑 InputSurface（见 handshakeUntilFirstFrame）
            val display = displayManager.createDisplay(
                surface = null,
                videoConfig = config.videoConfig,
                densityDpi = densityDpi
            )
            OsrLog.d("🖥️ VirtualDisplay ready densityDpi=$densityDpi")
            presentationController.show(display, this)
            delay(PRESENTATION_SETTLE_MS)

            checkAndTransition(RecorderState.PREPARING, RecorderState.PREPARED)
            prepared.complete(Unit)
            OsrLog.i("✅ prepare done PREPARED encode=${config.videoConfig.width}x${config.videoConfig.height}")
        } catch (e: Exception) {
            OsrLog.e("prepare failed output=$outputPath", e)
            prepared.cancel(CancellationException("prepare failed", e))
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifier.notifyError(wrapError(e))
            throw e
        }
    }

    /**
     * ▶️ 申请开始录制。prepare 未完成时挂起等待；完成后进入 RECORDING + VD 握手。
     */
    override fun startRecord(onReady: (() -> Unit)?) {
        if (!startRequested.compareAndSet(false, true)) {
            throw RecorderError.EncoderError("录制已申请启动")
        }
        OsrLog.i("startRecord requested, await prepare")
        recorderScope.launch {
            try {
                prepared.await()
                OsrLog.i("prepare ready → RECORDING")
                checkAndTransition(RecorderState.PREPARED, RecorderState.RECORDING)
                startRecordNow(onReady)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OsrLog.e("startRecord failed", e)
                state.set(RecorderState.RELEASED)
                releaseResources()
                notifier.notifyError(wrapError(e))
            }
        }
    }

    /**
     * ▶️ prepare 已把编码器 start 好了；这里只开输出循环 + 把 InputSurface 绑到 VD。
     *
     * 不再二次 codec.start()，也不再 VD.resize——降档已在 prepare/show 前完成，
     * Presentation/MapView 已按最终尺寸初始化。
     * 🤝 handshake：VD 从 surface=null → 绑 encoder InputSurface（真正开始送帧）。
     */
    private fun startRecordNow(onReady: (() -> Unit)?) {
        encoderController.launchEncoderLoop(
            scope = recorderScope,
            onFormatChanged = { format ->
                muxerController.addVideoTrack(format)
                muxerController.start()
                audioMixer?.startMixing(recorderScope)
                OsrLog.d("muxer started, video track added")
            },
            onFrame = { buffer, info ->
                ptsNormalizer.normalize(info)
                muxerController.writeSampleData(
                    muxerController.getVideoTrackIndex(),
                    buffer,
                    info
                )
            }
        )

        // 🙈 旧写法：start + 开编码循环后立刻 onStart，UI 只能 sleep 1 秒猜「是不是可以演戏了」。
        // 🎬 Muxer 要等 FORMAT_CHANGED 才开门，之前的帧全进垃圾桶；格式有了也不等于有画面
        //    （ColorOS 可以「格式到手、像素为零」）。动画开早了缺开头，开晚了片头发呆。
        // ✅ 等到第一帧真画面（不是 SPS 那种 CODEC_CONFIG）再主线程 onReady 开动画 + notifyStart。
        recorderScope.launch {
            try {
                handshakeUntilFirstFrame()
                withContext(Dispatchers.Main.immediate) {
                    onReady?.invoke()
                    notifier.notifyStart()
                    OsrLog.i("🎉 onReady+onStart")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OsrLog.e("💀 startRecord handshake failed", e)
                state.set(RecorderState.RELEASED)
                releaseResources()
                notifier.notifyError(wrapError(e))
            }
        }
    }

    /** 最多 3 轮：绑 → 50ms → 解绑再绑 → 等第一帧 1200ms */
    private suspend fun handshakeUntilFirstFrame() {
        val surface = encoderController.surface
        repeat(REATTACH_ROUNDS) { round ->
            currentCoroutineContext().ensureActive()
            OsrLog.d("🤝 VD reattach round=${round + 1}/$REATTACH_ROUNDS")
            displayManager.setSurface(surface)
            delay(REATTACH_SETTLE_MS)
            displayManager.setSurface(null)
            displayManager.setSurface(surface)
            if (encoderController.awaitFirstVideoFrame(FIRST_FRAME_TIMEOUT_MS)) {
                OsrLog.i("🎉 first frame ready round=${round + 1}")
                return
            }
        }
        throw RecorderError.EncoderError("3 轮 reattach 后仍无第一帧")
    }

    /**
     * ⏹️ stopRecord：优雅收尾。顺序很重要：先让编码器收 EOS → 等编码循环结束 → 停音频 → 再 stop Muxer。
     *
     * **为什么必须等 encoder done 再 stop Muxer**：Muxer.stop() 会写 moov 等索引，一旦 stop 就不能再 writeSampleData；
     * 若编码器还在往 Muxer 写帧，会乱套或丢帧。所以先 signalEndOfStream，等 EncoderLoop 里收到 EOS 并 break，done 才 complete。
     *
     * **幂等**：Demo 常「按钮停 + 动画 onEnd 再停」。非 RECORDING（已 STOPPING/RELEASED）直接 return，避免炸。
     */
    override fun stopRecord() {
        if (!state.compareAndSet(RecorderState.RECORDING, RecorderState.STOPPING)) {
            OsrLog.w("stopRecord ignored, state=${state.get()} (need RECORDING)")
            return
        }
        OsrLog.i("stopRecord RECORDING -> STOPPING lastPts=${ptsNormalizer.lastPts}us")

        recorderScope.launch {
            try {
                encoderController.signalEndOfStream()
                OsrLog.d("signalEndOfStream sent, waiting encoder done")
                encoderController.done.await()
                OsrLog.d("encoder done")

                audioMixer?.stopMixing()
                OsrLog.d("audio mixing stopped")

                muxerController.stop()
                OsrLog.i("muxer stopped")

                notifier.notifyStop()
                config.outputConfig.file?.let {
                    OsrLog.i("notifySaved path=${it.absolutePath}")
                    notifier.notifySaved(it)
                }
                OsrLog.d("onStop/onSaved notified")
            } catch (e: Exception) {
                OsrLog.e("stopRecord failed", e)
                notifier.notifyError(wrapError(e))
            } finally {
                state.set(RecorderState.RELEASED)
                releaseResources()
                OsrLog.i("stopRecord done RELEASED")
            }
        }
    }

    /** 🔌 外部主动释放（如 Activity 销毁）；若已 RELEASED 则忽略，否则逆序释放所有资源。 */
    override fun release() {
        val prev = state.getAndSet(RecorderState.RELEASED)
        OsrLog.i("release state $prev -> RELEASED")
        if (prev == RecorderState.RELEASED) return
        if (!prepared.isCompleted) {
            prepared.cancel(CancellationException("session released"))
        }
        releaseResources()
    }

    override fun getState(): RecorderState = state.get()

    /**
     * 🧹 按依赖逆序释放，避免悬空引用：先关 Presentation（不再画）→ 再 VirtualDisplay → Encoder → Audio → Muxer → 最后 cancel 协程。
     */
    private fun releaseResources() {
        OsrLog.d("releaseResources start")
        presentationController.dismiss()
        OsrLog.d("presentationController dismissed")
        displayManager.release()
        encoderController.release()
        audioMixer?.release()
        muxerController.release()
        recorderScope.cancel()
        OsrLog.i("releaseResources done")
    }

    private fun checkAndTransition(expected: RecorderState, next: RecorderState) {
        if (!state.compareAndSet(expected, next)) {
            OsrLog.e("invalid state transition expected=$expected actual=${state.get()}")
            throw RecorderError.EncoderError(
                "非法状态转换: 期望 $expected, 实际 ${state.get()}"
            )
        }
    }

    private fun wrapError(e: Exception): RecorderError {
        return if (e is RecorderError) e
        else RecorderError.EncoderError("录制异常", e)
    }

    companion object {
        /** ⏱️ Presentation show 后等多长时间再认为首帧稳定；约 2～3 个 Vsync */
        private const val PRESENTATION_SETTLE_MS = 100L
        private const val REATTACH_ROUNDS = 3
        private const val REATTACH_SETTLE_MS = 50L
        private const val FIRST_FRAME_TIMEOUT_MS = 1200L
    }
}
