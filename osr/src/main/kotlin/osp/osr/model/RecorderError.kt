package osp.osr.model

/**
 * ⚠️ 录制过程各类错误的密封类
 *
 * 使用方可在 onError 里 when (error) 分支处理，或统一上报。
 */
sealed class RecorderError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /** 编码器（MediaCodec）相关 */
    class EncoderError(message: String, cause: Throwable? = null) :
        RecorderError(message, cause)

    /** 混流（MediaMuxer）相关 */
    class MuxerError(message: String, cause: Throwable? = null) :
        RecorderError(message, cause)

    /** 虚拟 Display 创建/绑定失败 */
    class DisplayError(message: String, cause: Throwable? = null) :
        RecorderError(message, cause)

    /** Presentation 创建或 show 失败 */
    class PresentationError(message: String, cause: Throwable? = null) :
        RecorderError(message, cause)

    /** 音频文件或混音过程出错 */
    class AudioError(message: String, cause: Throwable? = null) :
        RecorderError(message, cause)
}
