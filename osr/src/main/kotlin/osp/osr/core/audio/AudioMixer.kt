package osp.osr.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import osp.osr.core.muxer.MuxerController
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError
import java.io.File
import java.nio.ByteBuffer

/**
 * 🎵 背景音混合器
 *
 * 从 [audioFile] 用 MediaExtractor 抽音频轨，在 Muxer 里加一条音频轨；
 * 录制中通过 [startMixing] 起协程**实时**往 Muxer 写 sample（和视频并行），
 * 音频比视频短就 seek 回开头循环，直到 [stopMixing] 被调。
 */
class AudioMixer(
    private val audioFile: File,
    private val muxerController: MuxerController
) {

    private var extractor: MediaExtractor? = null
    private var audioTrackIndex = -1
    private var sourceTrackIndex = -1
    private var mixingJob: Job? = null

    /** 采样率，从 format 里读，用来算「每包该 delay 多久」做实时节奏 */
    private var sampleRate = 44100

    /**
     * 🛠️ prepare：打开音频文件、选轨、在 Muxer 里 addAudioTrack。
     *
     * **为啥只支持 AAC**：MP4 常用、Muxer 兼容好；别的格式可能写不进去或播不了。
     * **执行后**：extractor 已选好轨、还没读 sample；Muxer 已有音频轨，等 muxer.start() 后就能 writeSampleData。
     * **下一步**：Session 里会 createDisplay/show，然后在 onFormatChanged 里 muxer.start() + startMixing()。
     */
    fun prepare(): MediaFormat {
        OsrLog.d("open audio file ${audioFile.name}")

        val ext = MediaExtractor()
        // 绑定文件，解析容器；之后 getTrackCount/getTrackFormat 可用，但还不能 readSampleData（要先 selectTrack）。
        ext.setDataSource(audioFile.absolutePath)

        sourceTrackIndex = findAudioTrack(ext)
        if (sourceTrackIndex < 0) {
            ext.release()
            OsrLog.e("no audio track in ${audioFile.name}")
            throw RecorderError.AudioError("音频文件中未找到音频轨道: ${audioFile.name}")
        }

        // 选中这条轨，后面 readSampleData/advance 都只针对它。
        ext.selectTrack(sourceTrackIndex)

        val format = ext.getTrackFormat(sourceTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (!isAacMime(mime)) {
            ext.release()
            OsrLog.e("unsupported audio mime in ${audioFile.name}: $mime, only AAC allowed")
            throw RecorderError.AudioError("仅支持 AAC 音频格式，当前为: $mime")
        }

        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
        OsrLog.d("audio sampleRate=$sampleRate")

        // Muxer 里登记一条音频轨，返回的 index 之后写 sample 时用。
        audioTrackIndex = muxerController.addAudioTrack(format)
        extractor = ext
        OsrLog.d("audio track added index=$audioTrackIndex")
        return format
    }

    /**
     * ▶️ startMixing：在 [scope] 里起一个协程，边读文件边往 Muxer 写音频 sample（和视频并行）。
     *
     * **为啥要「节奏控制」**：纯 IO 读文件比实时快太多，不 delay 的话几秒的音频会一瞬间写完，和视频对不上。
     * 所以用「当前写到的 pts」换算成真实时间，sleep 到那个点再写下一包，模拟「按时间轴播」。
     * **pts 从 0 开始**：和视频轨归零后的 pts 对齐，播出来音画同步。
     *
     * **效果**：协程里 while(isActive) 循环，读一包 → 写 Muxer → advance；到文件末尾就 seekTo(0) 循环。stopMixing 时 cancel 掉。
     */
    fun startMixing(scope: CoroutineScope) {
        val ext = extractor ?: throw RecorderError.AudioError("AudioMixer 尚未准备")
        OsrLog.d("startMixing")

        val frameDurationUs = 1024L * 1_000_000L / sampleRate

        mixingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocateDirect(MAX_BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            var loopOffsetUs = 0L
            var lastWrittenPtsUs = -1L
            var audioSampleCount = 0
            var lastLoggedSec = -1L
            val startTimeMs = System.currentTimeMillis()

            OsrLog.d("audio mixing loop started, frameDuration=${frameDurationUs}us")

            while (isActive) {
                // 把当前 sample 读进 buffer；<0 表示没数据了（到轨末尾），要 seek 回开头或退出。
                val sampleSize = ext.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    if (!seekToBeginning(ext)) break
                    // 用一帧时长做 PTS 递进，不插静音，避免循环时“顿一下”
                    loopOffsetUs = lastWrittenPtsUs + frameDurationUs
                    continue
                }

                val pts = loopOffsetUs + ext.sampleTime
                if (pts <= lastWrittenPtsUs) {
                    ext.advance()
                    buffer.clear()
                    continue
                }

                info.set(0, sampleSize, pts, MediaCodec.BUFFER_FLAG_SYNC_FRAME)
                // 写进 Muxer 的音频轨；同一轨 pts 必须严格递增，否则 Muxer 会报 out of order。
                muxerController.writeSampleData(audioTrackIndex, buffer, info)
                lastWrittenPtsUs = pts
                buffer.clear()
                audioSampleCount++

                val sec = pts / 1_000_000
                if (sec > lastLoggedSec) {
                    OsrLog.d("audio mixing pts=${pts}us (${sec}s), count=$audioSampleCount")
                    lastLoggedSec = sec
                }

                // 移到下一包；false = 已经到轨末尾，下一轮 readSampleData 会 <0，上面会 seek 或 break。
                if (!ext.advance()) {
                    if (!seekToBeginning(ext)) break
                    // 用一帧时长做 PTS 递进，不插静音，避免循环时“顿一下”
                    loopOffsetUs = lastWrittenPtsUs + frameDurationUs
                }

                // ⏱️ 节奏：按「当前 pts 对应的真实时间」sleep，避免一口气写完全部音频。
                val elapsedMs = System.currentTimeMillis() - startTimeMs
                val targetMs = pts / 1000
                val sleepMs = targetMs - elapsedMs
                if (sleepMs > 0) {
                    delay(sleepMs)
                }
            }
            OsrLog.d("audio mixing loop ended, totalSamples=$audioSampleCount lastPts=${lastWrittenPtsUs}us")
        }
    }

    /**
     * ⏹️ 停止实时混合：cancel 协程并 join 等它真结束，这样 stopRecord 里后面 muxer.stop() 时不会再有人写音频。
     */
    suspend fun stopMixing() {
        OsrLog.d("stopMixing")
        mixingJob?.cancel()
        mixingJob?.join()
        mixingJob = null
        OsrLog.d("stopMixing done")
    }

    /**
     * 🔁 把读位置 seek 到 0 附近的同步点（AAC 一般是首帧）；用于「音频比视频短」时循环播。
     * SEEK_TO_PREVIOUS_SYNC：找 ≤0 的最近同步帧，避免 seek 后 pts 乱序。执行后 sampleTime/readSampleData 指向该帧。
     */
    private fun seekToBeginning(ext: MediaExtractor): Boolean {
        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (ext.sampleTime < 0) {
            OsrLog.e("audio seek to 0 failed, no sample available")
            return false
        }
        OsrLog.d("audio loop seek to 0, first sampleTime=${ext.sampleTime}us")
        return true
    }

    /**
     * 🔌 释放 Extractor；release 后不能再 readSampleData/advance，一般 Session releaseResources 时调。
     */
    fun release() {
        OsrLog.d("release AudioMixer")
        mixingJob?.cancel()
        mixingJob = null
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
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun isAacMime(mime: String): Boolean {
        return mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == "audio/aac"
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 256 * 1024
    }
}
