package com.osr.demo.ui.presentation

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import androidx.appcompat.app.AppCompatActivity
import com.osr.demo.ui.components.ColorChangeView
import osp.osr.RecorderSession

/**
 * 用于录制的 Presentation：静态挂上 ColorChangeView，
 * startRecord 后等第一帧再开变色循环；结束时 stopRecord。
 */
class ColorChangePresentation(
    activity: AppCompatActivity,
    display: Display,
    private val session: RecorderSession
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = ColorChangeView(context).apply {
            fps = 30
            durationSeconds = 5
            onEnd = { session.stopRecord() }
        }
        setContentView(content)
        // 先静态首屏；第一帧进文件后再 content.start() 开变色（勿在 onCreate 提前 start）
        session.startRecord(onReady = { content.start() })
    }
}
