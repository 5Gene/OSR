package com.osp.osc.pres

import android.app.Presentation
import android.content.Context
import android.view.Display
import com.osp.osc.pres.dsl.Builder
import com.osp.osc.pres.dsl.RecorderConfig
import com.osp.osc.pres.listener.RecorderListener
import java.io.File

object OffscreenRecorder {

    fun record(context: Context, block: RecorderConfig.() -> Unit): RecorderSession {
        val config = RecorderConfig().apply(block)
        val file = config.outputFile ?: throw IllegalStateException("Output file not configured")
        val presentationFactory = config.getPresentationFactory()

        return createSession(
            context = context,
            width = config.width,
            height = config.height,
            bitrate = config.bitrate,
            fps = config.fps,
            iFrameInterval = config.iFrameInterval,
            densityDpi = config.densityDpi,
            outputFile = file,
            presentationFactory = presentationFactory,
            listener = config.listener
        )
    }

    fun builder(context: Context): Builder {
        return Builder(context)
    }

    internal fun createSession(
        context: Context,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        iFrameInterval: Int,
        densityDpi: Int,
        outputFile: File,
        presentationFactory: (Display, RecorderSession) -> Presentation,
        listener: RecorderListener?
    ): RecorderSession {
        return RecorderSession(
            context = context,
            width = width,
            height = height,
            bitrate = bitrate,
            fps = fps,
            iFrameInterval = iFrameInterval,
            densityDpi = densityDpi,
            outputFile = outputFile,
            presentationFactory = presentationFactory,
            listener = listener
        )
    }
}
