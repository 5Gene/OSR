# OffscreenRecorder 代码结构文档

## 包结构

```
com.osp.osc.pres
├── OffscreenRecorder.kt          # 主入口
├── RecorderSession.kt            # 核心会话控制类
├── core/                         # 核心控制器
│   ├── EncoderController.kt      # MediaCodec 视频编码器
│   ├── MuxerController.kt        # MediaMuxer 音视频合成
│   ├── VirtualDisplayManager.kt  # VirtualDisplay 管理
│   ├── PresentationController.kt # Presentation 控制
│   ├── RecorderState.kt          # 状态枚举
│   └── MediaFormatHelper.kt      # 编码格式配置
├── dsl/                          # DSL 配置
│   ├── Builder.kt                # Builder 模式
│   └── RecorderConfig.kt         # DSL 配置类
├── data/                         # 数据模型
│   └── EncodedFrame.kt           # 编码帧数据
├── error/                        # 错误处理
│   └── RecorderError.kt          # 错误类型定义
├── coroutine/                    # 协程管理
│   └── RecorderScope.kt          # 协程作用域
└── listener/                     # 事件监听
    └── RecorderListener.kt       # 事件监听接口
```

## 核心类说明

### OffscreenRecorder

- **作用**: 库的主入口，提供 DSL 和 Builder 两种使用方式
- **主要方法**:
    - `record(context, block)`: DSL 方式创建录制会话
    - `builder(context)`: Builder 方式创建录制会话

### RecorderSession

- **作用**: 核心控制类，管理整个录制流程
- **状态机**: Idle → Prepared → Recording → Stopping → Prepared/Released
- **主要方法**:
    - `prepare()`: 准备录制环境
    - `startRecord()`: 开始录制
    - `stopRecord()`: 停止录制
    - `release()`: 释放资源

### EncoderController

- **作用**: 管理 MediaCodec 编码器
- **功能**:
    - 创建输入 Surface
    - 配置编码参数
    - 编码循环
    - 错误回调

### MuxerController

- **作用**: 管理 MediaMuxer 写文件
- **功能**:
    - 创建 MP4 文件
    - 添加视频轨道
    - 写入编码帧

### VirtualDisplayManager

- **作用**: 管理虚拟显示
- **功能**:
    - 创建 VirtualDisplay
    - 绑定 Surface

### PresentationController

- **作用**: 管理 Presentation
- **功能**:
    - 创建 Presentation
    - 显示/隐藏 Presentation

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
Channel<EncodedFrame>
      │
      ▼
MediaMuxer
      │
      ▼
MP4 File
```

## 错误类型

- `EncoderError`: 编码器错误
- `MuxerError`: 合成器错误
- `DisplayError`: 虚拟显示错误
- `PresentationError`: Presentation 错误
- `ConfigurationError`: 配置错误
- `IllegalStateError`: 状态错误

## 事件监听

所有错误和状态变化都会通过 `RecorderListener` 回调:

- `onPrepared()`: 准备完成
- `onRecordingStart()`: 开始录制
- `onRecordingStop()`: 停止录制
- `onVideoSaved(file)`: 视频保存完成
- `onError(error)`: 发生错误