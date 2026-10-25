package io.legado.app.epd

import android.graphics.Bitmap

/**
 * 灰阶量化。
 *
 * 墨水屏真正能稳定显示的灰阶是有限的：DU 只有 1bpp（纯黑白），
 * GC16 是 4bpp（16 级）。而 App 里的图片是 8bpp 的连续灰，
 * 直接交给面板就是「面板替你随机抖动」，结果不可控、还带残影。
 *
 * 这里做的是**自己抖动**：用 8×8 Bayer 有序抖动把 8bpp 压到 2/4/16 级，
 * 输出的图案是确定的、可复现的，看起来像网点，而不是噪点。
 *
 * 纯函数部分只吃 IntArray，不碰 Bitmap，方便单测。
 */
object EinkGray {

    const val LEVELS_16 = 16
    const val LEVELS_4 = 4
    const val LEVELS_2 = 2

    /** 8×8 Bayer 阈值矩阵，值域 0..63 */
    private val BAYER8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /**
     * 原地量化 ARGB 像素。
     *
     * @param levels 2 / 4 / 16，其余值会被夹到最近一档
     * @param dither false 时退化为直接四舍五入（会出硬边）
     */
    fun quantizeArgb(pixels: IntArray, width: Int, levels: Int, dither: Boolean = true) {
        val steps = levels.coerceIn(2, 256) - 1
        val step = 255.0 / steps
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = luminance(c)
            val threshold = if (dither) {
                val b = BAYER8[(i % width and 7) + ((i / width and 7) shl 3)]
                (b + 0.5) / 64.0
            } else {
                0.5
            }
            val q = (lum / step + threshold).toInt().coerceIn(0, steps)
            val g = (q * step).toInt().coerceIn(0, 255)
            pixels[i] = (c and -0x1000000) or (g shl 16) or (g shl 8) or g
        }
    }

    /** 生成一张量化后的新位图；原图不变。 */
    fun quantize(src: Bitmap, levels: Int, dither: Boolean = true): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        quantizeArgb(pixels, w, levels, dither)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** 亮度：整数近似，避免每像素浮点除法 */
    fun luminance(color: Int): Int {
        val r = color shr 16 and 0xFF
        val g = color shr 8 and 0xFF
        val b = color and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }
}
