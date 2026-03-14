package com.osp.osc.pres.core

import android.media.MediaCodec
import android.view.Surface
import com.osp.osc.pres.data.EncodedFrame
import com.osp.osc.pres.error.RecorderError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class EncoderController(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val iFrameInterval: Int
) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val frameChannel = Channel<EncodedFrame>(Channel.BUFFERED)
    private var isEncoding = false
    private var trackIndex = -1
    private var errorCallback: ((RecorderError) -> Unit)? = null

    val surface: Surface?
        get() = inputSurface

    fun setErrorCallback(callback: (RecorderError) -> Unit) {
        errorCallback = callback
    }

    fun configure(): Int {
        return try {
            codec = MediaCodec.createEncoderByType(MediaFormatHelper.MIME_TYPE).apply {
                MediaFormatHelper.configureFormat(this, width, height, bitrate, fps, iFrameInterval)
            }
            inputSurface = codec?.createInputSurface()
            codec?.start()
            trackIndex = -1
            0
        } catch (e: Exception) {
            val error = RecorderError.EncoderError("Failed to configure encoder", e)
            errorCallback?.invoke(error)
            throw error
        }
    }

    fun startEncoder(coroutineScope: CoroutineScope) {
        if (isEncoding) return
        isEncoding = true
        coroutineScope.launch(Dispatchers.Default) {
            encodeLoop()
        }
    }

    private suspend fun encodeLoop() = withContext(Dispatchers.Default) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isActive && isEncoding) {
            try {
                val index = codec?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: -1
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Format changed
                    }

                    index >= 0 -> {
                        val outputBuffer = codec?.getOutputBuffer(index)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val frame = EncodedFrame(
                                buffer = duplicateBuffer(outputBuffer),
                                info = bufferInfo
                            )
                            frameChannel.send(frame)
                        }
                        codec?.releaseOutputBuffer(index, false)
                    }

                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output buffer available
                    }
                }
            } catch (e: Exception) {
                val error = RecorderError.EncoderError("Error during encoding", e)
                errorCallback?.invoke(error)
                break
            }
        }
    }

    private fun duplicateBuffer(original: ByteBuffer): ByteBuffer {
        val duplicate = ByteBuffer.allocate(original.remaining())
        duplicate.put(original)
        duplicate.flip()
        return duplicate
    }

    fun setTrackIndex(index: Int) {
        trackIndex = index
    }

    fun getFrameChannel(): Channel<EncodedFrame> = frameChannel

    fun stop() {
        isEncoding = false
    }

    fun release() {
        stop()
        try {
            codec?.stop()
            codec?.release()
            codec = null
            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            val error = RecorderError.EncoderError("Error releasing encoder", e)
            errorCallback?.invoke(error)
        }
    }

    companion object {
        private const val TIMEOUT_US = 10000L
    }
}
