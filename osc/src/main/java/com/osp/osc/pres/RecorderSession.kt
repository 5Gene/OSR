package com.osp.osc.pres

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.osp.osc.pres.core.EncoderController
import com.osp.osc.pres.core.MuxerController
import com.osp.osc.pres.core.PresentationController
import com.osp.osc.pres.core.RecorderState
import com.osp.osc.pres.core.VirtualDisplayManager
import com.osp.osc.pres.coroutine.RecorderScope
import com.osp.osc.pres.error.RecorderError
import com.osp.osc.pres.listener.RecorderListener
import kotlinx.coroutines.CoroutineScope
import java.io.File

class RecorderSession(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val iFrameInterval: Int,
    private val densityDpi: Int,
    private val outputFile: File,
    private val presentationFactory: (Display, RecorderSession) -> Presentation,
    private val listener: RecorderListener?
) {

    private val displayManager: DisplayManager by lazy {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val recorderScope = RecorderScope()

    private var encoderController: EncoderController? = null
    private var muxerController: MuxerController? = null
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private var presentationController: PresentationController? = null

    @Volatile
    private var currentState = RecorderState.Idle

    val state: RecorderState
        get() = currentState

    val scope: CoroutineScope
        get() = recorderScope.scope

    fun prepare() {
        if (currentState != RecorderState.Idle) {
            val error = RecorderError.IllegalStateError("Invalid state: $currentState, expected Idle")
            listener?.onError(error)
            throw error
        }

        try {
            encoderController = EncoderController(width, height, bitrate, fps, iFrameInterval)
            encoderController?.configure()

            muxerController = MuxerController(outputFile)
            muxerController?.configure(encoderController!!.getFrameChannel())

            val surface = encoderController?.surface
            if (surface == null) {
                throw RecorderError.EncoderError("Failed to get encoder surface")
            }

            virtualDisplayManager = VirtualDisplayManager(displayManager, width, height, densityDpi)
            val display = virtualDisplayManager?.createVirtualDisplay(surface)
            if (display == null) {
                throw RecorderError.DisplayError("Failed to create virtual display")
            }

            val trackIndex = muxerController?.addVideoTrack(
                android.media.MediaFormat.createVideoFormat(
                    com.osp.osc.pres.core.MediaFormatHelper.MIME_TYPE,
                    width,
                    height
                )
            ) ?: throw RecorderError.MuxerError("Failed to add video track")

            encoderController?.setTrackIndex(trackIndex)

            presentationController = PresentationController(context, display, presentationFactory)

            currentState = RecorderState.Prepared
            listener?.onPrepared()

        } catch (e: RecorderError) {
            listener?.onError(e)
            release()
            throw e
        } catch (e: Exception) {
            val error = RecorderError.ConfigurationError("Failed to prepare recorder", e)
            listener?.onError(error)
            release()
            throw error
        }
    }

    fun startRecord() {
        if (currentState != RecorderState.Prepared) {
            val error = RecorderError.IllegalStateError("Invalid state: $currentState, expected Prepared")
            listener?.onError(error)
            throw error
        }

        try {
            presentationController?.createPresentation(this)
            presentationController?.show()

            muxerController?.startMuxer()
            muxerController?.startMuxerWrite(recorderScope.scope)

            encoderController?.startEncoder(recorderScope.scope)

            currentState = RecorderState.Recording
            listener?.onRecordingStart()

        } catch (e: RecorderError) {
            listener?.onError(e)
            throw e
        } catch (e: Exception) {
            val error = RecorderError.IllegalStateError("Failed to start recording", e)
            listener?.onError(error)
            throw error
        }
    }

    fun stopRecord() {
        if (currentState != RecorderState.Recording) {
            val error = RecorderError.IllegalStateError("Invalid state: $currentState, expected Recording")
            listener?.onError(error)
            throw error
        }

        try {
            currentState = RecorderState.Stopping

            encoderController?.stop()
            muxerController?.stop()

            presentationController?.hide()

            currentState = RecorderState.Prepared
            listener?.onRecordingStop()
            listener?.onVideoSaved(outputFile)

        } catch (e: RecorderError) {
            listener?.onError(e)
            throw e
        } catch (e: Exception) {
            val error = RecorderError.IllegalStateError("Failed to stop recording", e)
            listener?.onError(error)
            throw error
        }
    }

    fun release() {
        try {
            presentationController?.release()
            virtualDisplayManager?.release()
            encoderController?.release()
            muxerController?.release()
            recorderScope.cancel()

            currentState = RecorderState.Released

        } catch (e: Exception) {
            val error = RecorderError.IllegalStateError("Failed to release recorder", e)
            listener?.onError(error)
        }
    }
}
