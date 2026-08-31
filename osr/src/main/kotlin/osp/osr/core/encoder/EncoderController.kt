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

    /**
     * 📏 clamp / MaxFS 缩小**之前**用户要的宽（通常=屏幕宽）。
     * 为啥留着：VirtualDisplay 算 dpi 要用「编码宽 / 原屏宽」同比缩小，
     * 以及 start 失败重配时要从「用户原始请求」再算一遍 MaxFS，不能拿已经对齐过的尺寸当原点。
     */
    var requestedWidth: Int = 0
        private set

    /**
     * 📏 同上，原始请求高。
     * start 失败 → retry MaxFS 时和 [requestedWidth] 一起喂给 [prepareStrict]。
     */
    var requestedHeight: Int = 0
        private set

    /** ✅ 编码循环结束时 complete，stopRecord 里 await 它，确保所有帧都写完再 stop Muxer */
    val done = CompletableDeferred<Unit>()

    /** 第一帧可写 sample（非 SPS）到达时 complete；供 Presentation 握手 / onReady */
    private var firstVideoFrame = CompletableDeferred<Unit>()

    val surface: Surface
        get() = inputSurface ?: throw RecorderError.EncoderError("InputSurface 尚未创建")

    /**
     * 📦 start() 的回执：有没有换过「画布」。
     *
     * [surfaceReplaced]=false：一次 start 就成功，外面啥都不用动。
     * [surfaceReplaced]=true：旧 codec/Surface 已扔，宽高可能变小了——
     *   Presentation 要 VD.resize；FBO 要 updateEncoderTarget，否则还往旧 Surface 上画。
     */
    class StartResult(val surfaceReplaced: Boolean)

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
        requestedHeight = reqH
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
        bindConfigured(encoder)
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

    /**
     * 🧩 按宽高建 AVC 编码器并 configure（还不 start）。
     *
     * 对照谷歌 EncodeAndMuxTest / MediaFormat 文档，Surface 编码最少要这几项：
     * MIME + 宽高 + COLOR_FormatSurface + BIT_RATE + FRAME_RATE + I_FRAME_INTERVAL，
     * 再 configure(..., CONFIGURE_FLAG_ENCODE)。键集合与官方一致。
     */
    private fun createAndConfigure(width: Int, height: Int): MediaCodec? {
        // 📊 码率：外部 bitrate>0 用用户值；默认 0 则 3*w*h（随分辨率自动估，不用懂 Mbps）
        // 例：1280×720 → ~2.8Mbps；976×2128 → ~6.2Mbps。越大画质越好、文件也越大。
        val bitRate = if (videoConfig.bitrate > 0) {
            videoConfig.bitrate
        } else {
            3 * width * height
        }

        // 🎬 createVideoFormat(mime, w, h)：
        // - mime=video/avc → H.264，设备兼容最好（HEVC 另说）
        // - w/h = 编码画布像素；决定缓冲大小与清晰度（已是 align/MaxFS 后的最终值）
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            // 🖼️ 颜色格式：COLOR_FormatSurface = 从 Surface 吃帧（VirtualDisplay / FBO 画上去）
            //    不是 CPU 塞 YUV ByteBuffer。谷歌 Surface 录制示例固定写这个。
            //    写错成 YUV Flexible 就没法 createInputSurface() 那套路。
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // 💰 目标平均码率（bps）。影响画质 vs 体积；码控会尽量靠近这个数。
            //    不解决 NO_MEMORY——那是宽高/缓冲问题。可选进阶键 KEY_BITRATE_MODE 本库不设。
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)

            // ⏱️ 帧率（fps）：编码器「码控参考」用的名义帧率，文档要求编码器必填。
            //    本库真实出多少帧 = InputSurface 多久来一帧（Presentation 绘制节奏），
            //    不是这个数字硬造帧。Demo 常用 30。
            setInteger(MediaFormat.KEY_FRAME_RATE, videoConfig.fps)

            // 🔑 关键帧间隔，单位是「秒」不是「帧数」！（谷歌文档 / EncodeAndMuxTest 常用 10）
            //    例：10 → 约每 10 秒一个 I 帧；0 → 几乎每帧都是关键帧（体积大、seek 细）。
            //    短动画用 10 可能整段只有 1 个关键帧，seek 粗但文件小；要细 seek 可设 1～2。
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoConfig.iFrameInterval)
        }

        // 按 MIME 拿系统硬编实例；还没 configure，不能 start，也不能 createInputSurface。
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        return try {
            // configure(format, surface, crypto, flags)：
            // - surface=null：编码输出不渲染到屏幕，我们自己 dequeue 写 Muxer
            // - crypto=null：不加密
            // - CONFIGURE_FLAG_ENCODE：这是编码器不是解码器
            // 成功后才能 createInputSurface()；真正申请 ION 缓冲多半在 start()。
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            OsrLog.i("✅ configure OK ${width}x${height} bitrate=$bitRate fps=${videoConfig.fps}")
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
     * ▶️ start：编码器开机，从 InputSurface 抓帧并编码。
     *
     * **效果**：输出队列很快会有数据；launchEncoderLoop 里 dequeue 会先拿到 INFO_OUTPUT_FORMAT_CHANGED，
     * 再拿到 CODEC_CONFIG（SPS/PPS），然后就是一帧一帧的 H.264。下一步必须 launchEncoderLoop，否则队列会满。
     *
     * 🙈 旧坑：configure() 在很多手机上「嘴上答应」（日志 ✅ configure OK），真正按宽高去申请
     *    硬件缓冲（ION/GraphicBuffer）是在 start() 里才干。整屏超大时就会：
     *    MediaCodec: err 0xfffffff4/NO_MEMORY, state 5/STARTING
     *    （0xfffffff4 = -12 = 底层说「内存/缓冲不够」，不是 Java 堆 OOM。）
     *    降 bitrate 救不了——缓冲大小跟画布宽高走，不跟码率走。
     *
     * 🛟 兜底：start 一炸 → 扔掉坏掉的 codec + 旧 InputSurface →
     *    若用户开的是 relaxed（strictAvc=false）：按原始请求再走一遍 MaxFS（=strict 那套）；
     *    若已经是 strict：直接降 720p。
     *    MaxFS 这档 start 还挂 → 再降 720p。全挂才抛给 Session。
     *    换过 Surface 时返回 surfaceReplaced=true，外面必须跟着换 VD / FBO 目标。
     */
    fun start(): StartResult {
        val beforeW = videoConfig.width
        val beforeH = videoConfig.height
        OsrLog.i("🎥 ▶️ codec.start ${beforeW}x${beforeH}")
        val encoder = codec ?: throw RecorderError.EncoderError("MediaCodec 尚未准备")
        try {
            // 🟢 开心路径：开机成功，Surface 还是 prepare 时那块，外面不用动
            encoder.start()
            return StartResult(surfaceReplaced = false)
        } catch (e: MediaCodec.CodecException) {
            // 典型：NO_MEMORY(0xfffffff4)。codec 已废，不能原地再 start，只能重建
            OsrLog.e(
                "💥 codec.start CodecException ${beforeW}x${beforeH} code=0x${Integer.toHexString(e.errorCode)}",
                e
            )
            return retryStartAfterFailure(fromRelaxed = !videoConfig.strictAvcLevel41)
        } catch (e: Exception) {
            OsrLog.e("💥 codec.start failed ${beforeW}x${beforeH}", e)
            return retryStartAfterFailure(fromRelaxed = !videoConfig.strictAvcLevel41)
        }
    }

    /**
     * 🛟 start 挂了之后的「换小号画布再开机」。
     *
     * **小白流程**：
     * 1. 旧编码器已经坏了 → [releaseCodecAndSurface] 连 InputSurface 一起扔（必须 release，否则泄漏）
     * 2. fromRelaxed=true：用户本来想冲高清（只 16 对齐），现在退一步走 [prepareStrict]（压到 MaxFS≤8192 宏块）
     * 3. fromRelaxed=false：prepare 时已经 MaxFS 过了，还 start 挂 → 只剩 720p 这张底牌
     * 4. 新 configure + 新 createInputSurface + 再 start
     * 5. MaxFS 档 start 仍挂（仅 fromRelaxed）→ 再来一轮 720p
     *
     * **为啥返回 surfaceReplaced=true**：新 Surface ≠ 旧 Surface。Presentation 的 VD、FBO 的 EGL
     * 还握着旧的就会画空气 / 黑屏，所以 Session 看到 true 必须 resize / updateEncoderTarget。
     */
    private fun retryStartAfterFailure(fromRelaxed: Boolean): StartResult {
        // 用原始请求宽高当原点（不是已经 align 过的），否则 MaxFS 缩放基准会偏
        val reqW = requestedWidth.coerceAtLeast(videoConfig.width)
        val reqH = requestedHeight.coerceAtLeast(videoConfig.height)
        // 🛑 先清场。stopFirst=false：start 半截失败时 codec 可能不在 Started，stop 会再抛一次，忽略即可
        releaseCodecAndSurface(stopFirst = false)

        val encoder = if (fromRelaxed) {
            OsrLog.w("🛟 start fail → retry MaxFS from ${reqW}x${reqH}")
            // ♻️ 复用 prepareStrict：等比缩到 AVC 4.1 MaxFS，configure 再跪才内部降 720p
            prepareStrict(reqW, reqH)
        } else {
            val (fbW, fbH) = fallback720p(videoConfig.width, videoConfig.height)
            OsrLog.w("🛟 start fail (already MaxFS) → fallback 720p ${fbW}x${fbH}")
            applySize(fbW, fbH)
            createAndConfigure(fbW, fbH)
                ?: throw RecorderError.EncoderError("MediaCodec.configure 失败（720p fallback）")
        }
        // 🆕 新 codec 必须重新 createInputSurface；旧的已经 release 了
        bindConfigured(encoder)

        try {
            encoder.start()
            OsrLog.i("🎥 ▶️ codec.start retry OK ${videoConfig.width}x${videoConfig.height}")
            return StartResult(surfaceReplaced = true)
        } catch (e: Exception) {
            if (!fromRelaxed) {
                // 已经是「strict 失败 → 720p」这条线，没有更小档了
                throw RecorderError.EncoderError("MediaCodec.start 失败（含 720p）", e)
            }
            // 🪜 第二级台阶：MaxFS 尺寸 start 仍 NO_MEMORY → 硬降 720×1280 / 1280×720
            OsrLog.e("💥 MaxFS start still fail, try 720p", e)
            releaseCodecAndSurface(stopFirst = false)
            val (fbW, fbH) = fallback720p(reqW, reqH)
            applySize(fbW, fbH)
            val fb = createAndConfigure(fbW, fbH)
                ?: throw RecorderError.EncoderError("MediaCodec.configure 失败（720p fallback）")
            bindConfigured(fb)
            try {
                fb.start()
                OsrLog.i("🎥 ▶️ codec.start 720p OK ${fbW}x${fbH}")
                return StartResult(surfaceReplaced = true)
            } catch (e2: Exception) {
                throw RecorderError.EncoderError("MediaCodec.start 失败（含 720p）", e2)
            }
        }
    }

    /**
     * 🔗 把「已 configure 的编码器」挂到本类字段上，并造一块新的 InputSurface。
     * prepare 成功路径、start 失败重配路径都会走这里，避免两处各写一遍 createInputSurface。
     */
    private fun bindConfigured(encoder: MediaCodec) {
        inputSurface = encoder.createInputSurface()
        codec = encoder
        // 重配后第一帧要从头等，旧的 CompletableDeferred 可能已经 complete/cancel 过
        firstVideoFrame = CompletableDeferred()
    }

    /**
     * 🧹 关掉编码器 + 释放 InputSurface。
     *
     * ⚠️ Surface 一定要 release：只把引用置 null，系统侧 BufferQueue / 硬编实例可能还占着，
     *    反复录制会越录越容易再撞 NO_MEMORY。
     * [stopFirst]=true：正常收尾（release()）先 stop 再 release；
     * [stopFirst]=false：start 失败重配时用，codec 可能还没真正 Started，stop 可省略。
     */
    private fun releaseCodecAndSurface(stopFirst: Boolean) {
        if (stopFirst) {
            runCatching { codec?.stop() }
        }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
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
     * 🔌 停掉并释放编码器；Session.release / 异常收尾会调。
     * 走 [releaseCodecAndSurface]，保证 InputSurface 也 release，避免硬编实例泄漏。
     */
    fun release() {
        OsrLog.d("🧹 encoder release")
        releaseCodecAndSurface(stopFirst = true)
    }

    companion object {
        /** dequeue 单次最多等 10ms，既不让线程空转太猛，又不至于等太久卡住 loop */
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
