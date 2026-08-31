package osp.osr.fbo.source

import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCaptureCallbackTest {

    @Test
    fun `ViewCapture can submit uploaded texture directly`() {
        val methodNames = FrameCaptureCallback::class.java.methods.map { it.name }

        assertTrue(
            "FrameCaptureCallback 缺少纹理直通入口，ViewSource 上传的纹理无法进入编码管线",
            "captureTexture" in methodNames
        )
    }
}
