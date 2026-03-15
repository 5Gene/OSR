package osp.osr.fbo

import android.opengl.GLSurfaceView
import android.view.View
import osp.osr.dsl.RecorderDsl
import osp.osr.fbo.filter.Filter

// ─── 🎬 帧源配置（sealed：四种模式四选一） ───

/**
 * 四种录制模式对应四种数据来源，DSL 里只能选一种。
 * 谁用：FboRecorderSession.prepare() 里 when(sourceConfig) 穷举，创建对应的 FrameSource。
 */
sealed interface FrameSourceConfig {
    /** 方式 1：你把「我们的 Renderer」通过 attach 挂到地图/自定义 GL 环境，每帧 onDrawFrame 时我们录一帧 */
    data class CaptureRenderer(val attach: (GLSurfaceView.Renderer) -> Unit) : FrameSourceConfig

    /** 方式 2：你提供 GLSurfaceView + 自己的 Renderer，我们包一层装饰器，边显示边录 */
    data class GlSurfaceView(
        val surface: GLSurfaceView,
        val renderer: GLSurfaceView.Renderer
    ) : FrameSourceConfig

    /** 方式 3：纯后台，只传 Renderer，我们在离屏 EGL 里循环调 onDrawFrame 并录 */
    data class Offscreen(val renderer: GLSurfaceView.Renderer) : FrameSourceConfig

    /** 方式 4：任意 View，我们主线程 draw 到 Bitmap，GL 线程上传纹理再录 */
    data class ViewCapture(val provider: () -> View) : FrameSourceConfig
}

// ─── 📋 FBO DSL 入口 ───

/**
 * fbo { } 块里配置「用哪种帧源」+「用哪些滤镜」。
 * 谁用：RecorderConfig.fbo(block) 里 new FboConfig().apply(block)，然后交给 FboStrategy。
 */
@RecorderDsl
class FboConfig {
    internal var sourceConfig: FrameSourceConfig? = null
    internal var filterConfig: FilterConfig? = null

    private fun requireSourceNotSet() {
        require(sourceConfig == null) {
            "只能选择一种录制模式（captureRenderer / glSurfaceView / renderer / view）"
        }
    }

    /** 方式 1：高德/腾讯 setCustomRenderer 场景，你把我们的 Renderer 传进去 */
    fun captureRenderer(attach: (GLSurfaceView.Renderer) -> Unit) {
        requireSourceNotSet()
        sourceConfig = FrameSourceConfig.CaptureRenderer(attach)
    }

    /** 方式 2：边显示边录，surface 上绑我们包装过的 renderer */
    fun glSurfaceView(surface: GLSurfaceView, renderer: GLSurfaceView.Renderer) {
        requireSourceNotSet()
        sourceConfig = FrameSourceConfig.GlSurfaceView(surface, renderer)
    }

    /** 方式 3：纯后台，不显示，只录 */
    fun renderer(renderer: GLSurfaceView.Renderer) {
        requireSourceNotSet()
        sourceConfig = FrameSourceConfig.Offscreen(renderer)
    }

    /** 方式 4：任意 View 截图录 */
    fun view(provider: () -> View) {
        requireSourceNotSet()
        sourceConfig = FrameSourceConfig.ViewCapture(provider)
    }

    /** 可选：加模糊/水波纹/圆角/自定义滤镜，按声明顺序链式执行 */
    fun filters(block: FilterConfig.() -> Unit) {
        filterConfig = FilterConfig().apply(block)
    }
}

// ─── 🎨 滤镜 DSL ───

@RecorderDsl
class FilterConfig {
    internal val filters = mutableListOf<Filter>()

    fun blur(block: BlurConfig.() -> Unit = {}) {
        filters.add(osp.osr.fbo.filter.BlurFilter(BlurConfig().apply(block)))
    }

    fun waterRipple(block: WaterRippleConfig.() -> Unit = {}) {
        filters.add(osp.osr.fbo.filter.WaterRippleFilter(WaterRippleConfig().apply(block)))
    }

    fun roundCorner(block: RoundCornerConfig.() -> Unit = {}) {
        filters.add(osp.osr.fbo.filter.RoundCornerFilter(RoundCornerConfig().apply(block)))
    }

    fun custom(vararg filter: Filter) {
        filters.addAll(filter)
    }
}

// ─── 滤镜参数（DSL 里可调） ───

class BlurConfig {
    var radius: Float = 5f
}

class WaterRippleConfig {
    var amplitude: Float = 0.02f
    var frequency: Float = 20f
    var speed: Float = 1.0f
}

class RoundCornerConfig {
    var radius: Float = 16f
}
