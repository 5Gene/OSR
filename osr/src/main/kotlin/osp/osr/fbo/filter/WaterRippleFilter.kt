package osp.osr.fbo.filter

import android.opengl.GLES30
import osp.osr.fbo.WaterRippleConfig
import osp.osr.fbo.gl.FboPool
import osp.osr.fbo.gl.GlUtil

/**
 * 🌊 水波纹滤镜：用 sin 对 UV 做周期偏移，采样时「抖动」一下就有波纹感。
 *
 * **数据流**：FilterPipeline.render 调 apply(inputTexture) →
 * 传 uTime（自 init 起的秒数）、uAmplitude/uFrequency/uSpeed（DSL 里配的）→
 * 片元里 uv.y += sin(uv.x * freq + time * speed) * amp，再 texture2D(uTexture, uv) →
 * 结果画到我们自己的 FBO，返回 texId 给下一格。
 */
class WaterRippleFilter(private val config: WaterRippleConfig) : Filter {

    private var program = 0
    private var fboId = 0
    private var texId = 0
    private var width = 0
    private var height = 0
    private var startTimeNs = 0L
    private var fboPool: FboPool? = null

    override fun init(width: Int, height: Int, pool: FboPool) {
        this.width = width
        this.height = height
        fboPool = pool
        startTimeNs = System.nanoTime()
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val pair = pool.acquire(width, height)
        fboId = pair.first
        texId = pair.second
    }

    override fun apply(inputTexture: Int): Int {
        val timeSec = (System.nanoTime() - startTimeNs) / 1_000_000_000f
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)

        val timeLoc = GLES30.glGetUniformLocation(program, "uTime")
        val ampLoc = GLES30.glGetUniformLocation(program, "uAmplitude")
        val freqLoc = GLES30.glGetUniformLocation(program, "uFrequency")
        val speedLoc = GLES30.glGetUniformLocation(program, "uSpeed")
        GLES30.glUniform1f(timeLoc, timeSec)
        GLES30.glUniform1f(ampLoc, config.amplitude)
        GLES30.glUniform1f(freqLoc, config.frequency)
        GLES30.glUniform1f(speedLoc, config.speed)

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
            uniform float uTime;
            uniform float uAmplitude;
            uniform float uFrequency;
            uniform float uSpeed;
            void main() {
                vec2 uv = vTexCoord;
                uv.y += sin(uv.x * uFrequency + uTime * uSpeed) * uAmplitude;
                gl_FragColor = texture2D(uTexture, uv);
            }
        """
    }
}
