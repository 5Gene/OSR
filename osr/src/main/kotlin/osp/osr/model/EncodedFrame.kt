package osp.osr.model

import android.media.MediaCodec
import java.nio.ByteBuffer

/**
 * 📦 一帧编码后的数据
 *
 * 从 Encoder 的 dequeueOutputBuffer 取出后，深拷贝到本结构再送入 Channel，
 * 避免 MediaCodec 复用 buffer 导致数据被覆盖。Muxer 协程消费时直接写入 MP4。
 */
data class EncodedFrame(
    val buffer: ByteBuffer,
    val info: MediaCodec.BufferInfo
)
