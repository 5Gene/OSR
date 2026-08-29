package osp.osr.fbo.source

/**
 * 🎬 帧源接口（小白看这里）
 *
 * **干啥的**：代表「谁在产帧」。四种录制模式对应四种实现——
 * CaptureRendererSource / GLSurfaceViewSource / OffscreenSource / ViewSource。
 * 它们负责在合适的时机提交当前 framebuffer 或已上传纹理，
 * 真正做「拷 FBO → 滤镜 → 画到编码器」的是 FrameCaptureRenderer。
 *
 * **数据流**：start() 后开始产帧 → 每帧结束时提交 framebuffer/纹理 → FrameCaptureRenderer；
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

    /**
     * ViewCapture 已经产出 2D 纹理，直接送入滤镜与编码器，避免从空 PBuffer 重复 blit。
     */
    fun captureTexture(textureId: Int)
}

/**
 * 📐 宿主 Surface 尺寸通知（可选）
 *
 * 方式 1/2 下，宿主在 onSurfaceChanged 中可通知真实 Surface 宽高，
 * 供 captureFrame 用作 glBlitFramebuffer 的源矩形，避免依赖 GL_VIEWPORT 在 onDrawFrame 中被局部修改导致的裁剪/偏移。
 * 实现类：FrameCaptureRenderer。
 */
interface SourceSizeSink {
    fun setSourceSize(width: Int, height: Int)
}
