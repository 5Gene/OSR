package osp.osr.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import osp.osr.core.muxer.MuxerController
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError
import java.io.File
import java.nio.ByteBuffer

/**
 * 🎵 背景音混合器
 *
 * 从 [audioFile] 里用 MediaExtractor 抽出音频轨，在 Muxer 里加一条音频轨；
 * 录制结束后 [writeTo] 按视频时长写入 sample，若音频较短则 seek 回开头循环，直到填满视频时长。
 */
class AudioMixer(
    private val audioFile: File,
    private val muxerController: MuxerController
) {

    private var extractor: MediaExtractor? = null
    private var audioTrackIndex = -1
    private var sourceTrackIndex = -1

    /** 打开音频文件、找到音频轨、在 Muxer 里添加音频轨 */
    fun prepare(): MediaFormat {
        OsrLog.d("open audio file ${audioFile.path}")
        val ext = MediaExtractor()
        ext.setDataSource(audioFile.absolutePath)

        sourceTrackIndex = findAudioTrack(ext)
        if (sourceTrackIndex < 0) {
            ext.release()
            OsrLog.e("no audio track in ${audioFile.name}")
            throw RecorderError.AudioError("音频文件中未找到音频轨道: ${audioFile.name}")
        }

        ext.selectTrack(sourceTrackIndex)
        val format = ext.getTrackFormat(sourceTrackIndex)
        audioTrackIndex = muxerController.addAudioTrack(format)
        extractor = ext
        OsrLog.d("audio track added index=$audioTrackIndex")
        return format
    }

    /**
     * 按 [videoDurationUs] 时长往 Muxer 写音频 sample；
     * 音频播完就 seek 到 0 继续写，时间戳用 loopOffsetUs 累加，保证递增。
     */
    fun writeTo(videoDurationUs: Long) {
        OsrLog.d("audio mix start videoDurationUs=${videoDurationUs}us")
        val ext = extractor ?: throw RecorderError.AudioError("AudioMixer 尚未准备")
        val buffer = ByteBuffer.allocateDirect(MAX_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        var loopOffsetUs = 0L
        var lastSampleTimeUs = 0L
        var audioSampleCount = 0
        var lastLoggedSec = -1L

        while (true) {
            val sampleSize = ext.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                loopOffsetUs = lastSampleTimeUs
                OsrLog.d("audio mix loop seek to 0, next loopOffsetUs=${loopOffsetUs}us")
                ext.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                continue
            }

            val pts = loopOffsetUs + ext.sampleTime
            if (pts >= videoDurationUs) break

            info.set(0, sampleSize, pts, ext.sampleFlags)
            muxerController.writeSampleData(audioTrackIndex, buffer, info)
            lastSampleTimeUs = pts
            buffer.clear()
            audioSampleCount++
            val sec = pts / 1_000_000
            if (sec > lastLoggedSec) {
                OsrLog.d("audio mix write samples up to pts=${pts}us (${sec}s), count=$audioSampleCount")
                lastLoggedSec = sec
            }

            if (!ext.advance()) {
                loopOffsetUs = lastSampleTimeUs
                OsrLog.d("audio mix loop seek to 0 (end of stream), loopOffsetUs=${loopOffsetUs}us")
                ext.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
        }
        OsrLog.d("audio mix done totalSamples=$audioSampleCount lastPts=${lastSampleTimeUs}us")
    }

    fun release() {
        OsrLog.d("release AudioMixer")
        try {
            extractor?.release()
        } catch (_: Exception) {
        }
        extractor = null
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME) ?: continue
            // audio/vorbis无法添加到mp4，必须是acc格式
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 256 * 1024
    }
}
