package com.osp.osc.pres.core

import android.media.MediaMuxer
import com.osp.osc.pres.data.EncodedFrame
import com.osp.osc.pres.error.RecorderError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MuxerController(
    private val outputFile: File
) {

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isWriting = false
    private var muxerStarted = false
    private var encoderChannel: Channel<EncodedFrame>? = null
    private var errorCallback: ((RecorderError) -> Unit)? = null

    fun setErrorCallback(callback: (RecorderError) -> Unit) {
        errorCallback = callback
    }

    fun configure(encoderChannel: Channel<EncodedFrame>) {
        this.encoderChannel = encoderChannel
    }

    fun startMuxer(): Int {
        return try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            0
        } catch (e: Exception) {
            val error = RecorderError.MuxerError("Failed to create muxer", e)
            errorCallback?.invoke(error)
            throw error
        }
    }

    fun addVideoTrack(format: android.media.MediaFormat): Int {
        return try {
            videoTrackIndex = muxer?.addTrack(format) ?: -1
            videoTrackIndex
        } catch (e: Exception) {
            val error = RecorderError.MuxerError("Failed to add video track", e)
            errorCallback?.invoke(error)
            throw error
        }
    }

    fun startMuxerWrite(coroutineScope: CoroutineScope) {
        if (isWriting) return
        isWriting = true
        coroutineScope.launch(Dispatchers.IO) {
            writeLoop()
        }
    }

    private suspend fun writeLoop() = withContext(Dispatchers.IO) {
        val channel = encoderChannel ?: return@withContext
        if (!muxerStarted) {
            try {
                muxer?.start()
                muxerStarted = true
            } catch (e: Exception) {
                val error = RecorderError.MuxerError("Failed to start muxer", e)
                errorCallback?.invoke(error)
                return@withContext
            }
        }
        try {
            for (frame in channel) {
                if (!isActive || !isWriting) break
                writeFrame(frame)
            }
        } catch (e: Exception) {
            val error = RecorderError.MuxerError("Error during writing", e)
            errorCallback?.invoke(error)
        }
    }

    private fun writeFrame(frame: EncodedFrame) {
        if (videoTrackIndex < 0 || !muxerStarted) return
        try {
            muxer?.writeSampleData(videoTrackIndex, frame.buffer, frame.info)
        } catch (e: Exception) {
            val error = RecorderError.MuxerError("Failed to write frame", e)
            errorCallback?.invoke(error)
            throw error
        }
    }

    fun stop() {
        isWriting = false
    }

    fun release() {
        stop()
        try {
            muxer?.stop()
            muxer?.release()
            muxer = null
            muxerStarted = false
            videoTrackIndex = -1
        } catch (e: Exception) {
            val error = RecorderError.MuxerError("Error releasing muxer", e)
            errorCallback?.invoke(error)
        }
    }
}
