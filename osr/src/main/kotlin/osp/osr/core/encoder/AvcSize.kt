package osp.osr.core.encoder

import kotlin.math.sqrt

/** AVC Level 4.1 宏块上限（MaxFS） */
internal const val AVC_LEVEL41_MAX_FS = 8192

/** 硬编要求宽高为 16 的倍数 */
internal fun Int.align16(): Int = ((this + 15) / 16 * 16).coerceAtLeast(16)

internal fun macroblocks(w: Int, h: Int): Int = (w / 16) * (h / 16)

/** 仅 16 对齐，不做 MaxFS 缩小（尽量保屏部分辨率） */
internal fun alignEncodeSize(width: Int, height: Int): Pair<Int, Int> =
    width.align16() to height.align16()

/**
 * 将编码尺寸压到 AVC Level 4.1 宏块上限内：先 16 对齐；超限则按 sqrt(maxFs/mb) 等比缩小再对齐。
 * 尽量贴近上限，避免粗暴压长边 1920。
 */
internal fun clampEncodeSize(
    width: Int,
    height: Int,
    maxFs: Int = AVC_LEVEL41_MAX_FS
): Pair<Int, Int> {
    var w = width.align16()
    var h = height.align16()
    if (macroblocks(w, h) <= maxFs) return w to h
    val scale = sqrt(maxFs.toDouble() / macroblocks(w, h))
    w = (w * scale).toInt().align16()
    h = (h * scale).toInt().align16()
    while (macroblocks(w, h) > maxFs) {
        if (w >= h && w > 16) w -= 16 else if (h > 16) h -= 16 else break
    }
    return w to h
}

/** configure 仍失败时的兜底分辨率（竖屏 720×1280 / 横屏 1280×720） */
internal fun fallback720p(width: Int, height: Int): Pair<Int, Int> =
    if (height >= width) 720 to 1280 else 1280 to 720

/** 编码画布缩小后，VD 密度同比缩小，避免小画布 + 原 dpi 把 UI「放大」 */
internal fun virtualDisplayDensityDpi(deviceDpi: Int, encodeW: Int, screenW: Int): Int =
    (deviceDpi * encodeW / screenW.coerceAtLeast(1)).coerceAtLeast(1)
