package com.osp.osc.pres.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

object MediaFormatHelper {
    const val MIME_TYPE = "video/avc"

    fun configureFormat(
        codec: MediaCodec,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        iFrameInterval: Int
    ) {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }
}
