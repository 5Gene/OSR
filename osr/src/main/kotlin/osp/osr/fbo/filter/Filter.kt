package osp.osr.fbo.filter

import androidx.annotation.WorkerThread
import osp.osr.fbo.gl.FboPool
import osp.osr.log.OsrLog

/**
 * 🎨 GL 滤镜接口（小白看这里）
 *
 * **干啥的**：输入一张纹理 ID，输出一张纹理 ID；中间可以在自己的 FBO 上画（模糊、水波纹、圆角等）。
 * 所有方法都在 **GL 线程** 调用；构造函数只在 Main 线程保存配置，真正创建 FBO/Program 在 init()。
 *
 * **数据流**：
 * - 谁调 init：FilterPipeline.init(width, height)，在 FrameCaptureRenderer.initGL() 里被调；FBO 从 FboPool 申请，release 时归还。
 * - 谁调 apply：FilterPipeline.render(inputTexture)，每一帧里按顺序对每个 Filter 调 apply，上一格的输出是下一格的输入
 * - 谁调 release：FilterPipeline.release()，在 FrameCaptureRenderer.release() 里被调；FBO 归还 FboPool，不直接 glDelete。
 */
interface Filter {
    @WorkerThread
    fun init(width: Int, height: Int, pool: FboPool)

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
 * FBO 由 [FboPool] 统一管理，多滤镜时同尺寸 FBO 复用，减少显存与创建开销。
 */
class FilterPipeline {
    private val filters = mutableListOf<Filter>()
    private val fboPool = FboPool()
    private var initialized = false

    fun isEmpty(): Boolean = filters.isEmpty()

    fun setFilters(list: List<Filter>) {
        filters.clear()
        filters.addAll(list)
    }

    fun init(width: Int, height: Int) {
        OsrLog.i("FilterPipeline: init ${width}x${height}, filters=${filters.size}")
        filters.forEach { it.init(width, height, fboPool) }
        initialized = true
    }

    fun render(inputTexture: Int): Int {
        if (!initialized || filters.isEmpty()) return inputTexture
        var current = inputTexture
        filters.forEach { current = it.apply(current) }
        return current
    }

    fun release() {
        OsrLog.i("FilterPipeline: release start, filters=${filters.size}")
        filters.forEach { runCatching { it.release() }.onFailure { OsrLog.e("FilterPipeline: filter.release failed", it) } }
        filters.clear()
        fboPool.releaseAll()
        initialized = false
        OsrLog.i("FilterPipeline: released")
    }
}
