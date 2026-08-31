package osp.osr

import android.content.Context
import osp.osr.dsl.RecorderConfig
import osp.osr.log.OsrLog

/**
 * 🚀 离屏录制库的唯一起入口
 *
 * 使用方式二选一：
 * - DSL：`OSR.recorder(context) { video { }; output { }; presentation { } }`
 * - Builder：`RecorderConfig.Builder().setX().build()` 后 `OSR.recorder(context, config)`
 *
 * 注意：recorder 为 suspend，需在协程作用域中调用（如 lifecycleScope.launch { }）。
 */
object OSR {

    /**
     * 📝 DSL 方式：在 lambda 里配置 video / output / listener / presentation（或 fbo），然后创建 Session。
     */
    suspend fun recorder(context: Context, block: RecorderConfig.() -> Unit): RecorderSession {
        OsrLog.i("recorder(DSL) entry")
        val config = RecorderConfig().apply(block)
        return recorder(context, config)
    }

    /**
     * 📦 直接传入已构建的 [RecorderConfig]，校验后交给 [RenderStrategy] 创建 Session。
     */
    suspend fun recorder(context: Context, config: RecorderConfig): RecorderSession {
        OsrLog.i("recorder(config) output=${config.outputConfig.file?.absolutePath}")
        applyVideoDefaults(context, config)
        validate(config)
        OsrLog.i("config validated, creating session")
        val session = config.renderStrategy!!.createSession(context, config)
        OsrLog.i("session created")
        return session
    }

    /** 宽高/dpi 为 0 时用屏幕像素与设备 dpi 填入 */
    private fun applyVideoDefaults(context: Context, config: RecorderConfig) {
        val dm = context.resources.displayMetrics
        val v = config.videoConfig
        if (v.width <= 0) v.width = dm.widthPixels
        if (v.height <= 0) v.height = dm.heightPixels
        if (v.densityDpi <= 0) v.densityDpi = dm.densityDpi
        OsrLog.i(
            "🎬 video defaults ${v.width}x${v.height} dpi=${v.densityDpi} strictAvc=${v.strictAvcLevel41}"
        )
    }

    /** 参数校验，避免脏配置进入策略层 */
    private fun validate(config: RecorderConfig) {
        OsrLog.d("validate: output file and render strategy")
        requireNotNull(config.outputConfig.file) {
            "必须通过 output { file = ... } 设置输出文件"
        }
        requireNotNull(config.renderStrategy) {
            "必须设置渲染策略（如 presentation { } 或 fbo { }）"
        }
        require(config.videoConfig.width > 0 && config.videoConfig.height > 0) {
            "视频宽高必须大于 0"
        }
        require(config.videoConfig.bitrate >= 0) {
            "码率不能为负数（0=按分辨率自动估）"
        }
        require(config.videoConfig.fps > 0) {
            "帧率必须大于 0"
        }
        require(config.videoConfig.densityDpi > 0) {
            "densityDpi 必须大于 0"
        }
        OsrLog.d(
            "validate passed ${config.videoConfig.width}x${config.videoConfig.height} " +
                "${config.videoConfig.fps}fps dpi=${config.videoConfig.densityDpi}"
        )
    }
}
