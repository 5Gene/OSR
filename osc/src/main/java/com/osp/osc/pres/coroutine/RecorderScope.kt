package com.osp.osc.pres.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class RecorderScope {

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    fun cancel() {
        scope.cancel()
    }
}
