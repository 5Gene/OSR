package osp.osr.pres

import android.app.Presentation
import android.content.Context
import android.view.Display
import osp.osr.RecorderSession
import osp.osr.dsl.RecorderConfig
import osp.osr.log.OsrLog
import osp.osr.render.RenderStrategy

/** 用户提供的工厂：(Display, RecorderSession) -> Presentation，用于在虚拟 Display 上展示自定义 UI */
typealias PresentationFactory = (Display, RecorderSession) -> Presentation

/**
 * 🖥️ Presentation 渲染策略
 *
 * **设计模式：策略模式（Strategy）的具体实现**
 * 实现 [RenderStrategy]，根据配置创建 [PresentationRecorderSession] 并完成 prepare
 * （Encoder → Surface → VirtualDisplay → Presentation），然后返回给调用方。
 *
 * 扩展函数 [RecorderConfig.presentation] 负责把本策略注入到 config.renderStrategy，
 * 这样公共层不需要依赖 pres 包，零耦合。
 */
class PresentationStrategy(
    private val factory: PresentationFactory
) : RenderStrategy {

    override suspend fun createSession(context: Context, config: RecorderConfig): RecorderSession {
        OsrLog.i("create PresentationRecorderSession")
        val session = PresentationRecorderSession(context, config, factory)
        session.prepare()
        OsrLog.i("session prepared, return:$session")
        return session
    }
}

/**
 * 📌 DSL 扩展：在 recorder { } 里写 presentation { display, session -> YourPresentation(display, session) }
 * 即把渲染策略设为 PresentationStrategy，无需显式 new Strategy。
 */
fun RecorderConfig.presentation(factory: PresentationFactory) {
    renderStrategy = PresentationStrategy(factory)
}
