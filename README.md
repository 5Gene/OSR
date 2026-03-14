# OffScreenRecorder

Android 离屏录制库：在虚拟 Display 上渲染 UI 并录制为 MP4，无需 MediaProjection、无需敏感权限。

## 特性

- 不使用 MediaProjection，不申请系统敏感权限
- MediaCodec Surface 直连 VirtualDisplay，GPU 渲染零拷贝
- Kotlin DSL + Builder 两种使用方式
- 协程架构，支持可选音频混合（设置音频文件，短则循环）
- 策略模式：支持 Presentation / FBO 等多种渲染方式，公共配置与渲染实现解耦

## 集成

在应用模块的 `build.gradle.kts` 中：

```kotlin
dependencies {
    implementation(project(":osc"))
}
```

## 基本使用（DSL）

在 `OSR.recorder { }` 中配置视频、输出文件、可选音频和可选监听，然后选择渲染策略（如 `presentation { }`）。录制开始/结束在 Presentation 内调用
`session.startRecord()` / `session.stopRecord()`。

- **audio**：可选。设置后保存视频时自动混入该音频；音频短于视频时长时会从头循环填充。
- **listener**：可选。按需设置 `onStart` / `onStop` / `onSaved` / `onError`，不关心的可不写。
- **presentation**：选择 Presentation 渲染策略（来自 `osp.osr.pres` 包的扩展函数）。

`OSR.recorder()` 是 suspend 函数，需在协程作用域中调用：

```kotlin
import osp.osr.OSR
import osp.osr.pres.presentation  // 扩展函数

lifecycleScope.launch {
    val session = OSR.recorder(context) {

        video {
            width = 1080
            height = 1920
            fps = 30
            bitrate = 4_000_000
        }

        output {
            file = File(context.filesDir, "demo.mp4")
        }

        // 可选：混入背景音，短则循环
        audio {
            file = File(context.filesDir, "bgm.aac")
        }

        // 可选：按需监听
        listener {
            onStart = { }
            onStop = { }
            onSaved = { file -> }
            onError = { error -> }
        }

        // 渲染策略：Presentation
        presentation { display, session ->
            DemoPresentation(context, display, session)
        }
    }
}
```

## 在 Presentation 中控制录制

由 Presentation 决定何时开始、何时结束录制：

```kotlin
class DemoPresentation(
    context: Context,
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

## Builder 方式

使用 `RecorderConfig.Builder` 构建配置，再通过 `OSR.recorder(context, config)` 创建 Session：

```kotlin
import osp.osr.pres.PresentationStrategy

val config = RecorderConfig.Builder()
    .setVideoSize(1080, 1920)
    .setFps(30)
    .setBitrate(4_000_000)
    .setAudioFile(File("bgm.aac"))   // 可选
    .setOutputFile(File(context.filesDir, "demo.mp4"))
    .setRenderStrategy(PresentationStrategy { display, session ->
        DemoPresentation(context, display, session)
    })
    .setListener {
        onSaved = { file -> }
    }
    .build()

lifecycleScope.launch {
    val session = OSR.recorder(context, config)
}
```

## 配置说明

| 配置项            | 说明        | 默认值                    |
|----------------|-----------|------------------------|
| width / height | 视频宽高      | 1080 x 1920            |
| fps            | 帧率        | 30                     |
| bitrate        | 码率（bps）   | 4_000_000              |
| iFrameInterval | 关键帧间隔（秒）  | 1                      |
| output file    | 输出 MP4 路径 | 必填                     |
| audio file     | 背景音文件（可选） | null                   |
| renderStrategy | 渲染策略      | 必填（presentation / fbo） |

## 架构

```
OSR.recorder { }
    → RecorderConfig（公共配置）
    → RenderStrategy.createSession()（策略工厂）
        → PresentationRecorderSession  （Presentation 方案）
        → FboRecorderSession           （FBO 方案，预留）
```

公共配置 (`osp.osr`) 与渲染实现 (`osp.osr.pres` / `osp.osr.fbo`) 完全解耦，通过扩展函数注入。

## 依赖

- AndroidX Core、AppCompat、Material
- Kotlin Coroutines
- minSdk 29

## License

见项目 LICENSE 文件。
