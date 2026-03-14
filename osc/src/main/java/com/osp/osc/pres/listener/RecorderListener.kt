package com.osp.osc.pres.listener

import com.osp.osc.pres.error.RecorderError
import java.io.File

interface RecorderListener {

    fun onPrepared()

    fun onRecordingStart()

    fun onRecordingStop()

    fun onVideoSaved(file: File)

    fun onError(error: RecorderError)
}
