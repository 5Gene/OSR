package osp.osr.log

import android.util.Log

/**
 * OSR 统一日志：debug / info / warn / error，Tag 固定为 "OSR"。
 *
 * - **i**：重要步骤与节点（如流程入口/出口、状态切换、录制开始/结束、保存路径、释放完成）。
 * - **d**：调试细节（如循环内日志、子步骤、中间变量）。
 */
object OsrLog {

    private const val TAG = "OSR"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
    }
}
