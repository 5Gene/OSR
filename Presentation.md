# Android 离屏录制库方案文档

（Offscreen Recorder Library Design）

---

# 1 项目目标

开发一个 **Android 开源库**，用于：

**离屏渲染 UI 并录制为视频文件。**

核心特点：

* 不使用 MediaProjection
* 不申请系统敏感权限
* 不需要用户授权
* 使用 MediaCodec 的 Surface 作为渲染目标
* 通过 Presentation 在虚拟 Display 上渲染 UI
* 支持 Kotlin DSL + Builder 方式使用
* 完整协程并发架构
* 可扩展
* 高性能
* 严格遵循编码最佳实践

核心应用场景：

```text
地图自动生成视频
UI 自动生成视频
视频素材生成
UI 测试录制
自动化演示视频
AI 视频生成
```

---

# 2 技术原理

Android 允许使用 **MediaCodec 创建一个输入 Surface**：

```text
MediaCodec.createInputSurface()
```

这个 Surface 可以被当作 **渲染目标**。

然后创建一个 **VirtualDisplay**：

```text
DisplayManager.createVirtualDisplay()
```

将 Surface 绑定到该 Display。

之后使用：

```text
Presentation(display)
```

在该 Display 上渲染 UI。

最终数据流：

```text
Presentation UI
      │
      ▼
VirtualDisplay
      │
      ▼
Surface
      │
      ▼
MediaCodec Encoder
      │
      ▼
MediaMuxer
      │
      ▼
MP4 文件
```

整个流程：

```text
UI → GPU → Surface → Encoder → MP4
```

整个过程：

**完全不需要 MediaProjection。**

---

# 3 核心设计原则

### 3.1 不使用 MediaProjection

原因：

```text
需要用户授权
属于敏感权限
体验复杂
```

本库设计：

```text
只使用 MediaCodec + VirtualDisplay
```

---

### 3.2 Display 必须来自 MediaCodec

流程：

```text
MediaCodec
   │
createInputSurface
   │
   ▼
VirtualDisplay
   │
   ▼
Presentation
```

保证：

```text
GPU 渲染直接进入编码器
```

避免：

```text
Bitmap 拷贝
CPU 渲染
```

---

### 3.3 UI 完全由 Presentation 控制

Presentation 决定：

```text
什么时候开始录制
什么时候结束录制
渲染什么内容
```

---

### 3.4 协程并发模型

库内部：

```text
100% Kotlin Coroutine
```

不使用：

```text
Thread
HandlerThread
Executor
```

---

# 4 系统架构

系统整体结构：

```text
OffscreenRecorder
       │
       ▼
RecorderSession
       │
       ├── VirtualDisplayManager
       ├── EncoderController
       ├── MuxerController
       └── PresentationController
```

数据流：

```text
Presentation
      │
      ▼
Surface
      │
      ▼
MediaCodec
      │
      ▼
Channel
      │
      ▼
MediaMuxer
      │
      ▼
MP4
```

---

# 5 模块设计

---

# 5.1 OffscreenRecorder

对外主入口。

职责：

```text
创建 RecorderSession
管理生命周期
Builder + DSL 配置
```

示例：

```kotlin
OffscreenRecorder.record(context) {

    video {
        width = 1080
        height = 1920
        bitrate = 4_000_000
    }

    output {
        file = File("map.mp4")
    }

    presentation { display, session ->
        MapPresentation(display, session)
    }

}
```

---

# 5.2 RecorderSession

核心控制类。

职责：

```text
管理录制状态
控制 encoder
控制 muxer
控制 display
提供 startRecord / stopRecord
```

状态机：

```text
Idle
Prepared
Recording
Stopping
Released
```

---

# 5.3 VirtualDisplayManager

职责：

```text
创建 VirtualDisplay
绑定 Surface
提供 Display
```

流程：

```text
MediaCodec.createInputSurface
        │
        ▼
VirtualDisplay
        │
        ▼
Display
```

---

# 5.4 EncoderController

职责：

```text
配置 MediaCodec
创建输入 Surface
编码视频
```

配置：

```text
video/avc
width
height
bitrate
fps
I-frame interval
```

---

# 5.5 MuxerController

职责：

```text
写入 MP4 文件
合并视频轨道
合并音频轨道
```

组件：

```text
MediaMuxer
```

---

# 5.6 PresentationController

职责：

```text
创建 Presentation
绑定 Display
管理生命周期
```

调用：

```kotlin
presentationFactory(display, session)
```

---

# 6 Kotlin DSL 使用方式

---

# 6.1 基本使用

```kotlin
OffscreenRecorder.record(context) {

    video {
        width = 1080
        height = 1920
        fps = 30
        bitrate = 4_000_000
    }

    output {
        file = File("demo.mp4")
    }

    presentation { display, session ->
        DemoPresentation(display, session)
    }

}
```

---

