# 录制视频数据流

## 链路概览

```
Presentation (UI 绘制)
       │
       ▼
VirtualDisplay (绑定 Encoder 的 Surface)
       │
       ▼
MediaCodec InputSurface (GPU 渲染结果进编码器)
       │
       ▼
EncoderController 编码循环 (dequeueOutputBuffer → 拷贝 → send)
       │
       ▼
Channel<EncodedFrame> (BUFFERED)
       │
       ▼
MuxerWriter 协程 (for (frame in muxerChannel) → writeSampleData)
       │
       ▼
MediaMuxer (写入 MP4 文件)
```

## 步骤说明

1. **Presentation** 在 VirtualDisplay 上展示，其内容绘制到 Display 背后的 **Surface**（即 MediaCodec 的 InputSurface）。
2. **VirtualDisplay** 由 `DisplayManager.createVirtualDisplay(..., surface, ...)` 创建，该 `surface` 来自 `encoder.createInputSurface()`
   ，因此绘制结果直接进编码器。
3. **EncoderController** 在协程里循环 `dequeueOutputBuffer(timeoutUs)`：
    - 若返回 `INFO_OUTPUT_FORMAT_CHANGED`，通知 Muxer 添加视频轨并 start；
    - 若返回有效 index 且非 CODEC_CONFIG，拷贝 Buffer 后 `muxerChannel.send(frameCopy)`；
    - 若带 `BUFFER_FLAG_END_OF_STREAM` 则退出循环并 close channel。
4. **MuxerWriter** 协程 `for (frame in muxerChannel)` 消费帧并调用 `muxerController.writeSampleData(videoTrackIndex, buffer, info)` 写入
   MP4。

## 为何会停在 “first video frame written”

- **“first video frame written”** 表示 MuxerWriter 已写入第一帧，随后会在 `for (frame in muxerChannel)` 上等待**下一帧**。
- 下一帧来自 **Encoder 编码循环** 的 `muxerChannel.send(frameCopy)`。
- Encoder 只有在 **Surface 上有新的一帧** 时，`dequeueOutputBuffer` 才会产出新的编码结果；新帧来自 **Presentation 的持续绘制**。

因此若 **Presentation 只画了一帧**（例如 onCreate 里只 setContentView 一次、没有后续 invalidate/动画），则：

- Surface 上只有一帧；
- 编码器只产出一帧并 send 一次；
- MuxerWriter 写完第一帧后就会一直阻塞在 `muxerChannel.receive()`，日志就停在 “first video frame written”。

## 正确做法：让 Presentation 持续输出帧

- 编码器按配置的 **fps**（如 30）期望持续输入；至少要在录制期间**周期性触发绘制**。
- 例如：
    - 用 **Choreographer** 或 **ValueAnimator** 每帧回调里 `view.postInvalidate()` 或更新内容；
    - 或用 **Handler.postDelayed** / 协程 **delay** 每隔约 `1000/fps` 毫秒（如 33ms）调用 `view.invalidate()` 并更新要画的内容。
- 当前 demo 里每秒只 `setBackgroundColor` 一次，相当于每秒最多 1 帧，且首帧之后要等 1 秒才有下一帧；若首帧之后没有触发第二次绘制，就只会有一帧，表现为卡在
  “first video frame written”。

## 小结

| 环节           | 职责                       | 阻塞点说明                                   |
|--------------|--------------------------|-----------------------------------------|
| Presentation | 持续向 VirtualDisplay 绘制    | 不绘制则下游无新帧                               |
| Encoder 循环   | dequeue → send 到 Channel | 无新输入时在 dequeue 上轮询等待                    |
| MuxerWriter  | 从 Channel 取帧写 MP4        | 无新帧时在 `for (frame in muxerChannel)` 上挂起 |

要得到连续视频，必须让 **Presentation 以目标帧率（或接近）持续刷新**。

---

## 排查 "no new frame drawn"

这条日志表示：**编码器已经至少编出过 1 帧，但之后一直拿不到新帧**（`dequeueOutputBuffer` 持续返回 `INFO_TRY_AGAIN_LATER`），即 **Surface
上没有新的画面**，通常是 **Presentation 没有持续往 VirtualDisplay 上画**。

### 按顺序排查

1. **刷新间隔是否太大**
    - 若用 `delay(1000)` 再 `postInvalidate()`，相当于每秒才画 1 次，中间会大量出现 "no new frame drawn"。
    - 目标 30fps 时，应用内“刷新间隔”建议约 **33ms**（或使用 Choreographer 每帧回调）。

2. **是否真的在触发绘制**
    - 在 Presentation 里对 content view 做：`content.postInvalidate()` 或 `content.invalidate()`。
    - 确认调用在 **主线程**，且 **循环/回调一直在跑**（例如用 Log 打一次 "draw request" 确认协程/Handler 没提前结束）。

3. **是否用 Choreographer 驱动（推荐）**
    - VirtualDisplay 上的 View 依赖系统合成，用 **Choreographer.postFrameCallback** 每帧请求一次绘制，比单纯 `delay(33)` 更稳定。
    - 示例：在回调里 `content.invalidate()`，再 `Choreographer.getInstance().postFrameCallback(this)` 继续下一帧，直到录制结束。

4. **Presentation 是否已 show**
    - 若 `presentation.show()` 未调用或失败，VirtualDisplay 上没有窗口，自然不会产生新帧。

5. **设备/模拟器是否异常**
    - 个别机型或模拟器上 VirtualDisplay 的合成可能异常，可换设备或镜像版本再试。
