package io.legado.app.epd

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.PopupWindow
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.setBackgroundKeepPadding
import java.util.WeakHashMap

/**
 * 弹出菜单的黑白化。
 *
 * **为什么需要单独一层**：`PopupWindow` 和 AppCompat 的 menu 弹窗都是**独立 window**，
 * 既不经过 `android.R.id.content` 的帧缓冲量化（[EinkFrameHost]），
 * 也不在 `EinkPalette` 的扫描范围里——活动页/对话框做完了，下拉菜单还是原样：
 *
 * | 来源 | 在 2 级世界里的样子 |
 * |---|---|
 * | `@color/background_menu` = `#EEEEEE` 面板 | 一片浅灰 |
 * | `@color/menu_color_default` = `#383838` 文字 | 比正文浅一档的灰 |
 * | `applyMd3PopupStyle()` 的 8dp elevation | 窗口外一圈灰渐变阴影（无法被量化，它在宿主之外） |
 * | 勾选/单选按钮的 `?attr/colorAccent` | 唯一还会漏出的色相 |
 * | 弹出时的淡入/缩放动画 | 一串中间帧（EPD 上就是脏） |
 *
 * 两条路径，对应两种窗口归属：
 *
 *  1. [prepare]：应用自己的 `PopupWindow`（`applyMd3PopupStyle()` 的所有使用者，
 *     书源页右上角「⋮」就是它）。窗口结构由我们控制，于是可以
 *     去阴影、去动画、白底黑框，并包进帧缓冲量化，直接落到纯黑白。
 *  2. [attachMenuWindow]：系统 menu 弹窗（AppCompat `MenuPopupWindow`，
 *     书源页右上角「AŻ」排序和「分组」两个下拉）。窗口不归我们管、拿不到
 *     `PopupWindow` 实例，只能从 `BaseActivity.onCreateView` 的 inflate 钩子里
 *     拿到弹窗根视图，做**颜色收敛**（面板纯白、文字纯黑、勾选纯黑）。
 *
 * 判据仍然是截图的亮度直方图：本类负责把「灰」从这两个窗口里清掉，
 * 量化宿主负责把剩下的一点抗锯齿灰边压成纯黑/纯白。
 */
object EinkPopup {

    private const val TAG = "EinkRefresh"

    private const val INK = 0xFF000000.toInt()

    private val INK_TINT = ColorStateList.valueOf(INK)

    /** 已经处理过的菜单窗口。同一次弹出会被 inflate 触发多次，只挂一次监听 */
    private val menuWindows = WeakHashMap<View, Boolean>()

