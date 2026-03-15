package osp.osr.fbo.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import osp.osr.log.OsrLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 📤 使用 PBO（Pixel Buffer Object）将 Bitmap 上传到当前绑定的 GL 纹理。
 *
 * **V2 性能**：Canvas → Bitmap → glTexSubImage2D 是 CPU→GPU 同步路径，1080P 在部分机型上易超 16ms。
 * 通过 PBO 将像素先拷到 GPU 可读的缓冲，再由 glTexSubImage2D(..., null) 从 PBO 取数，便于驱动做异步 DMA。
 *
 * **使用约定**：调用前由调用方 bind 好目标纹理（GL_TEXTURE_2D）；本类只负责向该纹理上传像素。
 * 首帧用 [uploadFirstFrame]，后续帧用 [uploadSubImage] 复用纹理尺寸。
 *
 * **后续优化**：双 PBO 流水线（CPU 填 PBO B 时 GPU 从 PBO A 传输）可进一步降低主线程/GL 线程阻塞。
 */
class PboTextureUploader {

    private var pboId = 0
    private var bufferCapacity = 0
    private var currentWidth = 0
    private var currentHeight = 0

    /**
     * 首帧：分配纹理并上传完整图。当前绑定的纹理应为 GL_TEXTURE_2D。
     */
    fun uploadFirstFrame(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        OsrLog.i("PboTextureUploader: uploadFirstFrame ${w}x${h}")
        ensurePboCapacity(w * h * 4)
        copyBitmapToPboAndUpload(bitmap, w, h, isFullUpload = true)
        currentWidth = w
        currentHeight = h
    }

    /**
     * 后续帧：仅更新像素，纹理尺寸不变。当前绑定的纹理应为 GL_TEXTURE_2D。
     */
    fun uploadSubImage(bitmap: Bitmap) {
        if (currentWidth <= 0 || currentHeight <= 0) {
            uploadFirstFrame(bitmap)
            return
        }
        copyBitmapToPboAndUpload(bitmap, currentWidth, currentHeight, isFullUpload = false)
    }

    private fun ensurePboCapacity(bytes: Int) {
        if (bytes <= bufferCapacity) return
        if (pboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(pboId), 0)
            pboId = 0
        }
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        pboId = ids[0]
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pboId)
        GLES30.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER, bytes, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
        bufferCapacity = bytes
    }

    private fun copyBitmapToPboAndUpload(bitmap: Bitmap, width: Int, height: Int, isFullUpload: Boolean) {
        val size = width * height * 4
        ensurePboCapacity(size)
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        // Bitmap 为 ARGB_8888，转为 GL 期望的 RGBA 顺序
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val p = pixels[i]
            buffer.put((p shr 16 and 0xFF).toByte())  // R
            buffer.put((p shr 8 and 0xFF).toByte())   // G
            buffer.put((p and 0xFF).toByte())         // B
            buffer.put((p shr 24 and 0xFF).toByte()) // A
        }
        buffer.flip()

        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pboId)
        GLES30.glBufferSubData(GLES30.GL_PIXEL_UNPACK_BUFFER, 0, size, buffer)
        if (isFullUpload) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
    }

    fun release() {
        if (pboId != 0) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glDeleteBuffers(1, intArrayOf(pboId), 0)
            pboId = 0
            OsrLog.i("PboTextureUploader: released")
        }
        bufferCapacity = 0
        currentWidth = 0
        currentHeight = 0
    }
}
