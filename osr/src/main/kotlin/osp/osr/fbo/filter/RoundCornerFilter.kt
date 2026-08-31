package osp.osr.fbo.filter

import android.opengl.GLES30
import osp.osr.fbo.RoundCornerConfig
import osp.osr.fbo.gl.FboPool
import osp.osr.fbo.gl.GlUtil

/**
 * 📐 圆角裁剪滤镜：按像素到「最近角」的距离做 alpha，用 smoothstep 抗锯齿。
 *
 * **思路**：每个像素找到矩形内离它最近的那个「角点」（clamp 到圆角矩形内），
 * 距离小于 radius 的就在圆角弧内，用 smoothstep(radius-1, radius+1, dist) 做柔和边缘。
 *
 * **数据流**：FilterPipeline.render 调 apply(inputTexture) →
 * 传 uRadius、uResolution → 片元里算 dist、alpha，gl_FragColor = color * alpha →
 * 结果画到我们 FBO，返回 texId。
 */
class RoundCornerFilter(private val config: RoundCornerConfig) : Filter {

    private var program = 0
    private var fboId = 0
    private var texId = 0
    private var width = 0
    private var height = 0
    private var fboPool: FboPool? = null

    override fun init(width: Int, height: Int, pool: FboPool) {
        this.width = width
        this.height = height
        fboPool = pool
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val pair = pool.acquire(width, height)
        fboId = pair.first
        texId = pair.second
    }

    override fun apply(inputTexture: Int): Int {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)

        val radiusLoc = GLES30.glGetUniformLocation(program, "uRadius")
        val resLoc = GLES30.glGetUniformLocation(program, "uResolution")
        GLES30.glUniform1f(radiusLoc, config.radius)
        GLES30.glUniform2f(resLoc, width.toFloat(), height.toFloat())

        GlUtil.drawTexture(program, inputTexture)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return texId
    }

    override fun release() {
        GLES30.glDeleteProgram(program)
        fboPool?.release(fboId, texId, width, height)
            ?: run {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
            }
        fboPool = null
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
            uniform float uRadius;
            uniform vec2 uResolution;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                vec2 pixelPos = vTexCoord * uResolution;
                vec2 minCorner = vec2(uRadius);
                vec2 maxCorner = uResolution - vec2(uRadius);
                vec2 cornerPos = clamp(pixelPos, minCorner, maxCorner);
                float dist = length(pixelPos - cornerPos);
                float alpha = 1.0 - smoothstep(uRadius - 1.0, uRadius + 1.0, dist);
                gl_FragColor = vec4(color.rgb, color.a * alpha);
            }
        """
    }
}
