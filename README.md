# OffscreenRecorder

Android 离屏录制库 - 在不使用 MediaProjection 和系统权限的情况下，将 UI 渲染到虚拟 Display 并录制为视频。

## 特性

- 无需 MediaProjection
- 无需系统敏感权限
- 无需用户授权
- 使用 MediaCodec 的 Surface 作为渲染目标
- 通过 Presentation 在虚拟 Display 上渲染 UI
- Kotlin DSL + Builder 方式使用
- 完整协程并发架构

## 快速开始

### 1. 基本使用 (Kotlin DSL)

```kotlin
class MainActivity : AppCompatActivity() {

    private var session: RecorderSession? = null

    fun startRecording() {
        session = OffscreenRecorder.record(this) {
            width = 1080
            height = 1920
            fps = 30
            bitrate = 4_000_000
            outputFile = File(getExternalFilesDir(null), "demo.mp4")

            listener = object : RecorderListener {
                override fun onPrepared() = Unit
                override fun onRecordingStart() = Unit
                override fun onRecordingStop() = Unit
                override fun onVideoSaved(file: File) = Unit
                override fun onError(error: RecorderError) = Unit
            }

            presentation { display, session ->
                DemoPresentation(this@MainActivity, display, session)
            }
        }

        session?.prepare()
    }

    fun stopRecording() {
        session?.stopRecord()
        session?.release()
        session = null
    }
}
```

### 2. Presentation 实现

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

### 3. Builder 方式

```kotlin
val session = OffscreenRecorder.builder(context)
    .setVideoSize(1080, 1920)
    .setBitrate(4_000_000)
    .setOutputFile(File("output.mp4"))
    .setPresentationFactory { display, session ->
        DemoPresentation(context, display, session)
    }
    .build()

session.prepare()
```

## 配置参数

| 参数             | 默认值       | 说明          |
|----------------|-----------|-------------|
| width          | 1080      | 视频宽度        |
| height         | 1920      | 视频高度        |
| fps            | 30        | 帧率          |
| bitrate        | 4,000,000 | 比特率 (4Mbps) |
| iFrameInterval | 1         | I 帧间隔 (秒)   |
| densityDpi     | 320       | 屏幕密度        |

## 状态机

```
Idle → Prepared → Recording → Stopping → Prepared
                ↓                   
              Released
```

## 错误处理

```kotlin
listener = object : RecorderListener {
    override fun onError(error: RecorderError) {
        when (error) {
            is RecorderError.EncoderError -> { /* 编码器错误 */
            }
            is RecorderError.MuxerError -> { /* 合成器错误 */
            }
            is RecorderError.DisplayError -> { /* 显示错误 */
            }
            is RecorderError.PresentationError -> { /* Presentation 错误 */
            }
            is RecorderError.ConfigurationError -> { /* 配置错误 */
            }
            is RecorderError.IllegalStateError -> { /* 状态错误 */
            }
        }
    }
}
```

## 数据流

```
Presentation UI
      │
      ▼
VirtualDisplay
      │
      ▼
Surface (MediaCodec Input)
      │
      ▼
MediaCodec Encoder
      │
      ▼
MediaMuxer
      │
      ▼
MP4 File
```

## 应用场景

- 地图自动生成视频
- UI 自动生成视频
- 视频素材生成
- UI 测试录制
- 自动化演示视频
- AI 视频生成

## 导入

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":osc"))
}
```

## 许可

MIT License