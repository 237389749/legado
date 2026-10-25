package io.legado.app.epd

import android.graphics.Bitmap

/**
 * 灰阶量化。
 *
 * 墨水屏真正能稳定显示的灰阶是有限的：DU 只有 1bpp（纯黑白），
 * GC16 是 4bpp（16 级）。而 App 里的图片是 8bpp 的连续灰，
 * 直接交给面板就是「面板替你随机抖动」，结果不可控、还带残影。
 *
 * 这里做的是**自己抖动**：8×8 Bayer 有序抖动，把 8bpp 压到 2/4/16 级，
 * 输出图案确定、可复现，看起来像网点而不是噪点。
 *
 * 性能：全屏 1072×1448 是 1.5M 像素，逐像素做浮点运算是 80–100ms（实测），
 * 所以这里用 **64 行 × 256 列的查找表**（每行对应一个 Bayer 位置），
 * 单像素只剩「取亮度 → 查表 → 写回」，压到 10ms 量级。
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

    private val lutCache = HashMap<Long, Array<IntArray>>()

    /**
     * 查找表：rows[pos][lum] = 量化后的灰度值。
     *
     * pos 是 8×8 网格里的位置，dither=false 时只用 rows[0]。
     */
    fun lut(levels: Int, dither: Boolean): Array<IntArray> {
        val n = levels.coerceIn(2, 256)
        val key = n.toLong() shl 1 or (if (dither) 1L else 0L)
        lutCache[key]?.let { return it }
        val steps = n - 1
        val step = 255.0 / steps
        val rows = Array(64) { pos ->
            val b = if (dither) (BAYER8[pos] + 0.5) / 64.0 else 0.5
            IntArray(256) { lum ->
                val q = (lum / step + b).toInt().coerceIn(0, steps)
                (q * step).toInt().coerceIn(0, 255)
            }
        }
        lutCache[key] = rows
        return rows
    }

    /**
     * 原地量化 ARGB 像素。
     *
     * @param levels 2 / 4 / 16，其余值会被夹到范围内
     * @param dither false 时退化为四舍五入（文字用），true 时抖动（图片用）
     */
    fun quantizeArgb(pixels: IntArray, width: Int, levels: Int, dither: Boolean = true) {
        if (width <= 0 || pixels.isEmpty()) return
        val rows = lut(levels, dither)
        val height = pixels.size / width
        var i = 0
        for (y in 0 until height) {
            val rowBase = (y and 7) shl 3
            for (x in 0 until width) {
                val c = pixels[i]
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val b = c and 0xFF
                val lum = (r * 77 + g * 151 + b * 28) shr 8
                val q = if (dither) rows[rowBase + (x and 7)][lum] else rows[0][lum]
                pixels[i] = (c and -0x1000000) or (q shl 16) or (q shl 8) or q
                i++
            }
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
