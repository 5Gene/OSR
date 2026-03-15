### 🔍 深度分析与潜在隐患（专家级 Review）

虽然整体方案非常优秀，但在实际落地和极端场景下，仍有几个**严重细节**需要你关注：

#### 1. FBO 尺寸映射与 Viewport 陷阱 (Critical)

* **问题**：文档提到使用 `glGetIntegerv(GL_VIEWPORT)` 作为 `glBlitFramebuffer` 的源矩形。
* **风险**：在某些地图 SDK 或复杂的 UI 场景中，`Viewport` 并不等于整个渲染表面的尺寸（比如局部渲染或分屏）。如果用户在 `onDrawFrame` 中多次更改了
  Viewport，库在回调时拿到的可能是最后一刻的局部 Viewport，导致录制画面**裁剪或偏移**。
* **建议**：在 `captureFrame` 之前，最好能通过宿主环境获取 `Surface` 的真实 `Width/Height`（例如 `onSurfaceChanged` 中保存的值），而不是完全依赖
  `GL_VIEWPORT` 的实时状态。

#### 2. EGL 状态恢复的完整性 (High)

* **问题**：方案提到了恢复 FBO 绑定和 EGL Surface。
* **风险**：OpenGL 是一个巨大的状态机。`eglMakeCurrent` 会重置大量状态。虽然你用 `finally` 恢复了 Surface，但**
  纹理绑定单元（ActiveTexture）、混合模式（Blend）、深度测试（Depth Test）**等状态可能已被滤镜链修改。
* **建议**：在 `captureFrame` 开始时，使用 `glPushGroupMarker`（如果支持）或手动保存关键状态。最稳妥的做法是：**FilterPipeline
  内部必须严谨地执行“谁改变，谁恢复”**，确保不污染宿主 GL 状态，否则会导致屏幕上的地图出现闪烁或透明度异常。

#### 3. 方式 4 (View Capture) 的性能瓶颈 (Medium)

* **问题**：`Canvas -> Bitmap -> glTexSubImage2D`。
* **分析**：这是典型的 CPU 到 GPU 的“慢路径”。对于 1080P 画面，Bitmap 上传到 GPU 的耗时在部分机型上会超过 16ms，导致主线程掉帧。
* **建议**：
* **V1 阶段**：务必在 `Bitmap.createBitmap` 时指定 `Hardware` 配置（如果仅读取）或确保 Bitmap 内存对齐。
* **V2 阶段**：方案中提到的 **PBO (Pixel Buffer Object)** 异步上传是必须的。利用双 PBO 可以实现真正的流水线并行，让 CPU 在绘制下一帧时，GPU
  异步传输上一帧数据。

#### 4. 滤镜链的 FBO 管理 (Optimization)

* **问题**：`BlurFilter` 需要 2 个临时 FBO 做 ping-pong。
* **风险**：如果用户链式开启了多个滤镜，显存开销和 FBO 切换开销会激增。
* **建议**：引入一个简单的 **FBO Pool**。滤镜不需要自己创建 FBO，而是向 `FilterPipeline` 申请。这样可以复用相同尺寸的纹理对象，极大减少初始化耗时和内存压力。

---

### 🚀 建议的下一步行动

1. **编写具体的 `BlurFilter` Shader 代码**：包括如何实现高效的两趟高斯模糊处理。
2. **设计 `FBO Pool` 的具体实现方案**：确保在多滤镜场景下内存占用最低。
3. **细化 `RendererDecorator` 的实现**：处理 `GLSurfaceView` 生命周期与录制 Session 异步开启之间的竞态保护。
