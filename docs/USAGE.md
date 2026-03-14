# OffscreenRecorder 使用文档

## 概述

OffscreenRecorder 是一个 Android 离屏录制库，用于在不使用 MediaProjection 和系统权限的情况下，将 UI 渲染到虚拟 Display 并录制为视频。

## 特性

- 无需 MediaProjection
- 无需系统敏感权限
- 无需用户授权
- 使用 MediaCodec 的 Surface 作为渲染目标
- 通过 Presentation 在虚拟 Display 上渲染 UI
- Kotlin DSL + Builder 方式使用
- 完整协程并发架构

## 快速开始

### 1. 添加依赖

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.osp.osc:offscreen-recorder:1.0.0")
}
```

### 2. 基本使用 (Kotlin DSL)

```kotlin
class MainActivity : AppCompatActivity() {
    
    private var session: RecorderSession? = null
    
    fun startRecording() {
        session = OffscreenRecorder.record(this) {
            // 视频配置
            width = 1080
            height = 1920
            fps = 30
            bitrate = 4_000_000
            
            // 输出文件
            outputFile = File(getExternalFilesDir(null), "demo.mp4")
            
            // 监听器 (可选)
            listener = object : RecorderListener {
                override fun onPrepared() {
                    Log.d("Recorder", "Prepared")
                }
                
                override fun onRecordingStart() {
                    Log.d("Recorder", "Recording started")
                }
                
                override fun onRecordingStop() {
                    Log.d("Recorder", "Recording stopped")
                }
                
                override fun onVideoSaved(file: File) {
                    Log.d("Recorder", "Video saved: ${file.absolutePath}")
                }
                
                override fun onError(error: RecorderError) {
                    Log.e("Recorder", "Error: ${error.message}")
                }
            }
            
            // Presentation 工厂
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

### 3. Presentation 实现

```kotlin
class DemoPresentation(
    context: Context,
    display: Display,
    private val session: RecorderSession
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.demo)
        
        // 在 Presentation 中控制录制
        lifecycleScope.launch {
            delay(1000)  // 等待 UI 准备好
            
            session.startRecord()  // 开始录制
            
            delay(5000)  // 录制 5 秒
            
            session.stopRecord()  // 停止录制
        }
    }
}
```

### 4. Builder 方式

```kotlin
val session = OffscreenRecorder.builder(context)
    .setVideoSize(1080, 1920)
    .setBitrate(4_000_000)
    .setFps(30)
    .setOutputFile(File("output.mp4"))
    .setPresentationFactory { display, session ->
        DemoPresentation(context, display, session)
    }
    .setListener(listener)
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

所有错误都会通过 `onError` 回调：

```kotlin
listener = object : RecorderListener {
    override fun onError(error: RecorderError) {
        when (error) {
            is RecorderError.EncoderError -> {
                // 编码器错误
            }
            is RecorderError.MuxerError -> {
                // 合成器错误
            }
            is RecorderError.DisplayError -> {
                // 显示错误
            }
            is RecorderError.PresentationError -> {
                // Presentation 错误
            }
            is RecorderError.ConfigurationError -> {
                // 配置错误
            }
            is RecorderError.IllegalStateError -> {
                // 状态错误
            }
        }
    }
}
```

## 完整示例

```kotlin
class MapVideoGenerator(private val context: Context) {

    private var session: RecorderSession? = null
    
    fun generateMapVideo(outputFile: File) {
        session = OffscreenRecorder.record(context) {
            width = 1080
            height = 1920
            bitrate = 6_000_000
            outputFile = outputFile
            
            listener = object : RecorderListener {
                override fun onPrepared() = Unit
                override fun onRecordingStart() = Unit
                override fun onRecordingStop() = Unit
                override fun onVideoSaved(file: File) {
                    Log.d("MapVideo", "Saved: ${file.absolutePath}")
                }
                override fun onError(error: RecorderError) {
                    Log.e("MapVideo", "Error: ${error.message}", error)
                }
            }
            
            presentation { display, session ->
                MapPresentation(context, display, session)
            }
        }
        
        session?.prepare()
    }
    
    fun release() {
        session?.release()
        session = null
    }
}

// MapPresentation 示例
class MapPresentation(
    context: Context,
    display: Display,
    private val session: RecorderSession
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.map_layout)
        
        val mapView = findViewById<MapView>(R.id.map_view)
        mapView.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            // 地图操作和录制控制
            delay(500)
            session.startRecord()
            
            // 模拟地图动画
            for (i in 0..10) {
                mapView.animateTo(LatLng(39.9 + i * 0.1, 116.4))
                delay(500)
            }
            
            session.stopRecord()
        }
    }
}
```

## 注意事项

1. **生命周期**: 确保在合适的时机调用 `prepare()`, `startRecord()`, `stopRecord()`, `release()`
2. **权限**: 不需要任何敏感权限
3. **线程**: 所有回调都在主线程
4. **协程**: 可在 Presentation 中使用 lifecycleScope 控制录制时机
5. **输出格式**: 仅支持 MP4 格式

## 性能优化

- 使用 GPU 直接渲染到 Surface，避免 Bitmap 拷贝
- 协程处理编码和写入，主线程无阻塞
- Channel 支持背压，避免内存溢出