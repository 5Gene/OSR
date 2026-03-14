# 离屏录制库实现总结

（对照 [Presentation.md](Presentation.md) 方案文档）

---

## 一、架构概览

采用 **公共层 + 策略工厂 + 扩展函数注入** 架构：

- **公共层** (`osp.osr`)：RecorderSession 接口、RenderStrategy 工厂接口、RecorderConfig DSL、公共编码组件
- **实现层** (`osp.osr.pres`)：Presentation 渲染策略，通过扩展函数 `RecorderConfig.presentation { }` 注入
- 两种策略（Presentation / FBO）各自实现 RecorderSession 接口，内部独立编排

```
OSR.recorder { }
    → RecorderConfig
    → RenderStrategy.createSession(context, config)
        → PresentationRecorderSession（编排 Encoder/Muxer/VirtualDisplay/Presentation）
        → FboRecorderSession（预留）
```

---

## 二、包与文件结构

```
osp/osr/                              # 公共层
├── RecorderSession.kt                 # 接口: startRecord/stopRecord/release/getState
├── OSR.kt                             # 入口: OSR.recorder(context) { }
├── model/
│   ├── RecorderState.kt               # 状态枚举
│   ├── RecorderError.kt               # 错误密封类
│   └── EncodedFrame.kt                # 编码帧数据
├── listener/
│   └── ListenerConfig.kt              # 可选回调 DSL
├── dsl/
│   └── RecorderConfig.kt              # DSL 配置 + Builder
├── render/
│   └── RenderStrategy.kt              # 工厂接口
└── core/
    ├── encoder/
    │   └── EncoderController.kt        # MediaCodec 编码
    ├── muxer/
    │   └── MuxerController.kt          # MediaMuxer 写入
    └── audio/
        └── AudioMixer.kt              # 音频混合

osp/osr/pres/                          # Presentation 实现层
├── PresentationStrategy.kt            # RenderStrategy 实现 + RecorderConfig.presentation() 扩展函数
├── PresentationRecorderSession.kt     # RecorderSession 实现
├── PresentationController.kt          # Presentation 生命周期
└── VirtualDisplayManager.kt           # VirtualDisplay 管理
```

---

## 三、方案对照

| 方案章节 | 要求 | 实现 |
|----------|------|------|
| 1 项目目标 | 无 MediaProjection、无权限 | ✅ |
| 2 技术原理 | UI→VirtualDisplay→Surface→MediaCodec→Muxer→MP4 | ✅ |
| 3 设计原则 | GPU 直连编码、协程架构 | ✅ |
| 4 系统架构 | OffscreenRecorder→Session→四控制器 | ✅ OSR→RenderStrategy→PresentationRecorderSession |
| 5.1 入口 | DSL + Builder | ✅ OSR.recorder {} + RecorderConfig.Builder |
| 5.2 Session | 状态机、startRecord/stopRecord | ✅ RecorderSession 接口 + PresentationRecorderSession 实现 |
| 5.3 Display | VirtualDisplay 创建/绑定 | ✅ VirtualDisplayManager |
| 5.4 Encoder | MediaCodec 配置 | ✅ EncoderController |
| 5.5 Muxer | MP4 写入、视频/音频轨 | ✅ MuxerController |
| 5.6 Presentation | 工厂/显示/关闭 | ✅ PresentationController |
| 6 DSL | video/output/presentation | ✅ + audio/listener |
| 7 监听器 | 回调 | ✅ listener { onStart = {}; onSaved = {} } |
| 8 协程 | SupervisorJob + Channel | ✅ |
| 9 数据结构 | EncodedFrame | ✅ |
| 11 音频 | 可选混入 | ✅ AudioMixer 循环填充 |
| 12 默认配置 | 1080x1920/30fps/4Mbps/1s | ✅ |
| 13 错误 | Encoder/Muxer/Display/Presentation | ✅ + Audio |
| 14 生命周期 | cancel scope、释放资源 | ✅ |

**扩展点：**

- RecorderSession 改为接口，支持 Presentation / FBO 各自实现
- RenderStrategy 工厂模式，公共层不感知具体渲染实现
- 扩展函数注入，零耦合

---

## 四、使用方式

**DSL：**

```kotlin
val session = OSR.recorder(context) {
    video { width = 1080; height = 1920 }
    audio { file = File("bgm.aac") }
    output { file = File("out.mp4") }
    listener { onSaved = { file -> } }
    presentation { display, session -> DemoPresentation(context, display, session) }
}
```

**Builder：**

```kotlin
val config = RecorderConfig.Builder()
    .setVideoSize(1080, 1920)
    .setOutputFile(File("out.mp4"))
    .setRenderStrategy(PresentationStrategy { display, session -> ... })
    .build()
val session = OSR.recorder(context, config)
```

---

## 五、代码注释与设计模式标注

源码中已补充详细注释（含 emoji 小节标记），并在体现设计模式处注明模式名称与优点。摘要如下：

| 文件                                 | 注释要点 | 设计模式标注 |
|------------------------------------|----------|----------------|
| **RecorderSession.kt**             | 录制会话统一抽象、4 个方法含义 | 策略模式的结果抽象：扩展新渲染方案不破坏 API |
| **RenderStrategy.kt**              | 渲染策略工厂、createSession 职责 | 策略 + 简单工厂：零耦合、易扩展 |
| **OSR.kt**                         | 唯一起入口、DSL/Builder、suspend 说明 | - |
| **RecorderConfig.kt**              | Video/Audio/Output 配置、DSL + Builder | Builder、DSL；renderStrategy 由扩展注入 |
| **RecorderState.kt**               | 状态流转说明 | - |
| **RecorderError.kt**               | 各子类含义 | - |
| **EncodedFrame.kt**                | 深拷贝原因（避免 buffer 复用） | - |
| **ListenerConfig.kt**              | 按需回调、用法 | - |
| **EncoderController.kt**           | prepare/InputSurface、EncoderLoop、深拷贝 | - |
| **MuxerController.kt**             | 视频/音频轨、@Synchronized | - |
| **AudioMixer.kt**                  | 抽轨、循环填充、时间戳累加 | - |
| **PresentationStrategy.kt**        | 策略实现、扩展函数作用 | 策略模式实现 + 扩展注入 |
| **PresentationRecorderSession.kt** | 编排链路、prepare/start/stop/release | 状态机（AtomicReference + CAS） |
| **PresentationController.kt**      | show 用 withContext 原因、dismiss 用 post 原因 | - |
| **VirtualDisplayManager.kt**       | Display 来自 MediaCodec、零拷贝 | - |

---

## 六、编译验证

`./gradlew :osc:compileDebugKotlin` — BUILD SUCCESSFUL
