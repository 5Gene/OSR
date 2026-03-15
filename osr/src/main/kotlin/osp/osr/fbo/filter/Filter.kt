package osp.osr.fbo.filter

import androidx.annotation.WorkerThread

/**
 * 🎨 GL 滤镜接口（小白看这里）
 *
 * **干啥的**：输入一张纹理 ID，输出一张纹理 ID；中间可以在自己的 FBO 上画（模糊、水波纹、圆角等）。
 * 所有方法都在 **GL 线程** 调用；构造函数只在 Main 线程保存配置，真正创建 FBO/Program 在 init()。
 *
 * **数据流**：
 * - 谁调 init：FilterPipeline.init(width, height)，在 FrameCaptureRenderer.initGL() 里被调
 * - 谁调 apply：FilterPipeline.render(inputTexture)，每一帧里按顺序对每个 Filter 调 apply，上一格的输出是下一格的输入
 * - 谁调 release：FilterPipeline.release()，在 FrameCaptureRenderer.release() 里被调
 */
interface Filter {
    @WorkerThread
    fun init(width: Int, height: Int)

    @WorkerThread
    fun apply(inputTexture: Int): Int

    @WorkerThread
    fun release()
}

/**
 * 🔗 滤镜管线：按声明顺序链式执行。
 *
 * 每一帧：inputTexture → Filter1.apply → tex1 → Filter2.apply → tex2 → … → 最终纹理返回给 FrameCaptureRenderer，
 * FrameCaptureRenderer 再把这个纹理画到编码器 Surface。
 *
 * 谁用：FrameCaptureRenderer 持有一个 FilterPipeline，initGL 时 init，captureFrame 时 render(fboTexId)。
 */
class FilterPipeline {
    private val filters = mutableListOf<Filter>()
    private var initialized = false

    fun isEmpty(): Boolean = filters.isEmpty()

    fun setFilters(list: List<Filter>) {
        filters.clear()
        filters.addAll(list)
    }

    fun init(width: Int, height: Int) {
        filters.forEach { it.init(width, height) }
        initialized = true
    }

    fun render(inputTexture: Int): Int {
        if (!initialized || filters.isEmpty()) return inputTexture
        var current = inputTexture
        filters.forEach { current = it.apply(current) }
        return current
    }

    fun release() {
        filters.forEach { runCatching { it.release() } }
        filters.clear()
        initialized = false
    }
}
