package com.osr.demo.ui.presentation

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import com.osr.demo.ui.components.ColorChangeView
import osp.osr.RecorderSession
import androidx.appcompat.app.AppCompatActivity

/**
 * 用于录制的 Presentation：使用 [ColorChangeView] 循环刷新背景色（约 30fps、5 秒），
 * 通过开始/结束回调驱动 [RecorderSession] 的 startRecord/stopRecord。
 */
class ColorChangePresentation(
    private val activity: AppCompatActivity,
    display: Display,
    private val session: RecorderSession
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = ColorChangeView(context).apply {
            fps = 30
            durationSeconds = 5
            onStart = { session.startRecord() }
            onEnd = { session.stopRecord() }
        }
        setContentView(content)
        content.start()
    }
}
