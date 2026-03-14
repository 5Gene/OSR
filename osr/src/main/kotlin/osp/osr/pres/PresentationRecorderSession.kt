package osp.osr.pres

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import osp.osr.RecorderSession
import osp.osr.core.audio.AudioMixer
import osp.osr.core.encoder.EncoderController
import osp.osr.core.muxer.MuxerController
import osp.osr.dsl.RecorderConfig
import osp.osr.listener.ListenerConfig
import osp.osr.log.OsrLog
import osp.osr.model.EncodedFrame
import osp.osr.model.RecorderError
import osp.osr.model.RecorderState
import java.util.concurrent.atomic.AtomicReference

/**
 * 🎬 Presentation 方案的录制会话实现
 *
 * 编排链路：EncoderController（Surface）→ VirtualDisplayManager（Display）→ PresentationController（Presentation）
 * 编码输出经 Channel 交给 MuxerWriter 协程写入 MP4；可选 AudioMixer 在 stopRecord 时按视频时长循环写入。
 *
 * **设计模式：状态机**
 * 用 [AtomicReference] 维护 [RecorderState]，CAS 做合法转换，避免重复 start/stop 或未 prepare 就 start。
 */
internal class PresentationRecorderSession(
    context: Context,
    private val config: RecorderConfig,
    private val presentationFactory: PresentationFactory
) : RecorderSession {

    private val state = AtomicReference(RecorderState.IDLE)
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val muxerChannel = Channel<EncodedFrame>(Channel.BUFFERED)

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
    private var muxerWriterJob: Job? = null
    private var lastPresentationTimeUs = 0L

    /** 创建 Encoder Surface、Muxer、可选音频轨、VirtualDisplay，最后在主线程 show Presentation（suspend 保证异常可传播） */
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
            OsrLog.i("prepare done PREPARED")
        } catch (e: Exception) {
            OsrLog.e("prepare failed output=$outputPath", e)
            state.set(RecorderState.RELEASED)
            releaseResources()
            notifyError(wrapError(e))
            throw e
        }
    }

    override fun startRecord() {
        OsrLog.i("startRecord PREPARED -> RECORDING")
        checkAndTransition(RecorderState.PREPARED, RecorderState.RECORDING)

        try {
            encoderController.start()
            OsrLog.d("encoder started")

            encoderController.launchEncoderLoop(recorderScope, muxerChannel) { format ->
                muxerController.addVideoTrack(format)
                muxerController.start()
                OsrLog.d("muxer started, video track added")
            }

            muxerWriterJob = recorderScope.launch(Dispatchers.IO) {
                var frameCount = 0
                OsrLog.d("MuxerWriter coroutine started")
                for (frame in muxerChannel) {
                    muxerController.writeSampleData(
                        muxerController.getVideoTrackIndex(),
                        frame.buffer,
                        frame.info
                    )
                    lastPresentationTimeUs = frame.info.presentationTimeUs
                    frameCount++
                    if (frameCount == 1) OsrLog.d("muxer receive first frame pts=${frame.info.presentationTimeUs}us size=${frame.info.size}")
                    else if (frameCount % 30 == 0) OsrLog.d("muxer receive frame count=$frameCount pts=${frame.info.presentationTimeUs}us size=${frame.info.size}")
                }
                OsrLog.i("MuxerWriter done frames=$frameCount lastPts=${lastPresentationTimeUs}us")
            }

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

    override fun stopRecord() {
        OsrLog.i("stopRecord RECORDING -> STOPPING lastPts=${lastPresentationTimeUs}us")
        checkAndTransition(RecorderState.RECORDING, RecorderState.STOPPING)

        recorderScope.launch {
            try {
                encoderController.signalEndOfStream()
                OsrLog.d("signalEndOfStream sent, waiting MuxerWriter")
                muxerWriterJob?.join()
                OsrLog.d("MuxerWriter joined")

                withContext(Dispatchers.IO) {
                    audioMixer?.writeTo(lastPresentationTimeUs)
                }
                OsrLog.d("audio mix done")

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

    override fun release() {
        val prev = state.getAndSet(RecorderState.RELEASED)
        OsrLog.i("release state $prev -> RELEASED")
        if (prev == RecorderState.RELEASED) return
        releaseResources()
    }

    override fun getState(): RecorderState = state.get()

    /** 按依赖逆序释放：Presentation → Display → Encoder → Audio → Muxer → 取消协程 */
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
}
