package com.osr.demo.ui.components

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 按固定 fps 循环切换背景色的自定义 View，用于录制等场景。
 *
 * 使用方式：
 * - 设置 [onStart] / [onEnd] 回调（如录制的 startRecord/stopRecord）
 * - 调用 [start] 开始循环，结束时自动调用 [onEnd]
 * - 可调用 [stop] 提前结束
 */
class ColorChangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 循环开始时回调（如开始录制） */
    var onStart: (() -> Unit)? = null

    /** 循环结束时回调（如停止录制） */
    var onEnd: (() -> Unit)? = null

    /** 帧率，默认 30 */
    var fps: Int = 30
        set(value) {
            field = value.coerceIn(1, 60)
        }

    /** 持续秒数，默认 5 */
    var durationSeconds: Int = 5
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private val colors = intArrayOf(
        Color.RED,
        Color.BLUE,
        Color.GREEN,
        Color.YELLOW,
        Color.MAGENTA
    )

    private var currentFrame = 0
    private var totalFrames = 0
    private var scheduleRunnable: Runnable? = null

    /** 开始循环切换背景色；结束时自动调用 [onEnd]。 */
    fun start() {
        stop()
        currentFrame = 0
        totalFrames = fps * durationSeconds
        onStart?.invoke()
        scheduleNext()
    }

    /** 提前结束循环，不会调用 [onEnd]。 */
    fun stop() {
        scheduleRunnable?.let { removeCallbacks(it) }
        scheduleRunnable = null
    }

    private fun scheduleNext() {
        if (currentFrame >= totalFrames) {
            scheduleRunnable = null
            onEnd?.invoke()
            return
        }
        setBackgroundColor(colors[(currentFrame / fps) % colors.size])
        invalidate()
        currentFrame++
        scheduleRunnable = Runnable { scheduleNext() }
        postDelayed(scheduleRunnable!!, 1000L / fps)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
