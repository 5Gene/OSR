package osp.osr.core.util

import android.media.MediaCodec

/**
 * PTS 归零器：将编码器输出的绝对 pts 转换为从 0 开始的相对时间。
 *
 * 首帧 pts 作为基准，后续帧减去基准。
 * 两个 RecorderSession（Presentation / FBO）共享此类，避免重复逻辑。
 */
internal class PtsNormalizer {
    private var firstPts = -1L
    var lastPts = 0L
        private set

    fun normalize(info: MediaCodec.BufferInfo) {
        val raw = info.presentationTimeUs
        if (firstPts < 0) firstPts = raw
        val normalized = raw - firstPts
        info.presentationTimeUs = normalized
        lastPts = normalized
    }
}
