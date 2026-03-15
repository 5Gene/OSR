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
 * 用 MediaCodec 的 InputSurface 创建 VirtualDisplay，得到 [Display] 再交给 Presentation。
 * 这样 GPU 渲染直接进编码器，零拷贝，符合方案里的“Display 必须来自 MediaCodec”的链路。
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
    fun createDisplay(surface: Surface, videoConfig: VideoConfig): Display {
        OsrLog.d("create VirtualDisplay ${videoConfig.width}x${videoConfig.height} densityDpi=${context.resources.displayMetrics.densityDpi}")
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        virtualDisplay = dm.createVirtualDisplay(
            DISPLAY_NAME,
            videoConfig.width,
            videoConfig.height,
            context.resources.displayMetrics.densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        ) ?: throw RecorderError.DisplayError("创建 VirtualDisplay 失败")

        OsrLog.d("VirtualDisplay created ${videoConfig.width}x${videoConfig.height}")
        return virtualDisplay!!.display
    }

    /**
     * 释放 VirtualDisplay。
     * release()：销毁虚拟 Display，不再向 Surface 合成；执行后 display 不可再使用。
     */
    fun release() {
        OsrLog.d("release VirtualDisplay")
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
