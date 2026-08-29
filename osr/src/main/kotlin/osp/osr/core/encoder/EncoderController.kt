package osp.osr.core.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import osp.osr.dsl.VideoConfig
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError
import java.nio.ByteBuffer

/**
 * 🎞️ 视频编码器封装
 *
 * **职责**：建 AVC 编码器、建 InputSurface（给 VirtualDisplay/FBO 画）、在协程里 dequeue 编码结果，
 * 通过 onFrame 回调直接交给 Session 写 Muxer（无 Channel、无深拷贝）。
 *
 * **帧率从哪来**：编码器「出多少帧」完全由 InputSurface 上多久出现一新帧决定（Presentation 的绘制节奏）。
 * 例如 33ms 画一帧 → 约 30fps；1s 画一帧 → 就 1fps。本类只负责「取」已编好的帧，不造帧。
 */
class EncoderController(private val videoConfig: VideoConfig) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    /** clamp 前用户请求的宽，供 VD 密度缩放用 */
    var requestedWidth: Int = 0
        private set

    /** ✅ 编码循环结束时 complete，stopRecord 里 await 它，确保所有帧都写完再 stop Muxer */
    val done = CompletableDeferred<Unit>()

    /** 第一帧可写 sample（非 SPS）到达时 complete；供 Presentation 握手 / onReady */
    private var firstVideoFrame = CompletableDeferred<Unit>()

    val surface: Surface
        get() = inputSurface ?: throw RecorderError.EncoderError("InputSurface 尚未创建")

    /**
     * 🛠️ prepare：配好编码器 + 创建 InputSurface，**不** start。
     *
     * **为啥不在这 start**：Surface 要先绑给 VirtualDisplay、Presentation 先画几帧，再 start 才能录到内容。
     * **执行后**：调用方拿返回的 Surface；Presentation 路径应延后绑到 VD（见 VirtualDisplayManager）。
     */
    fun prepare(): Surface {
        val reqW = videoConfig.width
        val reqH = videoConfig.height
        requestedWidth = reqW
        OsrLog.i(
            "🎬 encoder prepare request ${reqW}x${reqH} fps=${videoConfig.fps} " +
                "bitrate=${videoConfig.bitrate} strictAvc=${videoConfig.strictAvcLevel41}"
        )

        // 🙈 旧写法：宽高原样丢给 configure，不 16 对齐、不算宏块、跪了也不换小号再试。
        // 💥 平板大分辨率直接 CodecException（常 0xfffffc0e）。
        // 🧩 H.264 宏块≈(w/16)*(h/16)；AVC 4.1 MaxFS=8192。
        // ✅ strict=true：先 MaxFS 纠正；strict=false：先只 16 对齐，失败再 MaxFS，再失败 720p。
        val encoder = if (videoConfig.strictAvcLevel41) {
            prepareStrict(reqW, reqH)
        } else {
            prepareRelaxed(reqW, reqH)
        }

        // 🎯 得到「编码器的输入 Surface」：谁往这个 Surface 上画，编码器就编谁。VirtualDisplay 会绑定它。
        // 效果：之后 start() 一调，编码器就会开始从 Surface 取帧并输出 H.264。
        inputSurface = encoder.createInputSurface()
        codec = encoder
        firstVideoFrame = CompletableDeferred()
        OsrLog.i("🖼️ InputSurface ready ${videoConfig.width}x${videoConfig.height}")
        return inputSurface!!
    }

    /** 一开始就按 MaxFS 纠正，失败再 720p */
    private fun prepareStrict(reqW: Int, reqH: Int): MediaCodec {
        val (w, h) = clampEncodeSize(reqW, reqH)
        applySize(w, h)
        OsrLog.i("📐 try MaxFS clamp ${reqW}x${reqH} → ${w}x${h}")
        createAndConfigure(w, h)?.let { return it }

        val (fbW, fbH) = fallback720p(w, h)
        OsrLog.w("🛟 MaxFS configure fail → fallback 720p ${fbW}x${fbH}")
        applySize(fbW, fbH)
        return createAndConfigure(fbW, fbH)
            ?: throw RecorderError.EncoderError("MediaCodec.configure 失败（含 720p fallback）")
    }

    /** 先 16 对齐；失败再 MaxFS；仍失败再 720p */
    private fun prepareRelaxed(reqW: Int, reqH: Int): MediaCodec {
        val (alignW, alignH) = alignEncodeSize(reqW, reqH)
        applySize(alignW, alignH)
        OsrLog.i("📐 try align ${reqW}x${reqH} → ${alignW}x${alignH}")
        createAndConfigure(alignW, alignH)?.let { return it }

        val (clampW, clampH) = clampEncodeSize(alignW, alignH)
        applySize(clampW, clampH)
        OsrLog.w("🛟 align configure fail → clamp MaxFS ${clampW}x${clampH}")
        createAndConfigure(clampW, clampH)?.let { return it }

        val (fbW, fbH) = fallback720p(clampW, clampH)
        OsrLog.w("🛟 MaxFS configure fail → fallback 720p ${fbW}x${fbH}")
        applySize(fbW, fbH)
        return createAndConfigure(fbW, fbH)
            ?: throw RecorderError.EncoderError("MediaCodec.configure 失败（含 720p fallback）")
    }

    private fun applySize(width: Int, height: Int) {
        videoConfig.width = width
        videoConfig.height = height
    }

    private fun createAndConfigure(width: Int, height: Int): MediaCodec? {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoConfig.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoConfig.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoConfig.iFrameInterval)
        }

        // 按 MIME 拿系统编码器实例；还没 configure，不能 start。
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        return try {
            // 进入 Configured 状态；第三个 null = 不用 Surface 做渲染，第四个 FLAG = 编码模式。
            // 效果：可以接着 createInputSurface()，但还不能喂数据。
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            OsrLog.i("✅ configure OK ${width}x${height} bitrate=${videoConfig.bitrate} fps=${videoConfig.fps}")
            encoder
        } catch (e: MediaCodec.CodecException) {
            OsrLog.e("💥 configure CodecException ${width}x${height} code=0x${Integer.toHexString(e.errorCode)}", e)
            runCatching { encoder.release() }
            null
        } catch (e: Exception) {
            OsrLog.e("💥 configure failed ${width}x${height}", e)
            runCatching { encoder.release() }
            null
        }
    }

    /**
     * ▶️ start：编码器开始工作，从 InputSurface 抓帧并编码。
     *
     * **效果**：内部输出队列里很快会有数据；launchEncoderLoop 里 dequeue 会先拿到 INFO_OUTPUT_FORMAT_CHANGED，
     * 再拿到 CODEC_CONFIG（SPS/PPS），然后就是一帧一帧的 H.264。调用方下一步必须 launchEncoderLoop，否则队列会满。
     */
    fun start() {
        OsrLog.i("▶️ codec.start ${videoConfig.width}x${videoConfig.height}")
        codec?.start() ?: throw RecorderError.EncoderError("MediaCodec 尚未准备")
    }

    /**
     * 🔁 在 [scope] 里起一个协程：死循环 dequeue → 格式变化时调 onFormatChanged，有帧就调 onFrame。
     *
     * **为啥不用 Channel、不深拷贝**：onFrame 是同步调用的，我们在回调里直接 writeSampleData，返回后立刻 releaseOutputBuffer，
     * buffer 在回调期间不会被复用，所以不用 clone。这样少一层 MuxerWriter 协程和每帧拷贝。
     *
     * **调用顺序**：先 onFormatChanged（一次）→ 再多次 onFrame（每帧）→ EOS 时 break，finally 里 done.complete。
     */
    fun launchEncoderLoop(
        scope: CoroutineScope,
        onFormatChanged: (MediaFormat) -> Unit,
        onFrame: (buffer: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit
    ) {
        val encoder = codec ?: throw RecorderError.EncoderError("MediaCodec 尚未准备")
        val bufferInfo = MediaCodec.BufferInfo()

        scope.launch(Dispatchers.Default) {
            var frameCount = 0
            var timeoutCount = 0
            OsrLog.d("🔁 encoder loop started")
            try {
                while (isActive) {
                    // 📥 从编码器输出队列拿一块；最多等 DEQUEUE_TIMEOUT_US。返回负数表示「还没好」或「格式变了」。
                    // 效果：>=0 时 bufferInfo 里已有本帧的 offset/size/pts/flags；INFO_OUTPUT_FORMAT_CHANGED 时要先处理格式。
                    val index = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            timeoutCount++
                            if (frameCount >= 1 && timeoutCount % 10000 == 0) {
                                OsrLog.e("😴 encoder waiting Surface input timeouts=$timeoutCount")
                            }
                        }
                        // 📢 编码器说「我格式好了」，通常 start() 后第一轮 dequeue 就是。必须在这时让 Muxer 加视频轨并 start。
                        // encoder.outputFormat：带 SPS/PPS 等，Muxer addTrack 需要。下一轮 dequeue 会拿到 CODEC_CONFIG 或首帧。
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            OsrLog.i("📢 OUTPUT_FORMAT_CHANGED → muxer addTrack")
                            onFormatChanged(encoder.outputFormat)
                        }

                        index >= 0 -> {
                            timeoutCount = 0
                            // 拿到这一帧的只读 ByteBuffer；在 releaseOutputBuffer 之前都有效，所以 onFrame 里可以放心写给 Muxer。
                            val buffer = encoder.getOutputBuffer(index)
                                ?: throw RecorderError.EncoderError("输出 Buffer 为空, index=$index")

                            // SPS/PPS 等头信息，OUTPUT_FORMAT_CHANGED 时已经通过 format 交给 Muxer 了，这里直接还槽位跳过。
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encoder.releaseOutputBuffer(index, false)
                                continue
                            }

                            if (bufferInfo.size > 0) {
                                onFrame(buffer, bufferInfo)
                                frameCount++
                                if (frameCount == 1) {
                                    firstVideoFrame.complete(Unit)
                                    OsrLog.i("🎬 first sample pts=${bufferInfo.presentationTimeUs}us size=${bufferInfo.size}")
                                } else if (frameCount % 30 == 0) {
                                    OsrLog.d("🎞️ frames=$frameCount pts=${bufferInfo.presentationTimeUs}us size=${bufferInfo.size}")
                                }
                            }

                            // 还槽位给编码器复用。必须在我们写完 Muxer 之后，所以顺序是：onFrame → release。
                            encoder.releaseOutputBuffer(index, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                OsrLog.i("🏁 encoder EOS totalFrames=$frameCount")
                                break
                            }
                        }
                    }
                }
            } finally {
                done.complete(Unit)
                OsrLog.d("🔁 encoder loop finished")
            }
        }
    }

    /** 等第一帧可写 sample；超时返回 false。用户取消会抛 CancellationException。 */
    suspend fun awaitFirstVideoFrame(timeoutMs: Long): Boolean {
        val ok = withTimeoutOrNull(timeoutMs) { firstVideoFrame.await() } != null
        OsrLog.i("⏳ awaitFirstFrame timeoutMs=$timeoutMs ok=$ok")
        return ok
    }

    /**
     * 📤 告诉编码器「没有新输入了」，用于 stopRecord。
     *
     * **效果**：编码器把手头还没编完的帧编完，最后一块输出会带 BUFFER_FLAG_END_OF_STREAM；
     * launchEncoderLoop 里检测到 EOS 就 break → finally 里 done.complete(Unit) → stopRecord 里 await 返回。
     */
    fun signalEndOfStream() {
        OsrLog.d("📤 signalEndOfInputStream")
        codec?.signalEndOfInputStream()
    }

    /**
     * 🔌 停掉并释放编码器；release() 里会调，或异常时收尾。
     * stop 清队列，release 关资源；之后 codec 不可再用。
     */
    fun release() {
        OsrLog.d("🧹 encoder release")
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        inputSurface = null
    }

    companion object {
        /** dequeue 单次最多等 10ms，既不让线程空转太猛，又不至于等太久卡住 loop */
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
