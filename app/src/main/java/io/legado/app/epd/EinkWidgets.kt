package io.legado.app.epd

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import io.legado.app.R

/**
 * 把「靠颜色表达状态」的控件换成「靠形状/位置表达状态」。
 *
 * 背景：2 级量化之后只剩纯黑与纯白，色相全部丢失。Material 的开关在
 * 默认主题下是「灰轨道 + 彩色滑块 → 彩色轨道 + 彩色滑块」，量化后
 * **开 = 一团黑、关 = 完全看不见**（实测截图），因为关态的轨道和滑块
 * 都被判成了白色。灰阶/色相在这里不是审美问题，是信息丢失。
 *
 * 替代方案不引入任何新控件，只替换 drawable：
 *
 * | 状态 | 轨道 | 滑块 |
 * |---|---|---|
 * | 开   | 纯黑实心 | 白色 |
 * | 关   | 白底黑框（空心） | 黑色 |
 *
 * 于是「填充差（实心/空心）」+「位置差（左/右）」两个线索都不依赖颜色，
 * 1bpp 下也不会丢。偏好设置里的 `android.widget.Switch`、列表里的
 * `ThemeSwitch`(SwitchCompat)、Material `SwitchMaterial` 一并覆盖。
 */
object EinkWidgets {

    private const val TAG = "EinkRefresh"

    fun apply(root: View) {
        when (root) {
            is SwitchCompat -> styleSwitchCompat(root)
            is android.widget.Switch -> stylePlatformSwitch(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                apply(root.getChildAt(i))
            }
        }
    }

    /** androidx / Material 的开关（[SwitchCompat] 与其子类 SwitchMaterial） */
    private fun styleSwitchCompat(sw: SwitchCompat) {
        if (sw.getTag(R.id.eink_widget_styled) != null) return
        try {
            sw.thumbTintList = null
            sw.trackTintList = null
            sw.setTrackResource(R.drawable.eink_switch_track)
            sw.setThumbResource(R.drawable.eink_switch_thumb)
            sw.switchMinWidth = 0
            syncState(sw.drawableState, sw.thumbDrawable, sw.trackDrawable)
            sw.jumpDrawablesToCurrentState()
            sw.setTag(R.id.eink_widget_styled, true)
            Log.i(TAG, "switch(compat) styled checked=${sw.isChecked}")
        } catch (e: Throwable) {
            Log.w(TAG, "switch(compat) style failed", e)
        }
    }

    /** 偏好设置（androidx.preference）用的是平台 [android.widget.Switch] */
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun stylePlatformSwitch(sw: android.widget.Switch) {
        if (sw.getTag(R.id.eink_widget_styled) != null) return
        try {
            sw.thumbTintList = null
            sw.trackTintList = null
            sw.trackDrawable = ContextCompat.getDrawable(sw.context, R.drawable.eink_switch_track)
            sw.thumbDrawable = ContextCompat.getDrawable(sw.context, R.drawable.eink_switch_thumb)
            syncState(sw.drawableState, sw.thumbDrawable, sw.trackDrawable)
            sw.jumpDrawablesToCurrentState()
            sw.setTag(R.id.eink_widget_styled, true)
            Log.i(TAG, "switch(platform) styled checked=${sw.isChecked}")
        } catch (e: Throwable) {
            Log.w(TAG, "switch(platform) style failed", e)
        }
    }

    /**
     * 换 drawable 的时候，「开」状态通常早就设置过了，`drawableStateChanged()`
     * 不会因为换了 drawable 再跑一遍——不手动同步一次，选择器会停在默认分支上，
     * 于是「开」画出黑色滑块贴在黑色轨道上 = 又变成一团黑。
     */
    private fun syncState(state: IntArray, vararg drawables: android.graphics.drawable.Drawable?) {
        drawables.forEach { d ->
            d?.let {
                it.state = state
                it.jumpToCurrentState()
            }
        }
    }

    /**
     * 状态圆点（书源列表的「探索可用」标记）：实心 = 开，空心 = 关。
     *
     * 原来用 [android.graphics.Color.GREEN] / [android.graphics.Color.RED]：
     * 绿色的合成亮度约 150，2 级量化下直接变白 → 整个点消失；
     * 红色约 76 → 变黑。于是「启用」看不见、「禁用」看得见，语义正好反了。
     */
    fun stateDot(iv: android.widget.ImageView, on: Boolean) {
        iv.setImageResource(if (on) R.drawable.eink_dot_filled else R.drawable.eink_dot_hollow)
        // 注意：CircleImageView.setColorFilter 的参数是非空注解，传 null 会在运行时 NPE
        iv.clearColorFilter()
        iv.setTag(R.id.eink_state_on, on)
    }
}
