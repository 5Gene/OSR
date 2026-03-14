package com.osp.osc.pres.core

import android.app.Presentation
import android.content.Context
import android.view.Display
import com.osp.osc.pres.RecorderSession
import com.osp.osc.pres.error.RecorderError

class PresentationController(
    private val context: Context,
    private val display: Display,
    private val presentationFactory: (Display, RecorderSession) -> Presentation
) {

    private var presentation: Presentation? = null
    private var errorCallback: ((RecorderError) -> Unit)? = null

    fun setErrorCallback(callback: (RecorderError) -> Unit) {
        errorCallback = callback
    }

    fun createPresentation(session: RecorderSession): Presentation {
        return try {
            presentation = presentationFactory.invoke(display, session)
            presentation!!
        } catch (e: Exception) {
            val error = RecorderError.PresentationError("Failed to create presentation", e)
            errorCallback?.invoke(error)
            throw error
        }
    }

    fun show() {
        try {
            presentation?.show()
        } catch (e: Exception) {
            val error = RecorderError.PresentationError("Failed to show presentation", e)
            errorCallback?.invoke(error)
        }
    }

    fun hide() {
        try {
            presentation?.hide()
        } catch (e: Exception) {
            val error = RecorderError.PresentationError("Failed to hide presentation", e)
            errorCallback?.invoke(error)
        }
    }

    fun release() {
        try {
            presentation?.dismiss()
            presentation = null
        } catch (e: Exception) {
            val error = RecorderError.PresentationError("Error releasing presentation", e)
            errorCallback?.invoke(error)
        }
    }
}