    /**
     * 给自绘 [PopupWindow] 做墨水屏化。必须在 `show*()` 之前调用
     * （`PopupWindow.setContentView` 在显示之后是空操作）。
     *
     * 失败一律降级成「只改颜色」，绝不因为窗口装饰而让菜单不弹出来。
     */
    fun prepare(popup: PopupWindow) {
        if (!AppConfig.isEInkMode) return
        val content = popup.contentView ?: return
        // 已经处理过：再套一次会把描边画到宿主自身（被量化结果覆盖掉）
        if (content is EinkFrameHost) return
        try {
            // 8dp elevation 的阴影画在窗口外，量化宿主永远看不到它，只能关掉
            popup.elevation = 0f
            // 淡入/缩放是一串中间帧；EPD 上不存在「不可见的中间帧」
            popup.setAnimationStyle(0)
            // 菜单是 wrap_content 的小面板：白底黑框 + 帧缓冲量化。
            // 全宽的贴条（键盘工具条、进度滑杆）尺寸由窗口决定，
            // 换一层宿主会改变测量行为，只做颜色收敛，不动背景几何。
            val isMenuPanel = popup.width == ViewGroup.LayoutParams.WRAP_CONTENT
            if (isMenuPanel) {
                popup.setBackgroundDrawable(ColorDrawable(Color.WHITE))
                content.setBackgroundKeepPadding(R.drawable.bg_eink_border_dialog)
            }
            val hosted = isMenuPanel && EinkFrameHostInstaller.install(popup)
            // 文字收敛 / 开关黑白化 / 列表 gate 都要在这个窗口里再挂一次
            EinkUiPolicy.attachWindow(popup.contentView ?: content)
            Log.i(
                TAG,
                "popup prepared menu=$isMenuPanel host=$hosted content=${content.javaClass.simpleName}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "popup prepare failed", t)
        }
    }

    /**
     * 给**不是** `BaseDialogFragment` 的对话框补上同一套窗口策略。
     *
     * `BaseDialogFragment` / `BasePrefDialogFragment` / `ListPreferenceDialog` 三个基类在
     * `onStart()` 里自己挂了帧宿主和颜色收敛，但 `lib/dialogs` 里的
     * `alert {} / input {} / selector {}`（走 [io.legado.app.lib.dialogs.AndroidAlertBuilder]）
     * 和 `progressDialog()` 是直接 `builder.show()` 出来的裸 `Dialog`，
     * 一个都不在覆盖范围里 —— 这是「页面已经纯黑白、一弹确认框又是灰的」的主要来源。
     *
     * 必须在 `show()` 之后调用：`android.R.id.content` 在对话框上屏前是空的。
     */
    fun attachDialog(dialog: Dialog) {
        if (!AppConfig.isEInkMode) return
        try {
            val hosted = EinkFrameHostInstaller.install(dialog)
            // 文字收敛 / 开关黑白化 / 勾选去色 / 列表 gate
            dialog.window?.decorView?.let { EinkUiPolicy.attachWindow(it) }
            Log.i(TAG, "dialog attached host=$hosted type=${dialog.javaClass.simpleName}")
        } catch (t: Throwable) {
            Log.w(TAG, "dialog attach failed", t)
        }
    }

    /**
     * 给系统 menu 弹窗（AppCompat `MenuPopupWindow` / 平台 `PopupMenu`）做颜色收敛。
     *
     * 入口是 `BaseActivity.onCreateView`：AppCompat 的菜单项布局是活动自己的
     * `LayoutInflater` inflate 的，所以能在这里拿到弹窗的背景容器
     * （`ListMenuItemView` 的 `parent.parent`，即 `PopupBackgroundView`）。
     * 这个窗口的 `PopupWindow` 实例拿不到，因此**不改变窗口结构**，
     * 只替换背景 drawable 并把文字/勾选拉回纯黑白。
     *
     * 面板底色原来是 `@color/background_menu`（`#EEEEEE`，浅灰），这一支把它换成
     * 与对话框一致的白底黑框。
     */
    fun attachMenuWindow(root: View) {
        if (!AppConfig.isEInkMode) return
        if (menuWindows.put(root, true) != null) return
        try {
            root.setBackgroundKeepPadding(R.drawable.bg_eink_border_dialog)
            root.post {
                runCatching {
                    EinkUiPolicy.attachWindow(root)
                    blackenChecks(root)
                }
            }
            // 列表行是布局时才创建的，勾选按钮要等它们出现
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (!AppConfig.isEInkMode) return@addOnGlobalLayoutListener
                runCatching { blackenChecks(root) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "menu window attach failed", t)
        }
    }

    /**
     * 菜单的勾选/单选是 [CompoundButton]，默认用 `?attr/colorAccent` 上色
     * （本主题下是玫红）。在只剩黑白的界面上，这是唯一还会漏出色相的元素。
     * 文字由 [EinkPalette] 负责，这里只补控件。
     */
    private fun blackenChecks(root: View) {
        when (root) {
            is CompoundButton -> {
                // 同一个实例，重复布局时 setter 变成空操作，不会反复触发重绘
                if (root.buttonTintList !== INK_TINT) root.buttonTintList = INK_TINT
                return
            }

            is CheckedTextView -> {
                if (root.checkMarkTintList !== INK_TINT) root.checkMarkTintList = INK_TINT
                return
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                blackenChecks(root.getChildAt(i))
            }
        }
    }
}
