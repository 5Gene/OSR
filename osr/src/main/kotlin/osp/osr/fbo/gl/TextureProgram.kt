package osp.osr.fbo.gl

import android.opengl.GLES30
import osp.osr.log.OsrLog

/**
 * 🖼️ 全屏纹理四边形 Program（小白友好）
 *
 * **干啥用的**：把「一张 2D 纹理」画成铺满整个屏幕（或当前 FBO）的四边形。
 * 在 FBO 管线里，这是**最后一步**：滤镜链输出的纹理，通过我画到编码器的 Surface 上，
 * 编码器就能把这一帧编码成 H.264。
 *
 * **数据流**：
 * - 谁造我：FrameCaptureRenderer.initGL()
 * - 谁调 draw：FrameCaptureRenderer.captureFrame()，传入的是 FilterPipeline.render() 返回的 outputTex
 * - draw 内部：GlUtil.drawTexture(program, textureId)，把 textureId 贴到当前绑定的绘制目标（已通过 EglSurfaceManager.makeCurrent 切到编码器 Surface）
 *
 * **Shader 在干啥**：
 * - 顶点：直接把 aPosition/aTexCoord 传下去，不做变换（因为 QUAD 已经是 NDC 全屏了）
 * - 片元：根据 vTexCoord 从 uTexture 采样，输出 gl_FragColor，相当于「贴图」
 */

class TextureProgram {

    private val program: Int

    init {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        OsrLog.i("TextureProgram: created program=$program")
    }

    /**
     * 把 [textureId] 对应的纹理画满当前绑定的 framebuffer（在 captureFrame 里就是编码器 Surface）。
     * 内部会调 GlUtil.drawTexture(program, textureId)。
     */
    fun draw(textureId: Int) {
        GlUtil.drawTexture(program, textureId)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        OsrLog.i("TextureProgram: released")
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
