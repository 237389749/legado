package io.legado.app.epd

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 文字颜色的「亮度收敛」。
 *
 * 背景：2 级量化（纯黑白）按亮度 127.5 切分，任何亮度 > 0x7F 的文字都会被抹成白 ——
 * 实测书架空态那句 `#c0c0c0` 的提示在 2 级下完全消失。
 *
 * 收敛规则：把「合成到白底后亮度仍 > 0x7F」的文字色压到 #444444。
 * 这样 2 级下变黑（信息保住），4 级下落在 85 那一档（层次还在）。
 *
 * 为什么不改主题：实测给 Activity 主题叠加 `ThemeOverlay` 会让
 * `BottomNavigationView` 在 inflate 时抛 `Resources$NotFoundException: Resource ID #0x0`，
 * 直接崩在启动页。改在视图层做，影响面可控、随墨水屏模式开关。
 */
object EinkPalette {

    /** 允许的最亮文字亮度：超过它，2 级量化就会变白 */
    private const val MAX_TEXT_LUMINANCE = 0x7F

    private const val REPLACEMENT = 0xFF444444.toInt()

    fun clamp(root: View) {
        if (root is TextView) {
            clampText(root)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                clamp(root.getChildAt(i))
            }
        }
    }

    private fun clampText(view: TextView) {
        val color = view.currentTextColor
        val lum = compositeLuminance(color)
        if (lum <= MAX_TEXT_LUMINANCE) return
        try {
            view.setTextColor(REPLACEMENT)
        } catch (ignored: Throwable) {
        }
    }

    /** 把带 alpha 的颜色先合成到白底，再算亮度 */
    fun compositeLuminance(color: Int): Int {
        val a = color ushr 24
        if (a == 255) return EinkGray.luminance(color)
        val r = ((color shr 16 and 0xFF) * a + 255 * (255 - a)) / 255
        val g = ((color shr 8 and 0xFF) * a + 255 * (255 - a)) / 255
        val b = ((color and 0xFF) * a + 255 * (255 - a)) / 255
        return (r * 77 + g * 151 + b * 28) shr 8
    }
}
