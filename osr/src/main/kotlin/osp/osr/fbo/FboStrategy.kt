package osp.osr.fbo

import android.content.Context
import osp.osr.RecorderSession
import osp.osr.dsl.RecorderConfig
import osp.osr.log.OsrLog
import osp.osr.render.RenderStrategy

/**
 * 🎬 把 RecorderConfig.fbo { } 变成真正的 FBO 录制会话。
 *
 * 谁调我：OSR.recorder(context, config) 里会取 config.renderStrategy.createSession；
 * 当用户写了 config.fbo { } 时，renderStrategy 就是我们。
 *
 * 数据流：createSession →  new FboRecorderSession(context, config, fboConfig) → session.prepare() → 返回 session，
 * 调用方拿到 RecorderSession 后就可以 startRecord() / stopRecord() 了。
 */
fun RecorderConfig.fbo(block: FboConfig.() -> Unit) {
    val fboConfig = FboConfig().apply(block)
    renderStrategy = FboStrategy(fboConfig)
}

class FboStrategy(private val fboConfig: FboConfig) : RenderStrategy {
    override suspend fun createSession(context: Context, config: RecorderConfig): RecorderSession {
        OsrLog.i("FboStrategy: createSession start")
        val session = FboRecorderSession(context, config, fboConfig)
        session.prepare()
        OsrLog.i("FboStrategy: createSession done")
        return session
    }
}
