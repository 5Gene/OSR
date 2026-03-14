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
 * 负责：创建 MP4 文件、添加视频轨/音频轨、写入编码后的 sample。
 * 视频轨由 Encoder 的 outputFormat 添加，音频轨由 AudioMixer 在 prepare 时添加；
 * 所有写操作 @Synchronized，避免多协程并发写导致错乱。
 */
class MuxerController(private val outputFile: File) {

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var started = false

    @Synchronized
    fun prepare() {
        OsrLog.d("muxer prepare path=${outputFile.absolutePath}")
        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        OsrLog.d("muxer created")
    }

    @Synchronized
    fun addVideoTrack(format: MediaFormat): Int {
        val m = muxer ?: throw RecorderError.MuxerError("Muxer 尚未准备")
        videoTrackIndex = m.addTrack(format)
        OsrLog.d("muxer addVideoTrack index=$videoTrackIndex")
        return videoTrackIndex
    }

    @Synchronized
    fun addAudioTrack(format: MediaFormat): Int {
        val m = muxer ?: throw RecorderError.MuxerError("Muxer 尚未准备")
        audioTrackIndex = m.addTrack(format)
        OsrLog.d("addAudioTrack index=$audioTrackIndex")
        return audioTrackIndex
    }

    @Synchronized
    fun start() {
        if (started) return
        OsrLog.d("muxer start")
        muxer?.start() ?: throw RecorderError.MuxerError("Muxer 尚未准备")
        started = true
    }

    @Synchronized
    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) return
        muxer?.writeSampleData(trackIndex, buffer, info)
    }

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
