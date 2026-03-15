package osp.osr.pres

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import osp.osr.RecorderSession
import osp.osr.core.audio.AudioMixer
import osp.osr.core.encoder.EncoderController
import osp.osr.core.muxer.MuxerController
import osp.osr.core.util.PtsNormalizer
import osp.osr.core.util.SessionNotifier
import osp.osr.dsl.RecorderConfig
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError
import osp.osr.model.RecorderState
import java.util.concurrent.atomic.AtomicReference

/**
 * 🎬 Presentation 方案的录制会话实现
 *
 * **编排链路**：EncoderController（Surface）→ VirtualDisplayManager（Display）→ PresentationController（Presentation）
 * 编码输出通过 onFrame 回调直接写 Muxer（无 Channel 中间层）；AudioMixer 在录制期间实时并行写音频轨。
 *
 * **设计模式：状态机** 🎯
 * 用 [AtomicReference] + CAS 做合法状态转换，避免重复 start/stop、未 prepare 就 start 等非法调用。
 */
internal class PresentationRecorderSession(
    context: Context,
    private val config: RecorderConfig,
    private val presentationFactory: PresentationFactory
) : RecorderSession {

    private val state = AtomicReference(RecorderState.IDLE)
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * 🛠️ prepare：搭好管线（encoder/muxer/display/show），**不**启动编码器。
     * 等画面就绪（如地图加载完）后调 startRecord()，再 encoder.start()，首帧就是那时的画面。
     */
    suspend fun prepare() {
        val outputPath = config.outputConfig.file?.absolutePath ?: ""
        OsrLog.i("prepare start output=$outputPath")
        OsrLog.d("prepare IDLE -> PREPARED")
        checkAndTransition(RecorderState.IDLE, RecorderState.PREPARED)

        try {
            val surface = encoderController.prepare()
            OsrLog.d("encoder surface ready")
            muxerController.prepare()
            OsrLog.d("muxer ready")
            audioMixer?.prepare()
            OsrLog.d("audioMixer prepared (optional)")

            val display = displayManager.createDisplay(surface, config.videoConfig)
            OsrLog.d("VirtualDisplay ready")
            presentationController.show(display, this)
            delay(PRESENTATION_SETTLE_MS)
            OsrLog.i("prepare done PREPARED")
        } catch (e: Exception) {
            OsrLog.e("prepare failed output=$outputPath", e)
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifier.notifyError(wrapError(e))
            throw e
        }
    }

    /**
     * ▶️ startRecord：此时再 encoder.start() + 启动编码循环，首帧就是「开始录制」时的画面。
     */
    override fun startRecord() {
        OsrLog.i("startRecord PREPARED -> RECORDING")
        checkAndTransition(RecorderState.PREPARED, RecorderState.RECORDING)

        try {
            encoderController.start()
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
            notifier.notifyStart()
        } catch (e: Exception) {
            OsrLog.e("startRecord failed", e)
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifier.notifyError(wrapError(e))
            throw e
        }
    }

    /**
     * ⏹️ stopRecord：优雅收尾。顺序很重要：先让编码器收 EOS → 等编码循环结束 → 停音频 → 再 stop Muxer。
     *
     * **为什么必须等 encoder done 再 stop Muxer**：Muxer.stop() 会写 moov 等索引，一旦 stop 就不能再 writeSampleData；
     * 若编码器还在往 Muxer 写帧，会乱套或丢帧。所以先 signalEndOfStream，等 EncoderLoop 里收到 EOS 并 break，done 才 complete。
     */
    override fun stopRecord() {
        OsrLog.i("stopRecord RECORDING -> STOPPING lastPts=${ptsNormalizer.lastPts}us")
        checkAndTransition(RecorderState.RECORDING, RecorderState.STOPPING)

        recorderScope.launch {
            try {
                // 📤 告诉编码器：Surface 不会再上新帧了。编码器会把剩余帧编完，最后一帧带 EOS 标志。
                // 效果：EncoderController 的 while 里会收到 BUFFER_FLAG_END_OF_STREAM → break → finally 里 done.complete(Unit)。
                encoderController.signalEndOfStream()
                OsrLog.d("signalEndOfStream sent, waiting encoder done")
                encoderController.done.await()
                OsrLog.d("encoder done")

                // 🔇 停掉音频协程（cancel + join），避免再往 Muxer 写音频。
                audioMixer?.stopMixing()
                OsrLog.d("audio mixing stopped")

                // 📀 写 moov 等索引，MP4 才完整可播。stop 之后不能再 writeSampleData，所以必须放在最后。
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
        releaseResources()
    }

    override fun getState(): RecorderState = state.get()

    /**
     * 🧹 按依赖逆序释放，避免悬空引用：先关 Presentation（不再画）→ 再 VirtualDisplay → Encoder → Audio → Muxer → 最后 cancel 协程。
     * 效果：EncoderLoop、AudioMixer 协程被取消；codec/extractor/muxer 全部 release，文件句柄关闭。
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

    /** 🚦 CAS 状态转换：只有当前是 expected 才改成 next，否则抛错（防止重复 start、未 prepare 就 start 等）。 */
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
    }
}
