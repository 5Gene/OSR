package osp.osr.listener

import java.io.File

/**
 * 👂 录制过程的可选回调
 *
 * 在 DSL 里通过 listener { onStart = { }; onSaved = { file -> } } 按需赋值，
 * 不关心的不写即可，避免实现一整个接口。
 */
class ListenerConfig {
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSaved: ((File) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
}
