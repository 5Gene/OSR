package osp.osr.core.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError
import java.io.File
import java.nio.ByteBuffer

/**
 * 📀 MP4 混流器封装
 *
 * **职责**：创建 MP4 文件、addTrack 视频/音频轨、writeSampleData 写编码后的 sample。
 * 视频轨在 Encoder 的 onFormatChanged 里 add（用 encoder.outputFormat），音频轨在 AudioMixer.prepare 里 add。
 * 所有方法 @Synchronized，因为视频和音频两个协程会同时写，MediaMuxer 要求单线程写，我们锁一下保平安。
 */
class MuxerController(private val outputFile: File) {

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var started = false

    /**
     * 🛠️ prepare：创建 MP4 容器，打开输出文件。
     *
     * **效果**：文件已创建或截断，但还没写轨信息；只能 addTrack，不能 writeSampleData。
     * **调用顺序**：Session.prepare 里先 muxer.prepare()、audioMixer.prepare()（会 addAudioTrack），
     * 等 EncoderLoop 里收到 OUTPUT_FORMAT_CHANGED 再 addVideoTrack + start()，之后才能写。
     */
    @Synchronized
    fun prepare() {
        OsrLog.d("muxer prepare path=${outputFile.absolutePath}")
        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        OsrLog.d("muxer created")
    }

    /**
     * 📌 往容器里登记一条视频轨，返回 trackIndex。
     *
     * **为啥必须在 start 前调**：MediaMuxer 规定先 addTrack 再 start，start 之后就不能再 add 了。
     * **谁调**：EncoderController 的 onFormatChanged（EncoderLoop 里第一次拿到格式时），同时那次回调里还会 start()。
     */
    @Synchronized
    fun addVideoTrack(format: MediaFormat): Int {
        val m = muxer ?: throw RecorderError.MuxerError("Muxer 尚未准备")
        videoTrackIndex = m.addTrack(format)
        OsrLog.d("muxer addVideoTrack index=$videoTrackIndex")
        return videoTrackIndex
    }

    /**
     * 📌 往容器里登记一条音频轨，返回 trackIndex。
     *
     * **谁调**：AudioMixer.prepare()，用 extractor.getTrackFormat 拿到的 format。同样必须在 start 前。
     */
    @Synchronized
    fun addAudioTrack(format: MediaFormat): Int {
        val m = muxer ?: throw RecorderError.MuxerError("Muxer 尚未准备")
        audioTrackIndex = m.addTrack(format)
        OsrLog.d("addAudioTrack index=$audioTrackIndex")
        return audioTrackIndex
    }

    /**
     * ▶️ start：根据已经 add 的轨写文件头/元数据，之后 writeSampleData 才会真正落盘。
     *
     * **效果**：视频轨、音频轨可以开始写 sample；每条轨的 pts 必须严格递增，否则会 out of order 异常。
     * **谁调**：Session 的 onFormatChanged 里，和 addVideoTrack 同一次（Muxer 先 add 视频再 start）。
     */
    @Synchronized
    fun start() {
        if (started) return
        OsrLog.d("muxer start")
        muxer?.start() ?: throw RecorderError.MuxerError("Muxer 尚未准备")
        started = true
    }

    /**
     * 📤 写一帧/一包到指定轨；buffer 的 [offset, offset+size) 写入，pts 用 info.presentationTimeUs。
     *
     * **为啥要 @Synchronized**：视频在 EncoderLoop 的 onFrame 里写，音频在 AudioMixer 协程里写，会并发；
     * MediaMuxer.writeSampleData 本身线程安全，我们再加一层锁更稳。同一轨 pts 必须递增！
     * **谁调**：视频 = Session 的 onFrame；音频 = AudioMixer 的 startMixing 循环。
     */
    @Synchronized
    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) return
        muxer?.writeSampleData(trackIndex, buffer, info)
    }

    /**
     * ⏹️ stop：写 moov 等索引，MP4 变成完整可播文件。
     *
     * **重要**：stop 之后不能再 writeSampleData，所以必须等「编码器 EOS + 音频协程停掉」之后再 stop。
     * **效果**：outputFile 可以拿去播了；Session 里接着 notifyStop/notifySaved，再 release。
     */
    @Synchronized
    fun stop() {
        if (!started) return
        OsrLog.d("muxer stop")
        try {
            muxer?.stop()
        } catch (_: Exception) {
        }
        started = false
    }

    /**
     * 🔌 释放 Muxer；内部会先 stop（若还没 stop），再关文件。之后不能再调用本类方法。
     */
    @Synchronized
    fun release() {
        OsrLog.d("release muxer")
        stop()
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        videoTrackIndex = -1
        audioTrackIndex = -1
    }

    fun isStarted(): Boolean = started

    fun getVideoTrackIndex(): Int = videoTrackIndex

    fun getAudioTrackIndex(): Int = audioTrackIndex
}
