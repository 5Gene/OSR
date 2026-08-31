package com.osr.demo.ui.components

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 按固定 fps 循环切换背景色的自定义 View，用于录制等场景。
 *
 * 推荐：`session.startRecord(onReady = { start() })`，第一帧后再开动画；
 * [onEnd] 里调 `stopRecord`。勿在 [start] 之前先播动画再申请录制。
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
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 开始循环切换背景色；结束时自动调用 [onEnd]。应放在 startRecord(onReady) 里调用。 */
    fun start() {
        stop()
        currentFrame = 0
        totalFrames = fps * durationSeconds
        onStart?.invoke()
        scheduleNext()
    }

    /** 提前结束循环，不会调用 [onEnd]。 */
    fun stop() {
        scheduleRunnable?.let(mainHandler::removeCallbacks)
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
        // 离屏 View 未 attach，使用主线程 Handler 才能持续推进动画。
        mainHandler.postDelayed(scheduleRunnable!!, 1000L / fps)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
