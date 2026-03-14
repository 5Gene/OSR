下面是**更新后的完整方案文档**。
核心变化：

* **Session 移入 `source{}` 作用域**
* UI 在创建时即可拿到 **Session**
* UI 内部直接控制 **start / stop**
* **不使用 Presentation**
* **不使用 MediaProjection**
* 支持 **View / MapView**
* 支持 **Filter 扩展（示例：水波滤镜）**
* 架构符合 **SOLID + Clean Architecture**
* 并发模型 **Kotlin 协程**

本文档用于 **指导大模型生成代码**。

---

# Offscreen Recorder Library 设计方案

---

# 一、设计目标

实现一个 **Android 离屏视频录制库**。

核心目标：

支持画面来源：

```
普通 View
MapView
OpenGL Renderer（可扩展）
```

支持 GPU 滤镜：

```
Filter Pipeline
```

支持 UI 控制录制生命周期：

```
Session
```

支持事件监听：

```
录制开始
帧渲染
录制结束
错误
```

系统要求：

```
高性能
无系统权限
结构清晰
可扩展
```

---

# 二、关键技术约束

系统必须满足：

不使用

MediaProjection

不使用

```
Presentation
VirtualDisplay
```

视频生成流程：

```
GPU离屏渲染
```

---

# 三、整体架构

渲染管线：

```
FrameSource
     │
     ▼
FrameRenderer
     │
     ▼
FilterPipeline
     │
     ▼
FBO
     │
     ▼
EncoderSurface
     │
     ▼
MediaCodec
     │
     ▼
MP4
```

模块结构：

```
api
session
source
renderer
filter
encoder
event
config
core
```

模块职责：

```
api        外部调用
session    生命周期控制
source     UI画面输入
renderer   GPU渲染
filter     滤镜系统
encoder    视频编码
event      事件系统
config     参数配置
core       核心调度
```

---

# 四、Session 设计

Session 控制录制生命周期。

接口：

```kotlin
interface RecorderSession {

    fun start()

    fun pause()

    fun resume()

    fun stop()

}
```

Session 特点：

```
线程安全
UI可直接调用
内部状态机控制
```

---

# 五、使用方式设计

## Builder + DSL

核心 API：

```kotlin
val recorder = OffscreenRecorder.builder(context) {

    video {

        width = 1080
        height = 1920
        fps = 30
        bitrate = 8_000_000
        output = "/sdcard/demo.mp4"

    }
    //可选配置
    audio {
        input = "/sdcard/demo.aac"
    }
    filters {

        add(WaveFilter())

    }

    source { session ->

        view {

            AnimView(context, session)

        }

    }

}
```

准备：

```
recorder.prepare()
```

---

# 六、View 使用方式

UI 创建时直接拿到 Session。

示例：

```kotlin
class AnimView(
    context: Context,
    private val session: RecorderSession
) : View(context) {

    private val paint = Paint()

    var radius = 50f

    override fun onDraw(canvas: Canvas) {

        canvas.drawCircle(200f, 200f, radius, paint)

    }

    fun startAnimation() {

        session.start()

        ValueAnimator.ofFloat(50f, 200f).apply {

            duration = 2000

            addUpdateListener {

                radius = it.animatedValue as Float
                invalidate()

            }

            doOnEnd {

                session.stop()

            }

        }.start()

    }

}
```

使用：

```kotlin
source { session ->

    view {

        AnimView(context, session)

    }

}
```

---

# 七、MapView 使用方式

例如使用：

AMap Android SDK

示例：

```kotlin
class MapController(

    val mapView: MapView,
    val session: RecorderSession

) {

    fun startRouteDemo() {

        session.start()

        playRoute()

    }

    fun endRouteDemo() {

        session.stop()

    }

}
```

Recorder：

```kotlin
source { session ->

    map {

        MapController(mapView, session)

        mapView

    }

}
```

---

# 八、Source 设计

Source 是 UI 输入入口。

接口：

```kotlin
interface FrameSource {

    suspend fun attach(renderer: FrameRenderer)

    suspend fun detach()

}
```

实现：

```
ViewSource
MapSource
GLRendererSource
```

---

# 九、ViewSource 实现

原理：

```
View.draw(Canvas)
```

流程：

```
View
 ↓
Canvas
 ↓
Bitmap
 ↓
Texture
 ↓
FBO
```

实现：

