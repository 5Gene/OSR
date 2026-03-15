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
import osp.osr.dsl.RecorderConfig
import osp.osr.listener.ListenerConfig
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

    private val listener: ListenerConfig get() = config.listenerConfig

    /** 📌 编码器第一帧的绝对 pts（系统单调时钟），onFrame 里用来做「归零基准」 */
    private var firstPresentationTimeUs = -1L

    /** 📌 归零后的最后一帧 pts = 视频相对时长（微秒），stopRecord 时可用于音频对齐 */
    private var lastPresentationTimeUs = 0L

    /**
     * 🛠️ prepare：搭好整条管线，但**不**开始编码。
     *
     * **为什么这样设计**：Presentation 要先 show 并渲染几帧到 Surface 上，编码器再 start，
     * 否则 encoder 一启动就会抓到「空 Surface」→ 录出来开头黑屏。
     *
     * **执行后**：调用方拿到 session，下一步通常是 `startRecord()`。
     */
    suspend fun prepare() {
        val outputPath = config.outputConfig.file?.absolutePath ?: ""
        OsrLog.i("prepare start output=$outputPath")
        OsrLog.d("prepare IDLE -> PREPARED")
        checkAndTransition(RecorderState.IDLE, RecorderState.PREPARED)

        try {
            // 👉 返回的 surface 会交给 VirtualDisplay，画上去的内容之后会被编码器吃掉
            val surface = encoderController.prepare()
            OsrLog.d("encoder surface ready")
            muxerController.prepare()
            OsrLog.d("muxer ready")
            audioMixer?.prepare()
            OsrLog.d("audioMixer prepared (optional)")

            // 👉 VirtualDisplay 绑定了 encoder 的 surface，Presentation 画到 display 上 = 画到 surface 上
            val display = displayManager.createDisplay(surface, config.videoConfig)
            OsrLog.d("VirtualDisplay ready")
            presentationController.show(display, this)
            // ⏱️ 为啥要 delay：show() 返回后，Android 还要至少 2 个 Vsync 才完成首帧布局+绘制。
            // 不等的后果：startRecord 里 encoder.start() 一开，第一帧就是黑屏，后续 onFrame 会一直收到黑帧。
            delay(PRESENTATION_SETTLE_MS)
            OsrLog.i("prepare done PREPARED, Presentation shown and settled")
        } catch (e: Exception) {
            OsrLog.e("prepare failed output=$outputPath", e)
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifyError(wrapError(e))
            throw e
        }
    }

    /**
     * ▶️ startRecord：真正开始录。编码器开跑 → 很快触发 onFormatChanged → 再一帧一帧触发 onFrame。
     *
     * **为什么先 start 再 launchEncoderLoop**：encoder.start() 后才会从 Surface 取帧并产出格式；
     * Loop 里第一次 dequeue 就会拿到 INFO_OUTPUT_FORMAT_CHANGED，然后我们的 onFormatChanged 被调，
     * 里面 addVideoTrack + muxer.start()，之后 onFrame 才能安全写视频 sample。
     */
    override fun startRecord() {
        OsrLog.i("startRecord PREPARED -> RECORDING")
        checkAndTransition(RecorderState.PREPARED, RecorderState.RECORDING)

        try {
            // 🚀 编码器一 start，就会开始「盯」着 InputSurface，Surface 上每更新一帧就编一帧。
            // 效果：EncoderController 内部的 dequeue 循环很快就会收到 OUTPUT_FORMAT_CHANGED，然后是一串视频帧。
            encoderController.start()
            OsrLog.d("encoder started")

            var videoFrameCount = 0

            encoderController.launchEncoderLoop(
                scope = recorderScope,
                // 📢 只会在「编码器第一次产出格式」时被调一次。
                // 为啥这里要 start Muxer：MediaMuxer 规定必须先 addTrack 再 start，start 之后才能 writeSampleData。
                // 效果：Muxer 进入可写状态；同时这里启动音频协程，和视频并行往 Muxer 里塞数据。
                onFormatChanged = { format ->
                    muxerController.addVideoTrack(format)
                    muxerController.start()
                    OsrLog.d("muxer started, video track added")
                    audioMixer?.startMixing(recorderScope)
                },
                // 📢 每一帧编码完都会调这个；在 EncoderController 里是「先 onFrame，再 releaseOutputBuffer」。
                // 所以 buffer 在这里是有效的，不用深拷贝，直接写给 Muxer 即可。
                onFrame = { buffer, info ->
                    val rawPts = info.presentationTimeUs
                    if (firstPresentationTimeUs < 0) firstPresentationTimeUs = rawPts
                    // 🕐 归零：编码器给的 pts 是系统单调时钟（可能几十秒起步），不归零的话播放器会在前面插一大段黑屏。
                    val normalizedPts = rawPts - firstPresentationTimeUs
                    info.presentationTimeUs = normalizedPts

                    muxerController.writeSampleData(
                        muxerController.getVideoTrackIndex(),
                        buffer,
                        info
                    )
                    lastPresentationTimeUs = normalizedPts
                    videoFrameCount++
                    if (videoFrameCount == 1) OsrLog.d("muxer video first frame rawPts=${rawPts}us normalizedPts=${normalizedPts}us")
                    else if (videoFrameCount % 30 == 0) OsrLog.d("muxer video frames=$videoFrameCount pts=${normalizedPts}us")
                }
            )

            notifyStart()
            OsrLog.d("onStart notified")
        } catch (e: Exception) {
            OsrLog.e("startRecord failed", e)
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifyError(wrapError(e))
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
        OsrLog.i("stopRecord RECORDING -> STOPPING lastPts=${lastPresentationTimeUs}us")
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

                notifyStop()
                val savedFile = config.outputConfig.file
                if (savedFile != null) {
                    OsrLog.i("notifySaved path=${savedFile.absolutePath}")
                    notifySaved(savedFile)
                }
                OsrLog.d("onStop/onSaved notified")
            } catch (e: Exception) {
                OsrLog.e("stopRecord failed", e)
                notifyError(wrapError(e))
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

    private fun notifyStart() {
        try {
            listener.onStart?.invoke()
        } catch (_: Exception) {
        }
    }

    private fun notifyStop() {
        try {
            listener.onStop?.invoke()
        } catch (_: Exception) {
        }
    }

    private fun notifySaved(file: java.io.File) {
        try {
            listener.onSaved?.invoke(file)
        } catch (_: Exception) {
        }
    }

    private fun notifyError(error: RecorderError) {
        try {
            listener.onError?.invoke(error)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** ⏱️ Presentation show 后等多长时间再认为「首帧画好了」；约 2～3 个 Vsync，减少开头黑帧概率 */
        private const val PRESENTATION_SETTLE_MS = 100L
    }
}
