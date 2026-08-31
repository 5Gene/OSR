package osp.osr.core.util

import osp.osr.listener.ListenerConfig
import osp.osr.model.RecorderError
import java.io.File

/**
 * Listener 通知器：统一 runCatching 包裹，防止用户回调异常传播到 Session 内部。
 *
 * 两个 RecorderSession（Presentation / FBO）共享此类，避免重复 try-catch。
 */
internal class SessionNotifier(private val listener: ListenerConfig) {
    fun notifyStart() {
        runCatching { listener.onStart?.invoke() }
    }

    fun notifyStop() {
        runCatching { listener.onStop?.invoke() }
    }

    fun notifySaved(file: File) {
        runCatching { listener.onSaved?.invoke(file) }
    }

    fun notifyError(error: RecorderError) {
        runCatching { listener.onError?.invoke(error) }
    }
}
