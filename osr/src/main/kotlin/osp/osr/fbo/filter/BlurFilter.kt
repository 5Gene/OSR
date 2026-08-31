package osp.osr.fbo.filter

import android.opengl.GLES30
import osp.osr.fbo.BlurConfig
import osp.osr.fbo.gl.FboPool
import osp.osr.fbo.gl.GlUtil

/**
 * 🌫️ 两趟高斯模糊（水平 + 垂直），ping-pong 两个 FBO。
 *
 * **为啥要两趟**：二维高斯模糊可以拆成「先水平再垂直」两次一维模糊，采样数从 O(r²) 变成 O(r)，性能好很多。
 *
 * **数据流**：FilterPipeline.render 调我们 apply(inputTexture) →
 * 第一趟：绑定 fbo[0]，用 programH 把 inputTexture 画进去（水平方向加权采样）→ 得到 texIds[0]
 * 第二趟：绑定 fbo[1]，用 programV 把 texIds[0] 画进去（垂直方向加权采样）→ 得到 texIds[1]，返回给管线下一格。
 */
class BlurFilter(private val config: BlurConfig) : Filter {

    private var programH = 0
    private var programV = 0
    private val fboIds = IntArray(2)
    private val texIds = IntArray(2)
    private var width = 0
    private var height = 0
    private var fboPool: FboPool? = null

    override fun init(width: Int, height: Int, pool: FboPool) {
        this.width = width
        this.height = height
        fboPool = pool
        programH = GlUtil.createProgram(VERTEX_SHADER, fragmentShader(true))
        programV = GlUtil.createProgram(VERTEX_SHADER, fragmentShader(false))
        repeat(2) { i ->
            val pair = pool.acquire(width, height)
            fboIds[i] = pair.first
            texIds[i] = pair.second
        }
    }

    override fun apply(inputTexture: Int): Int {
        // 第一趟：水平方向模糊，结果写到 fbo[0] 的纹理
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
        GLES30.glViewport(0, 0, width, height)
        GlUtil.drawTextureWithProgram(programH, inputTexture, config.radius, width, height)

        // 第二趟：垂直方向模糊，读 texIds[0]，结果写到 fbo[1] 的纹理
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[1])
        GlUtil.drawTextureWithProgram(programV, texIds[0], config.radius, width, height)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return texIds[1]
    }

    override fun release() {
        GLES30.glDeleteProgram(programH)
        GLES30.glDeleteProgram(programV)
        fboPool?.let { pool ->
            repeat(2) { i -> pool.release(fboIds[i], texIds[i], width, height) }
        } ?: run {
            GLES30.glDeleteFramebuffers(2, fboIds, 0)
            GLES30.glDeleteTextures(2, texIds, 0)
        }
        fboPool = null
    }

    /** 水平为 true 时沿 x 方向采样，为 false 时沿 y 方向；权重是高斯近似，中心大两边小 */
    private fun fragmentShader(horizontal: Boolean): String {
        val dir = if (horizontal) "vec2(1.0/uResolution.x, 0.0)" else "vec2(0.0, 1.0/uResolution.y)"
        return """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uRadius;
            uniform vec2 uResolution;
            void main() {
                vec2 d = $dir;
                vec4 c = texture2D(uTexture, vTexCoord) * 0.2270270270;
                float r = clamp(uRadius, 1.0, 25.0);
                for (float i = 1.0; i <= 25.0; i++) {
                    if (i > r) break;
                    float w = 0.3162162162 * exp(-0.5 * (i*i) / (r*r));
                    c += texture2D(uTexture, vTexCoord + d * i) * w;
                    c += texture2D(uTexture, vTexCoord - d * i) * w;
                }
                gl_FragColor = c;
            }
        """.trimIndent()
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
    }
}
