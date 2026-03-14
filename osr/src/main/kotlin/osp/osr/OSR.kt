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
        validate(config)
        OsrLog.i("config validated, creating session")
        val session = config.renderStrategy!!.createSession(context, config)
        OsrLog.i("session created")
        return session
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
        require(config.videoConfig.bitrate > 0) {
            "码率必须大于 0"
        }
        require(config.videoConfig.fps > 0) {
            "帧率必须大于 0"
        }
        OsrLog.d("validate passed ${config.videoConfig.width}x${config.videoConfig.height} ${config.videoConfig.fps}fps")
    }
}
