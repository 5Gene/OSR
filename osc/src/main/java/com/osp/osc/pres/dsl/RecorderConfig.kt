package com.osp.osc.pres.dsl

import android.app.Presentation
import android.view.Display
import com.osp.osc.pres.RecorderSession
import com.osp.osc.pres.listener.RecorderListener
import java.io.File

class RecorderConfig {
    var width: Int = DEFAULT_WIDTH
    var height: Int = DEFAULT_HEIGHT
    var bitrate: Int = DEFAULT_BITRATE
    var fps: Int = DEFAULT_FPS
    var iFrameInterval: Int = DEFAULT_I_FRAME_INTERVAL
    var densityDpi: Int = DEFAULT_DENSITY_DPI
    var outputFile: File? = null
    var listener: RecorderListener? = null
    private var presentationFactoryBlock: ((Display, RecorderSession) -> Presentation)? = null

    fun presentation(block: (Display, RecorderSession) -> Presentation) {
        presentationFactoryBlock = block
    }

    fun getPresentationFactory(): (Display, RecorderSession) -> Presentation {
        return presentationFactoryBlock ?: throw IllegalStateException("Presentation factory not configured")
    }

    companion object {
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_BITRATE = 4_000_000
        const val DEFAULT_FPS = 30
        const val DEFAULT_I_FRAME_INTERVAL = 1
        const val DEFAULT_DENSITY_DPI = 320
    }
}

class VideoConfig {
    var width: Int = RecorderConfig.DEFAULT_WIDTH
    var height: Int = RecorderConfig.DEFAULT_HEIGHT
    var bitrate: Int = RecorderConfig.DEFAULT_BITRATE
    var fps: Int = RecorderConfig.DEFAULT_FPS
    var iFrameInterval: Int = RecorderConfig.DEFAULT_I_FRAME_INTERVAL
}

class OutputConfig {
    var file: File? = null
}
