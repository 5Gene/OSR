package osp.osr.pres

import android.app.Presentation
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import osp.osr.RecorderSession
import osp.osr.log.OsrLog

/**
 * 🖥️ Presentation 生命周期管理
 *
 * [show] 必须在主线程执行（Android 要求），所以用 withContext(Dispatchers.Main) 挂起并切换，
 * 这样异常能同步抛回 prepare()，不会丢在 Handler 的异步 post 里。
 * [dismiss] 只做“投递到主线程关闭”，不需要等待结果，所以沿用 Handler.post，且 release() 不能是 suspend。
 */
internal class PresentationController(
    private val factory: PresentationFactory
) {

    private var presentation: Presentation? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 在主线程创建并 show Presentation，异常会传播给调用方 */
    suspend fun show(display: Display, session: RecorderSession) {
        OsrLog.d("create and show Presentation on main thread")
        withContext(Dispatchers.Main) {
            val p = factory(display, session)
            p.show()
            presentation = p
            OsrLog.d("Presentation shown")
        }
    }

    /** 投递到主线程 dismiss，fire-and-forget（release 链路非 suspend） */
    fun dismiss() {
        OsrLog.d("post dismiss Presentation to main thread")
        mainHandler.post {
            try {
                presentation?.dismiss()
            } catch (_: Exception) {
            }
            presentation = null
            OsrLog.d("Presentation dismissed")
        }
    }
}
