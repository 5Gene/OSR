package osp.osr.pres

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import android.view.Surface
import osp.osr.dsl.VideoConfig
import osp.osr.log.OsrLog
import osp.osr.model.RecorderError

/**
 * 🖼️ 虚拟 Display 管理
 *
 * 创建 OWN_CONTENT_ONLY 假屏幕；Presentation 路径应 **延后** 绑定 encoder Surface（见 [setSurface]），
 * 避免 ColorOS layerstack=-1 / 华为未 start 黑首帧。
 */
internal class VirtualDisplayManager(private val context: Context) {

    private var virtualDisplay: VirtualDisplay? = null

    val display: Display
        get() = virtualDisplay?.display
            ?: throw RecorderError.DisplayError("VirtualDisplay 尚未创建")

    /**
     * 绑定 [surface]（来自 EncoderController.createInputSurface），按 [videoConfig] 尺寸创建虚拟 Display。
     *
     * DisplayManager.createVirtualDisplay(name, width, height, densityDpi, surface, flags)：
     * 创建一个虚拟 Display，其内容将渲染到给定的 Surface 上。
     * 执行后：virtualDisplay.display 可交给 Presentation；在 Presentation 上绘制的内容会合成到 surface，
     *        即编码器的 InputSurface，故绘制会直接作为编码器输入（编码器 start 后开始取帧）。
     * VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY：仅合成本 Display 自己的内容，不包含其他层。
     *
     * 下一步：调用方用返回的 Display 做 presentationController.show(display, session)。
     */
    fun createDisplay(
        surface: Surface?,
        videoConfig: VideoConfig,
        densityDpi: Int
    ): Display {
        // 🙈 旧写法：假屏幕一出生就把 encoder Surface 绑上，而且此时 codec 还没 start。
        // 👻 ColorOS：dumpsys 里 layerstack=-1，成片空白，dequeue 空转到天荒地老。
        //    OWN_CONTENT_ONLY 刚创建时 layerstack 可能闪一下 -1（还不归任何合成栈）；
        //    这窗口把 Surface 绑死，有的机型之后再也不往这儿送帧——你再 start 也是对空气编码。
        // 🖤 华为：片头一段黑。encoder 没 start，VD 已经往 Surface 塞黑初始帧进 BufferQueue；
        //    一 start，队列里先到的就是这几帧黑的，像摄影机先拍了镜头盖。
        // ✅ 这里 surface=null 先空降假屏幕；codec.start() 后再 setSurface，并解绑再绑一次把栈拉回来。
        // virtualDisplay = dm.createVirtualDisplay(..., surface, FLAG_OWN_CONTENT_ONLY)
        // 🙈 旧写法：编码画布缩小了，dpi 还用手机原密度。
        // 🔍 小画布 + 高密度 = UI 被「放大」糊成一团。
        // ✅ density = 设备dpi × 编码宽 / 原屏宽，跟画布一起瘦身。
        // context.resources.displayMetrics.densityDpi
        OsrLog.i("🖥️ VD create ${videoConfig.width}x${videoConfig.height} densityDpi=$densityDpi surfaceAttached=${surface != null}")
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        virtualDisplay = dm.createVirtualDisplay(
            DISPLAY_NAME,
            videoConfig.width,
            videoConfig.height,
            densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        ) ?: throw RecorderError.DisplayError("创建 VirtualDisplay 失败")

        OsrLog.i("🖥️ VD created ${videoConfig.width}x${videoConfig.height}")
        return virtualDisplay!!.display
    }

    /** codec.start() 之后再绑 / 解绑再绑，把 ColorOS layerstack 拉回正常 */
    fun setSurface(surface: Surface?) {
        OsrLog.d("🔗 VD setSurface attached=${surface != null}")
        virtualDisplay?.surface = surface
    }

    /**
     * 释放 VirtualDisplay。
     * release()：销毁虚拟 Display，不再向 Surface 合成；执行后 display 不可再使用。
     */
    fun release() {
        OsrLog.d("🧹 VD release")
        try {
            virtualDisplay?.surface = null
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
    }

    companion object {
        private const val DISPLAY_NAME = "OffscreenRecorderDisplay"
    }
}
