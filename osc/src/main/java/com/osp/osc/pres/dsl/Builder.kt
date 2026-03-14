package com.osp.osc.pres.dsl

import android.app.Presentation
import android.content.Context
import android.view.Display
import com.osp.osc.pres.OffscreenRecorder
import com.osp.osc.pres.RecorderSession
import com.osp.osc.pres.listener.RecorderListener
import java.io.File

class Builder(private val context: Context) {

    private var width: Int = RecorderConfig.DEFAULT_WIDTH
    private var height: Int = RecorderConfig.DEFAULT_HEIGHT
    private var bitrate: Int = RecorderConfig.DEFAULT_BITRATE
    private var fps: Int = RecorderConfig.DEFAULT_FPS
    private var iFrameInterval: Int = RecorderConfig.DEFAULT_I_FRAME_INTERVAL
    private var densityDpi: Int = RecorderConfig.DEFAULT_DENSITY_DPI
    private var outputFile: File? = null
    private var listener: RecorderListener? = null
    private var presentationFactory: ((Display, RecorderSession) -> Presentation)? = null

    fun setVideoSize(width: Int, height: Int): Builder {
        this.width = width
        this.height = height
        return this
    }

    fun setBitrate(bitrate: Int): Builder {
        this.bitrate = bitrate
        return this
    }

    fun setFps(fps: Int): Builder {
        this.fps = fps
        return this
    }

    fun setIFrameInterval(interval: Int): Builder {
        this.iFrameInterval = interval
        return this
    }

    fun setDensityDpi(densityDpi: Int): Builder {
        this.densityDpi = densityDpi
        return this
    }

    fun setOutputFile(file: File): Builder {
        this.outputFile = file
        return this
    }

    fun setListener(listener: RecorderListener): Builder {
        this.listener = listener
        return this
    }

    fun setPresentationFactory(factory: (Display, RecorderSession) -> Presentation): Builder {
        this.presentationFactory = factory
        return this
    }

    fun build(): RecorderSession {
        val file = outputFile ?: throw IllegalStateException("Output file not set")
        val factory = presentationFactory ?: throw IllegalStateException("Presentation factory not set")

        return OffscreenRecorder.createSession(
            context = context,
            width = width,
            height = height,
            bitrate = bitrate,
            fps = fps,
            iFrameInterval = iFrameInterval,
            densityDpi = densityDpi,
            outputFile = file,
            presentationFactory = factory,
            listener = listener
        )
    }
}
