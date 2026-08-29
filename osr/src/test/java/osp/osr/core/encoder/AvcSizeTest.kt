package osp.osr.core.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcSizeTest {

    @Test
    fun `1080x1920 stays near original after align`() {
        val (w, h) = clampEncodeSize(1080, 1920)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue(macroblocks(w, h) <= AVC_LEVEL41_MAX_FS)
        // 宏块本来就够：不应无故压得很小
        assertTrue(w >= 1080)
        assertTrue(h >= 1920)
    }

    @Test
    fun `2000x1172 clamps under MaxFS and stays above crude 1920 long-edge`() {
        val (w, h) = clampEncodeSize(2000, 1172)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue(macroblocks(w, h) <= AVC_LEVEL41_MAX_FS)
        // 等比贴近 8192，应明显高于「长边硬压 1920」后的面积
        val crudeLongEdge1920Area = 1920 * (1172 * 1920 / 2000)
        assertTrue(w * h > crudeLongEdge1920Area * 0.7)
    }

    @Test
    fun `alignEncodeSize only aligns to 16 without MaxFS shrink`() {
        val (w, h) = alignEncodeSize(1440, 3168)
        assertEquals(1440, w)
        assertEquals(3168, h)
        assertTrue(macroblocks(w, h) > AVC_LEVEL41_MAX_FS)
    }

    @Test
    fun `align16 rounds up to multiple of 16`() {
        assertEquals(16, 1.align16())
        assertEquals(1088, 1080.align16())
        assertEquals(1184, 1172.align16())
    }

    @Test
    fun `fallback720p respects orientation`() {
        assertEquals(720 to 1280, fallback720p(1080, 1920))
        assertEquals(1280 to 720, fallback720p(2000, 1172))
    }

    @Test
    fun `virtualDisplayDensityDpi scales with encode width`() {
        assertEquals(240, virtualDisplayDensityDpi(480, 1080, 2160))
        assertEquals(1, virtualDisplayDensityDpi(480, 0, 1080))
    }
}
