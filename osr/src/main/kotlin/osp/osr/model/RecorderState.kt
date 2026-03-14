package osp.osr.model

/**
 * 📊 录制会话状态
 *
 * 状态流转：IDLE → PREPARED → RECORDING → STOPPING → RELEASED
 * Session 内部用 AtomicReference 做 CAS 转换，保证多线程/协程下状态一致。
 */
enum class RecorderState {
    /** 初始态，尚未 prepare */
    IDLE,

    /** 已创建 Display / Presentation，可 startRecord */
    PREPARED,

    /** 正在编码并写入 Muxer */
    RECORDING,

    /** 已调 stopRecord，正在收尾（写音频、stop muxer） */
    STOPPING,

    /** 已释放，不可再用 */
    RELEASED
}