```kotlin
class ViewSource(
    private val view: View
) : FrameSource {

    override suspend fun attach(renderer: FrameRenderer) {

        renderer.setView(view)

    }

}
```

---

# 十、MapSource 实现

MapView 底层：

```
TextureView
OpenGL
```

流程：

```
MapView
 ↓
SurfaceTexture
 ↓
Texture
 ↓
FBO
```

实现：

```kotlin
class MapSource(
    val mapView: MapView
) : FrameSource {

    override suspend fun attach(renderer: FrameRenderer) {

        renderer.setMap(mapView)

    }

}
```

---

# 十一、Renderer 设计

Renderer 负责：

```
获取画面
执行滤镜
输出到 FBO
```

接口：

```kotlin
interface FrameRenderer {

    suspend fun renderFrame(timeNs: Long)

}
```

实现：

```
ViewRenderer
TextureRenderer
GLRenderer
```

---

# 十二、Filter 系统

Filter Pipeline：

```
InputTexture
   ↓
Filter1
   ↓
Filter2
   ↓
Filter3
   ↓
OutputTexture
```

Filter 接口：

```kotlin
interface Filter {

    fun init()

    fun apply(
        inputTexture: Int,
        frameBuffer: Int
    ): Int

    fun release()

}
```

---

# 十三、水波滤镜示例

滤镜：

```
WaveFilter
```

实现：

```kotlin
class WaveFilter : Filter {

    override fun init() {}

    override fun apply(
        inputTexture: Int,
        frameBuffer: Int
    ): Int {

        // shader处理

        return inputTexture

    }

    override fun release() {}

}
```

Fragment Shader 示例：

```
uv.y += sin(uv.x * 20.0 + time) * 0.02
```

效果：

```
水波动画
```

---

# 十四、FilterPipeline

实现：

```kotlin
class FilterPipeline {

    private val filters = mutableListOf<Filter>()

    fun add(filter: Filter) {

        filters.add(filter)

    }

    fun render(texture: Int): Int {

        var current = texture

        filters.forEach {

            current = it.apply(current, fbo)

        }

        return current

    }

}
```

---

# 十五、Encoder 模块

编码基于：

```
MediaCodec
```

流程：

```
FBO
 ↓
EncoderSurface
 ↓
MediaCodec
 ↓
Muxer
 ↓
MP4
```

核心组件：

```
VideoEncoder
MuxerController
```

---

# 十六、协程架构

所有并发使用协程。

Dispatcher：

```
MainDispatcher
RenderDispatcher
EncodeDispatcher
IODispatcher
```

结构：

```
RecorderScope
    │
    ├─ RenderLoop
    ├─ EncodeLoop
    └─ EventLoop
```

RenderLoop：

```kotlin
while (recording) {

    renderer.renderFrame(time)

}
```

---

# 十七、事件监听系统

监听接口：

```kotlin
interface RecorderListener {

    fun onStart()

    fun onFrame(frameIndex: Long, timestamp: Long)

    fun onPause()

    fun onResume()

    fun onStop()

    fun onComplete(path: String)

    fun onError(error: Throwable)

}
```

DSL：

```kotlin
listener {

    onStart {}

    onFrame { frame, time -> }

    onComplete { path -> }

}
```

---

# 十八、配置系统

统一配置：

```
RecorderConfig
```

子配置：

```
VideoConfig
EncoderConfig
RenderConfig
FilterConfig
```

示例：

```kotlin
data class VideoConfig(

    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int

)
```

---

# 十九、状态机

状态：

```
Idle
Preparing
Prepared
Recording
Paused
Stopping
Completed
```

控制：

```
RecorderStateMachine
```

保证：

```
线程安全
状态合法
```

---

# 二十、模块结构

最终模块：

```
offscreen-recorder
 ├─ api
 ├─ session
 ├─ source
 ├─ renderer
 ├─ filter
 ├─ encoder
 ├─ event
 ├─ config
 └─ core
```

依赖关系：

```
api
 ↓
session
 ↓
core
 ↓
renderer
 ↓
encoder
```

---

# 二十一、最终能力

库最终支持：

```
普通 View 动画录制
MapView 录制
GPU 滤镜扩展
Session 控制录制
事件监听
```

系统特点：

```
不使用 MediaProjection
不使用 Presentation
高性能 GPU 渲染
可扩展滤镜
模块化架构
协程并发
```
