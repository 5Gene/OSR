package com.osp.osc.pres.core

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import android.view.Surface
import com.osp.osc.pres.error.RecorderError

class VirtualDisplayManager(
    private val displayManager: DisplayManager,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int
) {

    private var virtualDisplay: VirtualDisplay? = null
    private var display: Display? = null
    private var errorCallback: ((RecorderError) -> Unit)? = null

    fun setErrorCallback(callback: (RecorderError) -> Unit) {
        errorCallback = callback
    }

    fun createVirtualDisplay(surface: Surface): Display {
        return try {
            virtualDisplay = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
            display = virtualDisplay?.display
            if (display == null) {
                throw RecorderError.DisplayError("Failed to create virtual display")
            }
            display!!
        } catch (e: RecorderError) {
            errorCallback?.invoke(e)
            throw e
        } catch (e: Exception) {
            val error = RecorderError.DisplayError("Failed to create virtual display", e)
            errorCallback?.invoke(error)
            throw error
        }
    }

    fun getDisplay(): Display? = display

    fun release() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            display = null
        } catch (e: Exception) {
            val error = RecorderError.DisplayError("Error releasing virtual display", e)
            errorCallback?.invoke(error)
        }
    }

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "offscreen_recorder_display"
    }
}
