package osp.osr.fbo.gl

import android.opengl.GLES30
import osp.osr.log.OsrLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 🛠️ OpenGL ES 工具类（小白友好版）
 *
 * **干啥用的**：帮我们干三件大事——
 * 1️⃣ 把 GLSL 源码编译成 shader，再链成 program（GPU 上的「小程序」）
 * 2️⃣ 创建 FBO + 纹理，用来「离屏画图」（不直接画到屏幕）
 * 3️⃣ 画一个铺满屏幕的四边形，把一张纹理贴上去（滤镜、最终输出都要用）
 *
 * **数据流**：谁在用我？
 * - [TextureProgram] 的 init 里会调 createProgram，draw 里会调 drawTexture
 * - [BlurFilter]/[WaterRippleFilter]/[RoundCornerFilter] 的 init 里调 createFboWithTexture，apply 里调 drawTexture
 * - [FrameCaptureRenderer] 通过 TextureProgram.draw 把最终画面画到编码器 Surface
 */
object GlUtil {

    /**
     * 📐 全屏四边形的顶点数据（NDC 坐标 + UV）
     * 格式：x, y, u, v 一组。四个点 = 两个三角形拼成的矩形，刚好铺满 -1～1 的裁剪空间。
     * 为啥用 -1～1？OpenGL 的 NDC（标准化设备坐标）规定：在这个范围里的才会被画出来。
     */
    private val QUAD_COORDS = floatArrayOf(
        -1f, -1f, 0f, 0f,  // 左下，UV(0,0)
        1f, -1f, 1f, 0f,  // 右下，UV(1,0)
        -1f, 1f, 0f, 1f,  // 左上，UV(0,1)
        1f, 1f, 1f, 1f   // 右上，UV(1,1)
    )

    /** 转成 Native 用的 FloatBuffer，GPU 读顶点数据时要这种格式 */
    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD_COORDS)
        .also { it.position(0) }

    /**
     * 🔗 创建并链好一个 GL Program（顶点 + 片元 shader）
     *
     * **为啥要两个 shader**：顶点 shader 管「每个顶点在哪」、片元 shader 管「每个像素啥颜色」。
     * 链在一起后，GPU 才知道：先算顶点 → 再光栅化 → 再算每个像素。
     *
     * **流程**：
     * 1. compileShader 把字符串编译成顶点/片元 shader
     * 2. glCreateProgram + glAttachShader + glLinkProgram 链成 program
     * 3. 链完后 shader 可以删掉（program 里已经拷了一份），省资源
     *
     * **返回**：program 的 ID，后面 glUseProgram(id) 就会用这个程序来画。
     * **谁调我**：TextureProgram 的 init、各 Filter 的 init（BlurFilter/WaterRippleFilter/RoundCornerFilter）
     */
    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    /**
     * 🖼️ 创建「FBO + 一张绑在 FBO 上的纹理」（一对好基友）
     *
     * **FBO 是啥**：Framebuffer Object，相当于一块离屏画布。我们往上面画，画完可以当纹理用，
     * 而不是直接画到屏幕。为啥要离屏？因为要做模糊/水波纹等效果，需要多遍绘制，中间结果要存着。
     *
     * **流程**：
     * 1. glGenTextures：生成一张空纹理，glTexImage2D 分配 width×height 的 RGBA 内存
     * 2. glTexParameteri：设置滤波（LINEAR）和边缘（CLAMP_TO_EDGE），采样时不会越界
     * 3. glGenFramebuffers + glFramebufferTexture2D：创建 FBO，并把纹理挂到 COLOR_ATTACHMENT0
     * 4. glCheckFramebufferStatus：检查 FBO 是否完整（不完整就不能用）
     * 5. glBindFramebuffer(0)：解绑，避免影响后续其他代码
     *
     * **返回**：Pair(FBO的ID, 纹理的ID)。后面谁要「画到这块画布」就 bind 这个 FBO；要「用这张图」就 bind 这个纹理。
     * **谁调我**：FrameCaptureRenderer.initGL（存一帧用的 FBO）、各 Filter.init（模糊/水波纹/圆角各自的中间 FBO）
     */
    fun createFboWithTexture(width: Int, height: Int): Pair<Int, Int> {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texIds[0], 0
        )
        val fbStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fbStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            OsrLog.e("FBO incomplete: $fbStatus")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return fboIds[0] to texIds[0]
    }

    /**
     * 🎨 用指定 program 把一张纹理画成「全屏四边形」
     *
     * **为啥要全屏**：我们要的是「整张图铺满输出」，不管是画到另一个 FBO 还是画到编码器 Surface，
     * 都是「输入纹理 → 输出一整屏」，所以用固定的四个顶点 + 一张纹理就够了。
     *
     * **流程**：
     * 1. glUseProgram：之后所有绘制都用这个 program（顶点/片元逻辑就定下来了）
     * 2. 取 attribute/uniform 的 location：aPosition/aTexCoord 是顶点属性，uTexture 是纹理采样器
     * 3. glActiveTexture + glBindTexture：把纹理绑到 0 号单元，glUniform1i(sampler, 0) 告诉 shader「去 0 号单元采样」
     * 4. quadBuffer 分别给 position 和 texCoord 用：stride=16 表示每 4 个 float 一组，position 用前两个，texCoord 用后两个
     * 5. glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)：画 4 个顶点，组成 2 个三角形，拼成矩形
     *
     * **之后发生啥**：GPU 执行这个 program 的顶点 shader → 光栅化 → 片元 shader 对每个像素采样 uTexture，
     * 结果写到当前绑定的绘制目标（可能是 FBO，也可能是 EglSurfaceManager 的编码器 Surface）。
     *
     * **谁调我**：TextureProgram.draw、BlurFilter/WaterRippleFilter/RoundCornerFilter 的 apply 里
     */
    fun drawTexture(program: Int, textureId: Int) {
        GLES30.glUseProgram(program)
        val posLoc = GLES30.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        val samplerLoc = GLES30.glGetUniformLocation(program, "uTexture")

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(samplerLoc, 0)

        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(texLoc)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(texLoc)
    }

    /**
     * 模糊滤镜专用：先设好 uRadius、uResolution，再调 drawTexture。
     * 谁调我：BlurFilter.apply（水平/垂直两趟）
     */
    fun drawTextureWithProgram(program: Int, textureId: Int, radius: Float, width: Int, height: Int) {
        GLES30.glUseProgram(program)
        val radiusLoc = GLES30.glGetUniformLocation(program, "uRadius")
        val resLoc = GLES30.glGetUniformLocation(program, "uResolution")
        GLES30.glUniform1f(radiusLoc, radius)
        GLES30.glUniform2f(resLoc, width.toFloat(), height.toFloat())
        drawTexture(program, textureId)
    }

    /**
     * 📜 把 GLSL 源码编译成一个 shader（顶点或片元）
     * 失败会抛异常并带上编译器给的错误日志，方便排查。
     * 谁调我：仅被 createProgram 内部使用。
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }
}
