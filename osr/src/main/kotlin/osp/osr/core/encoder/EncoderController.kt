package osp.osr.core.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import osp.osr.dsl.VideoConfig
import osp.osr.log.OsrLog
import osp.osr.model.EncodedFrame
import osp.osr.model.RecorderError

/**
 * 🎞️ 视频编码器封装
 *
 * 负责：创建 AVC 编码器、创建 InputSurface（给 VirtualDisplay 或 FBO 渲染）、
 * 在协程里循环取编码结果并送入 Channel，供 Muxer 消费。
 *
 * 重要：编码器「产出多少帧」由 InputSurface 上多久出现一新帧决定（Presentation 的绘制间隔）。
 * 例如 Presentation 每 1000ms 画一帧 → 最多 1 帧/秒；每 33ms 画一帧 → 约 30 帧/秒。
 * launchEncoderLoop 只是不断从编码器「取」已编好的帧，不会增加或减少帧数。
 * 深拷贝 ByteBuffer 是为了避免 releaseOutputBuffer 后 buffer 被复用导致数据错乱。
 */
class EncoderController(private val videoConfig: VideoConfig) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    val surface: Surface
        get() = inputSurface ?: throw RecorderError.EncoderError("InputSurface 尚未创建")

    /** 配置并创建编码器与 InputSurface，返回的 Surface 用于绑定 VirtualDisplay 等 */
    fun prepare(): Surface {
        OsrLog.d("encoder prepare ${videoConfig.width}x${videoConfig.height} ${videoConfig.fps}fps bitrate=${videoConfig.bitrate}")
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            videoConfig.width,
            videoConfig.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoConfig.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoConfig.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoConfig.iFrameInterval)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        codec = encoder
        OsrLog.d("encoder and InputSurface created")
        return inputSurface!!
    }

    fun start() {
        OsrLog.d("encoder start")
        codec?.start() ?: throw RecorderError.EncoderError("MediaCodec 尚未准备")
    }

    /**
     * 在 [scope] 里启动一个协程：循环从编码器「取已编好的帧」，拷贝后 send 到 [muxerChannel]。
     * 编码器能产出多少帧，取决于 Surface 上多久被画一帧（Presentation 的刷新间隔），本方法只是「取」不「造」帧。
     *
     * @param scope 协程作用域，用于 launch 编码循环；协程取消时循环会退出。
     * @param muxerChannel 编码后的帧送进此 Channel，由 MuxerWriter 协程消费并写入 MP4。
     * @param onFormatChanged 首次拿到编码器 outputFormat 时调用，用于 Muxer 添加视频轨并 start；只调用一次。
     */
    fun launchEncoderLoop(
        scope: CoroutineScope,
        muxerChannel: Channel<EncodedFrame>,
        onFormatChanged: (MediaFormat) -> Unit
    ) {
        val encoder = codec ?: throw RecorderError.EncoderError("MediaCodec 尚未准备")
        // BufferInfo：编码器在 dequeueOutputBuffer 返回时会往里填入本帧的 size、pts、flags，避免重复分配
        val bufferInfo = MediaCodec.BufferInfo()

        scope.launch(Dispatchers.Default) {
            var frameCount = 0
            var timeoutCount = 0
            OsrLog.d("encoder loop started")
            try {
                // ─── 主循环：不断问编码器「有编好的输出吗？」───
                while (isActive) {
                    // dequeueOutputBuffer(bufferInfo, timeoutUs)
                    // - 作用：从编码器输出队列里取一块「已编码好」的 buffer，最多阻塞 timeoutUs 微秒。
                    // - 返回值：
                    //   INFO_TRY_AGAIN_LATER(-1)：超时时间内没有新输出（通常说明 Surface 上还没新帧被送进来编码）。
                    //   INFO_OUTPUT_FORMAT_CHANGED(-2)：编码格式已确定，需先 getOutputFormat() 再继续 dequeue。
                    //   >=0：有效输出 buffer 的槽位 index，可用 getOutputBuffer(index) 取数据。
                    // - bufferInfo：出参，被填入本帧的 offset/size/presentationTimeUs/flags。
                    // - timeoutUs：单次等待上限(微秒)。太小会空转耗 CPU，太大会让 EOS 等得久；10ms 是常见折中。
                    val index = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        // 没有新输出：Surface 上还没新图，或编码器还在处理。继续循环等待。
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            timeoutCount++
                            if (frameCount >= 1 && timeoutCount % 10000 == 0) {
                                OsrLog.e("encoder waiting for input from Surface (timeouts=$timeoutCount, no new frame drawn)")
                            }
                        }
                        // 编码格式已就绪（通常第一轮就会遇到一次）：把 format 交给 Muxer 加轨并 start。
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            OsrLog.d("encoder OUTPUT_FORMAT_CHANGED, notify muxer")
                            onFormatChanged(encoder.outputFormat)
                        }

                        // 取到一块有效输出 buffer（index 为槽位号）
                        index >= 0 -> {
                            timeoutCount = 0
                            // 按 index 取出编码器内部的 ByteBuffer（只读用，用完必须 releaseOutputBuffer）
                            val buffer = encoder.getOutputBuffer(index)
                                ?: throw RecorderError.EncoderError("输出 Buffer 为空, index=$index")

                            // CODEC_CONFIG：编码器参数（SPS/PPS 等），不写入视频流，只用于 Muxer 的 format；释放后 continue
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encoder.releaseOutputBuffer(index, false)
                                continue
                            }

                            // size>0 才是实际一帧视频数据；拷贝后送 Channel，避免 release 后 buffer 被复用导致错乱
                            if (bufferInfo.size > 0) {
                                val frameCopy = EncodedFrame(
                                    buffer = cloneByteBuffer(buffer),
                                    info = cloneBufferInfo(bufferInfo)
                                )
                                muxerChannel.send(frameCopy)
                                frameCount++
                                if (frameCount == 1) OsrLog.d("encoder first frame sent pts=${bufferInfo.presentationTimeUs}us size=${bufferInfo.size}")
                                else if (frameCount % 30 == 0) OsrLog.d("encoder frames sent: $frameCount pts=${bufferInfo.presentationTimeUs}us size=${bufferInfo.size}")
                            }

                            // 必须调用，否则编码器不会复用该槽位；render=false 表示不渲染到 Surface
                            encoder.releaseOutputBuffer(index, false)

                            // 收到 EOS 表示编码器不再有新输出，退出循环并关闭 Channel
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                OsrLog.i("encoder EOS totalFrames=$frameCount")
                                break
                            }
                        }
                    }
                }
            } finally {
                // 关闭 Channel，MuxerWriter 的 for (frame in muxerChannel) 会结束，然后 stop muxer
                muxerChannel.close()
                OsrLog.d("encoder loop finished, channel closed")
            }
        }
    }

    /** 通知编码器不再有输入，用于 stopRecord 时收尾 */
    fun signalEndOfStream() {
        OsrLog.d("encoder signalEndOfInputStream")
        codec?.signalEndOfInputStream()
    }

    fun release() {
        OsrLog.d("encoder release")
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        inputSurface = null
    }

    private fun cloneByteBuffer(src: java.nio.ByteBuffer): java.nio.ByteBuffer {
        val dst = java.nio.ByteBuffer.allocateDirect(src.remaining())
        dst.put(src)
        dst.flip()
        return dst
    }

    private fun cloneBufferInfo(src: MediaCodec.BufferInfo): MediaCodec.BufferInfo {
        return MediaCodec.BufferInfo().apply {
            set(0, src.size, src.presentationTimeUs, src.flags)
        }
    }

    companion object {
        /** 单次 dequeueOutputBuffer 最长等待时间（微秒）。10ms：既不会长时间阻塞，又避免无输出时疯狂空转。不影响「有多少帧」，只影响轮询频率。 */
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
