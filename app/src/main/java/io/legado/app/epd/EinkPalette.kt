package io.legado.app.epd

import android.util.Log
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

    /**
     * 墨水屏模式下统一用纯黑。
     *
     * 判据不是「亮度超过 127 会消失」，而是「墨水屏上不应该存在灰色文字」：
     * DU 只有 2 色，靠灰度表达层次本来就会丢；被判成 85 的灰字，
     * 在对话框这类**不经过帧缓冲量化**的窗口里会原样显示成灰色。
     *
     * 所以这里的规则是：任何非纯黑的文字都拉回纯黑，层次交给字号 / 字重 / 间距。
     */
    private const val REPLACEMENT = 0xFF000000.toInt()

    private const val TAG = "EinkRefresh"
    private const val LOG_LIMIT = 80
    private var logged = 0

    /** 诊断用：新一轮扫描时重置日志配额 */
    fun resetLog() {
        logged = 0
    }

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
        val viewAlpha = effectiveAlpha(view)
        // 完全透明的（隐藏 / 正在淡出）不动，否则会把动画中的元素钉死
        if (viewAlpha < 0.05f) return
        // 已经是纯黑不透明就不用写，避免每次布局都触发重绘
        if (color == REPLACEMENT && viewAlpha >= 1f) return
        val alpha = ((color ushr 24) / 255f * viewAlpha).coerceIn(0f, 1f)
        if (logged < LOG_LIMIT) {
            logged++
            Log.d(
                TAG,
                "clamp tv=${view.javaClass.simpleName} color=#${Integer.toHexString(color)} " +
                    "viewAlpha=$viewAlpha lum=${compositeLuminance(color, alpha)} " +
                    "text=\"${view.text.take(10)}\""
            )
        }
        try {
            // 只改颜色不够：父链 alpha 会让纯黑又被冲淡，必须一起恢复
            if (viewAlpha < 1f) view.alpha = 1f
            view.setTextColor(REPLACEMENT)
        } catch (ignored: Throwable) {
        }
    }

    /** 视图链上的有效 alpha（View.alpha 会逐层相乘） */
    private fun effectiveAlpha(view: View): Float {
        var alpha = 1f
        var cur: View? = view
        while (cur != null) {
            alpha *= cur.alpha
            if (alpha <= 0.01f) return 0f
            cur = cur.parent as? View
        }
        return alpha
    }

    /** 把带 alpha 的颜色合成到白底，再算亮度 */
    fun compositeLuminance(color: Int): Int = compositeLuminance(color, (color ushr 24) / 255f)

    fun compositeLuminance(color: Int, alpha: Float): Int {
        if (alpha >= 1f) return EinkGray.luminance(color)
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val r = ((color shr 16 and 0xFF) * a + 255 * (255 - a)) / 255
        val g = ((color shr 8 and 0xFF) * a + 255 * (255 - a)) / 255
        val b = ((color and 0xFF) * a + 255 * (255 - a)) / 255
        return (r * 77 + g * 151 + b * 28) shr 8
    }
}
