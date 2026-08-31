package osp.osr.dsl

import osp.osr.listener.ListenerConfig
import osp.osr.render.RenderStrategy
import java.io.File

/** 📐 视频编码参数（宽高、帧率、码率、关键帧间隔、MaxFS 策略、VD dpi） */
class VideoConfig {
    /** 0 = OSR.recorder(context) 填成屏幕宽 */
    var width: Int = 0
    /** 0 = 填成屏幕高 */
    var height: Int = 0
    var fps: Int = 30
    /**
     * 码率（bps）。0=未指定，configure 时用 `3*width*height` 按分辨率自动估；
     * >0 则用该值。一般不用设。
     */
    var bitrate: Int = 0
    var iFrameInterval: Int = 10
    /**
     * true：一开始就按 MaxFS=8192 纠正宽高再 configure
     * false：先只 16 对齐 configure；失败再 MaxFS 纠正写回并重配；仍失败才 720p
     */
    var strictAvcLevel41: Boolean = true
    /** 0 = 填成设备 densityDpi；作为 VD 密度基准 */
    var densityDpi: Int = 0
}

/**
 * 🎵 可选背景音：设置后保存时混入 MP4，音频短于视频则循环。
 *
 * **MP4 常见可混合的音频格式**（参考 ISO 基媒体格式）：
 * - AAC（.aac / .m4a）：推荐，兼容性最好，本库**仅支持此格式**
 * - MP3：部分 Muxer 支持，本库不支持
 * - AC-3 / E-AC3：部分支持
 * - 其他（Opus、Vorbis 等）：视容器与设备而定
 *
 * **限制**：当前仅支持 **AAC**（MIME 为 `audio/mp4a-latm` 或 `audio/aac`），
 * 非 AAC 文件在 prepare 时会抛出 [osp.osr.model.RecorderError.AudioError]。
 */
class AudioConfig {
    /** AAC 音频文件（.aac / .m4a），null 表示不混入背景音 */
    var file: File? = null
}

/** 📁 输出 MP4 路径，必填 */
class OutputConfig {
    var file: File? = null
}

/** 限制 DSL 块内只能调用本库提供的 DSL 方法，避免误用外层 lambda */
@DslMarker
annotation class RecorderDsl

/**
 * 📋 录制配置的“大本营”
 *
 * **设计模式：Builder（内部类）+ DSL**
 * - DSL：video { } / audio { } / output { } / listener { } / presentation { }（扩展函数注入）
 * - Builder：链式 setX().build()，适合非 DSL 场景或从 Java 调用
 *
 * [renderStrategy] 由各实现包通过扩展函数设置（如 RecorderConfig.presentation { }），公共层不依赖具体实现。
 */
@RecorderDsl
class RecorderConfig {
    val videoConfig = VideoConfig()
    val audioConfig = AudioConfig()
    val outputConfig = OutputConfig()
    val listenerConfig = ListenerConfig()
    /** 渲染策略，由 presentation { } / fbo { } 等扩展函数注入 */
    var renderStrategy: RenderStrategy? = null

    fun video(block: VideoConfig.() -> Unit) {
        videoConfig.apply(block)
    }

    fun audio(block: AudioConfig.() -> Unit) {
        audioConfig.apply(block)
    }

    fun output(block: OutputConfig.() -> Unit) {
        outputConfig.apply(block)
    }

    fun listener(block: ListenerConfig.() -> Unit) {
        listenerConfig.apply(block)
    }

    /**
     * **设计模式：Builder**
     * 链式调用，最后 build() 得到 RecorderConfig，再交给 OSR.recorder(context, config)。
     */
    class Builder {
        private val config = RecorderConfig()

        fun setVideoSize(width: Int, height: Int) = apply {
            config.videoConfig.width = width
            config.videoConfig.height = height
        }

        fun setFps(fps: Int) = apply {
            config.videoConfig.fps = fps
        }

        /** >0 覆盖自动码率；0 或未调用则 configure 用 3*w*h */
        fun setBitrate(bitrate: Int) = apply {
            config.videoConfig.bitrate = bitrate
        }

        fun setIFrameInterval(interval: Int) = apply {
            config.videoConfig.iFrameInterval = interval
        }

        fun setStrictAvcLevel41(strict: Boolean) = apply {
            config.videoConfig.strictAvcLevel41 = strict
        }

        fun setDensityDpi(dpi: Int) = apply {
            config.videoConfig.densityDpi = dpi
        }

        fun setAudioFile(file: File) = apply {
            config.audioConfig.file = file
        }

        fun setOutputFile(file: File) = apply {
            config.outputConfig.file = file
        }

        fun setRenderStrategy(strategy: RenderStrategy) = apply {
            config.renderStrategy = strategy
        }

        fun setListener(block: ListenerConfig.() -> Unit) = apply {
            config.listenerConfig.apply(block)
        }

        fun build(): RecorderConfig = config
    }
}
