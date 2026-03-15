package osp.osr.fbo.source

/**
 * 🎬 帧源接口（小白看这里）
 *
 * **干啥的**：代表「谁在产帧」。四种录制模式对应四种实现——
 * CaptureRendererSource / GLSurfaceViewSource / OffscreenSource / ViewSource。
 * 它们负责在合适的时机（每帧画完）调 [FrameCaptureCallback.captureFrame]，
 * 真正做「拷 FBO → 滤镜 → 画到编码器」的是 FrameCaptureRenderer。
 *
 * **数据流**：start() 后开始产帧 → 每帧结束时 captureCallback.captureFrame() → FrameCaptureRenderer.captureFrame()；
 * stop() 停止产帧；release() 释放资源。
 */
interface FrameSource {
    fun start()
    fun stop()
    fun release()
}

/**
 * 📸 帧捕获回调（依赖倒置用）
 *
 * FrameSource 只依赖这个接口，不直接依赖 FrameCaptureRenderer，方便测试和扩展。
 * 实现类：FrameCaptureRenderer，在 FboRecorderSession 里把 renderer 传给各个 FrameSource。
 */
interface FrameCaptureCallback {
    fun captureFrame()
}
