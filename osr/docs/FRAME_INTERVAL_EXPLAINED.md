# 为什么 frameIntervalMs=1000 看起来「只有一帧」，改成 1000/33 就有很多帧？

## 先说清：1 秒间隔时编码器**确实**每秒有一帧

**间隔 1s 时**：每秒画 1 次 → 编码器每秒产 **1 帧**。录 5 秒就是约 **5 帧**，不是整段视频只有一帧。  
只是 1 帧/秒（1fps）看起来像幻灯片，所以容易被说成「只有一帧」；同时中间那 1 秒里编码器一直在等新图，就会反复打出 "no new frame drawn"。

## 一句话结论

**编码器只会对「Surface 上出现的新画面」进行编码。**  
你多久往 Surface 上画一次，编码器就**最多**按这个频率产出一帧。

- `frameIntervalMs=1000` → 每秒画 1 次 → **1 帧/秒**（5 秒约 5 帧，但很卡）。
- `1000/33` ≈ 每 33ms 画 1 次 → **约 30 帧/秒**（流畅）。

---

## 整条链路（谁决定「有多少帧」）

```
你写的代码：每 1000ms 调用一次 setBackgroundColor + invalidate()
        ↓
Presentation 的 View 被标记为「需要重画」
        ↓
系统在下一帧把 View 画到 VirtualDisplay 背后的 Surface 上（等于往 Encoder 的「输入」里塞进一帧图）
        ↓
MediaCodec 编码器：发现 Surface 上有新图 → 编码成一帧 H.264 → 通过 dequeueOutputBuffer 取出来
        ↓
launchEncoderLoop 里把这一帧 send 到 Channel，最后写入 MP4
```

- **frameIntervalMs = 1000**：每 **1000 毫秒** 执行一次「换颜色 + invalidate」→ Surface 每秒更新 **1 次** → 编码器**每秒产 1 帧**（录 5 秒约
  5 帧）。  
  因为只有 1fps，观感像幻灯片，常被说成「只有一帧」；而且每秒之间有约 1 秒没有新图，编码器会一直返回 TRY_AGAIN_LATER，所以会刷很多 "no new
  frame drawn"。

- **frameIntervalMs = 1000/33（约 33）**：每 **33 毫秒** 执行一次「换颜色 + invalidate」→ 每秒约 **30 次** 更新 Surface → 编码器每秒约 **30 帧
  **。  
  所以是接近 30fps 的连续视频，观感「有很多帧」、流畅。

**谁在「喂」帧给编码器？**  
是 **Presentation 的绘制频率**（你 delay 多少、或 Choreographer 多少毫秒一帧）。  
**launchEncoderLoop 不会自己造帧**，只是不断问编码器：「有编好的新帧吗？」有就取出来送进 Channel；没有就等（dequeueOutputBuffer 超时再问一次）。

---

## 和 launchEncoderLoop 的关系

- `launchEncoderLoop` 里是一个 **while 循环**，不断调用 `dequeueOutputBuffer(timeoutUs)`。
- **有新的「输入画面」**（Surface 被 Presentation 更新）→ 编码器才会去编码 → 才会有一块新的输出 buffer → `dequeueOutputBuffer` 返回一个 ≥0 的
  index，你才能 `send` 一帧。
- **没有新输入**（比如你 1 秒才 invalidate 一次）→ 在这 1 秒内，编码器没有新东西可编 → 每次 `dequeueOutputBuffer` 都会返回
  `INFO_TRY_AGAIN_LATER`，循环一直在「空转等待」，不会 `send` 新帧。

所以：

- **frameIntervalMs = 1000**：每秒给 Surface 1 次新图 → 编码器**每秒 1 帧**（5 秒约 5 帧），但 1fps 很卡，观感像「只有一帧」。
- **frameIntervalMs = 1000/33**：每秒给 Surface 约 30 次新图 → 编码器每秒约 30 帧 → 流畅、「有很多帧」。

总结：**帧数是由「你多久画一帧」（frameIntervalMs）决定的；launchEncoderLoop 只是把编码器已经编好的帧取出来、送到 MP4，不会增加或减少帧数。**
