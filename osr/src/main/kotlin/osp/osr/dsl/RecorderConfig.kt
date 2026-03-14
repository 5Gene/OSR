package osp.osr.dsl

import osp.osr.listener.ListenerConfig
import osp.osr.render.RenderStrategy
import java.io.File

/** 📐 视频编码参数（宽高、帧率、码率、关键帧间隔） */
class VideoConfig {
    var width: Int = 1080
    var height: Int = 1920
    var fps: Int = 30
    var bitrate: Int = 4_000_000
    var iFrameInterval: Int = 1
}

/** 🎵 可选背景音：设置后保存时混入，音频短于视频则循环 */
class AudioConfig {
    // 必须是acc格式音频文件
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

        fun setBitrate(bitrate: Int) = apply {
            config.videoConfig.bitrate = bitrate
        }

        fun setIFrameInterval(interval: Int) = apply {
            config.videoConfig.iFrameInterval = interval
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
