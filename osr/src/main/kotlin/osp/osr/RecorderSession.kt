package osp.osr

import osp.osr.model.RecorderState

/**
 * 🎬 录制会话的统一抽象
 *
 * **设计模式：策略模式（Strategy）的“结果”抽象**
 * 不同渲染方式（Presentation / FBO）各自实现本接口，对外只暴露这 4 个方法，
 * 调用方无需关心底层是 VirtualDisplay 还是 OpenGL FBO。
 *
 * 优点：扩展新渲染方案时只需新增实现类，不破坏现有 API；类型安全、易测试。
 */
interface RecorderSession {

    /** 🟢 开始录制（由用户在 Presentation 等 UI 中按需调用） */
    fun startRecord()

    /** 🔴 停止录制并完成写入（含音频混合） */
    fun stopRecord()

    /** 🧹 释放所有资源，会话不可再使用 */
    fun release()

    /** 📊 当前状态，便于 UI 或逻辑分支 */
    fun getState(): RecorderState
}
