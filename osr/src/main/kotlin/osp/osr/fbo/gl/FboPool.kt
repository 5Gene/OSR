package osp.osr.fbo.gl

import android.opengl.GLES30
import osp.osr.log.OsrLog
import java.util.ArrayDeque

/**
 * 🏊 按尺寸复用的 FBO 池，供滤镜链申请/归还，减少多滤镜场景下的显存与创建开销。
 *
 * **使用约定**：仅在同一 GL 线程内使用；acquire 与 release 成对调用，release 时归还的 FBO 必须来自本池。
 */
class FboPool {

    private data class Entry(val fboId: Int, val texId: Int, val width: Int, val height: Int)

    private val pool = ArrayDeque<Entry>()

    /**
     * 申请一对 (FBO ID, 纹理 ID)，尺寸为 width×height。若池中有同尺寸则复用，否则新建。
     */
    fun acquire(width: Int, height: Int): Pair<Int, Int> {
        val it = pool.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.width == width && e.height == height) {
                it.remove()
                return e.fboId to e.texId
            }
        }
        return GlUtil.createFboWithTexture(width, height)
    }

    /**
     * 归还一对 (FBO ID, 纹理 ID)，尺寸需与申请时一致，供后续同尺寸申请复用。
     */
    fun release(fboId: Int, texId: Int, width: Int, height: Int) {
        if (fboId == 0 || texId == 0) return
        pool.add(Entry(fboId, texId, width, height))
    }

    /**
     * 释放池中所有 FBO/纹理，通常在 FilterPipeline.release 时调用。
     */
    fun releaseAll() {
        var n = 0
        while (pool.isNotEmpty()) {
            val e = pool.pollFirst() ?: break
            GLES30.glDeleteFramebuffers(1, intArrayOf(e.fboId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(e.texId), 0)
            n++
        }
        OsrLog.i("FboPool: releaseAll done, recycled $n FBO(s)")
    }
}
