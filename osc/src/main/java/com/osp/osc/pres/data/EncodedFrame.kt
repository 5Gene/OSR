package com.osp.osc.pres.data

import android.media.MediaCodec
import java.nio.ByteBuffer

data class EncodedFrame(
    val buffer: ByteBuffer,
    val info: MediaCodec.BufferInfo
)