# 6.2 在 Presentation 中控制录制

```kotlin
class DemoPresentation(
    display: Display,
    private val session: RecorderSession
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.demo)

        lifecycleScope.launch {

            delay(1000)

            session.startRecord()

            delay(5000)

            session.stopRecord()

        }

    }

}
```

---

# 6.3 Builder 方式

```kotlin
OffscreenRecorder.Builder(context)
    .setVideoSize(1080, 1920)
    .setBitrate(4_000_000)
    .setOutputFile(file)
    .setPresentationFactory { display, session ->
        DemoPresentation(display, session)
    }
    .build()
    .start()
```

---

# 7 监听器设计

提供外部监听：

```kotlin
RecorderListener
```

接口：

```kotlin
interface RecorderListener {

    fun onPrepared()

    fun onRecordingStart()

    fun onRecordingStop()

    fun onVideoSaved(file: File)

    fun onError(error: RecorderError)

}
```

---

# 8 协程并发架构

---

# 8.1 RecorderScope

每个 Session 维护：

```kotlin
CoroutineScope(
    SupervisorJob() +
            Dispatchers.Default
)
```

---

# 8.2 协程模块

```text
Control Coroutine
Encoder Coroutine
Muxer Coroutine
```

结构：

```text
RecorderScope
   │
   ├── EncoderLoop
   ├── MuxerWriter
   └── ControlFlow
```

---

# 8.3 EncoderLoop

负责：

```text
读取 MediaCodec 输出帧
```

实现：

```kotlin
recorderScope.launch(Dispatchers.Default) {

    while (recording) {

        val index = codec.dequeueOutputBuffer(info, timeout)

        if (index >= 0) {

            val buffer = codec.getOutputBuffer(index)

            muxerChannel.send(
                EncodedFrame(buffer, info)
            )

            codec.releaseOutputBuffer(index, false)

        }

    }

}
```

---

# 8.4 Channel 数据传输

定义：

```kotlin
Channel<EncodedFrame>
```

用途：

```text
Encoder → Muxer
```

优势：

```text
协程安全
支持背压
非阻塞
```

---

# 8.5 MuxerWriter

```kotlin
recorderScope.launch(Dispatchers.IO) {

    for (frame in muxerChannel) {

        muxer.writeSampleData(
            trackIndex,
            frame.buffer,
            frame.info
        )

    }

}
```

---

# 9 数据结构

EncodedFrame：

```kotlin
data class EncodedFrame(
    val buffer: ByteBuffer,
    val info: MediaCodec.BufferInfo
)
```

---

# 10 录制流程

完整流程：

```text
创建 MediaCodec
      │
创建 InputSurface
      │
创建 VirtualDisplay
      │
创建 Presentation
      │
Presentation 渲染 UI
      │
UI 输出到 Surface
      │
Surface 进入 Encoder
      │
Encoder 输出帧
      │
Muxer 写入 MP4
```

---

# 11 音频支持（可选）

支持：

```text
混入音频
```

来源：

```text
AAC 文件
PCM 数据
麦克风
```

流程：

```text
AudioEncoder
     │
     ▼
MediaMuxer
```

---

# 12 默认配置

默认值：

```text
width = 1080
height = 1920
fps = 30
bitrate = 4Mbps
IFrameInterval = 1
```

---

# 13 错误处理

错误类型：

```text
EncoderError
MuxerError
DisplayError
PresentationError
```

---

# 14 生命周期管理

关闭时：

```kotlin
recorderScope.cancel()
```

自动释放：

```text
MediaCodec
Muxer
Display
Presentation
```

---

# 15 性能优化

避免：

```text
Bitmap 渲染
CPU copy
```

采用：

```text
GPU → Surface → Encoder
```

优势：

```text
零拷贝
高性能
低延迟
```

---

# 16 扩展能力

可扩展：

```text
OpenGL 渲染
Compose UI
地图渲染
动画生成
AI 视频生成
```

---

# 17 开源库目录结构

建议结构：

```text
offscreen-recorder
 ├── core
 │    ├── recorder
 │    ├── encoder
 │    ├── muxer
 │    ├── display
 │    └── presentation
 │
 ├── dsl
 │
 ├── audio
 │
 ├── util
 │
 └── sample
```

---

# 18 最终架构

完整架构：

```text
App
 │
 ▼
OffscreenRecorder
 │
 ▼
RecorderSession
 │
 ├── VirtualDisplayManager
 ├── EncoderController
 ├── MuxerController
 └── PresentationController
 │
 ▼
Presentation
 │
 ▼
Surface
 │
 ▼
MediaCodec
 │
 ▼
Channel
 │
 ▼
Muxer
 │
 ▼
MP4
```

---

# 19 最终特性

该开源库具备：

```text
无需 MediaProjection
无需权限
离屏渲染
UI 自动录制
Kotlin DSL
协程架构
高性能编码
模块化设计
```
