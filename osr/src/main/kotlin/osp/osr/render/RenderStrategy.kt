package osp.osr.render

import android.content.Context
import osp.osr.RecorderSession
import osp.osr.dsl.RecorderConfig

/**
 * 🎨 渲染策略工厂接口
 *
 * **设计模式：策略模式（Strategy） + 简单工厂**
 * 每种渲染方式（Presentation / FBO）实现本接口，负责“根据配置创建对应的 RecorderSession”。
 * 公共层（OSR、RecorderConfig）只依赖本接口，不依赖任何具体实现，实现类由各子包通过扩展函数注入。
 *
 * 优点：零耦合、易扩展（新增 fbo 包只需实现接口 + 扩展函数）；createSession 为 suspend，便于在主线程安全地 show Presentation。
 */
interface RenderStrategy {

    /**
     * 根据 [config] 创建并准备好一个 [RecorderSession]。
     * 实现方负责 prepare（如创建 VirtualDisplay、Presentation 等），调用方直接拿到可用的 session。
     */
    suspend fun createSession(context: Context, config: RecorderConfig): RecorderSession
}
