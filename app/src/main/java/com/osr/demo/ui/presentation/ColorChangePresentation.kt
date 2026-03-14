package com.osr.demo.ui.presentation

import android.app.Presentation
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import osp.osr.RecorderSession

/**
 * 用于录制的 Presentation：循环 + delay 持续刷新背景色（约 30fps），
 * 录制约 5 秒后自动调用 session.stopRecord()。
 */
class ColorChangePresentation(
    private val activity: AppCompatActivity,
    display: Display,
    private val session: RecorderSession
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = FrameLayout(activity)
        setContentView(content)
        session.startRecord()
        val colors = intArrayOf(
            Color.RED,
            Color.BLUE,
            Color.GREEN,
            Color.YELLOW,
            Color.MAGENTA
        )
        val frameIntervalMs = 1000L / 30  // 约 33ms 一帧，30fps
        val totalFrames = 30 * 5          // 5 秒

        activity.lifecycleScope.launch {
            for (i in 0 until totalFrames) {
                content.setBackgroundColor(colors[(i / 30) % colors.size])
                content.invalidate()
                delay(frameIntervalMs)
            }
            session.stopRecord()
        }
    }
}
