package io.legado.app.epd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书源页右上角三个下拉（排序 / 分组 / 溢出）在 2 级量化下还是灰的。
 *
 * 这两组断言钉住的不是「好看」，而是「这些窗口不会再漏出灰阶」的实现位置：
 *  - 自绘 PopupWindow 必须经过同一个入口（[EinkPopup.prepare]），
 *    否则每新增一个弹窗就要记得手动改一次；
 *  - 系统 menu 弹窗只能从 `BaseActivity.onCreateView` 的 inflate 钩子进去，
 *    这个钩子一旦被删，排序/分组下拉会静默退回成灰面板；
 *  - 面板/文字的颜色必须真的是墨与纸，而不是 `#EEEEEE` / `#383838`。
 */
class EinkPopupTest {

    @Test
    fun `self drawn popups go through one entry`() {
        val extension = readProjectFile("src/main/java/io/legado/app/utils/PopupWindowExtensions.kt")
        assertContains(extension, "EinkPopup.prepare(this)")

        val popupAction = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupAction.kt")
        assertContains(popupAction, "applyMd3PopupStyle()")
    }

    @Test
    fun `system menu windows are reached from the activity inflate hook`() {
        val baseActivity = readProjectFile("src/main/java/io/legado/app/base/BaseActivity.kt")
        assertContains(baseActivity, "EinkPopup.attachMenuWindow(menuWindow)")

        val appConst = readProjectFile("src/main/java/io/legado/app/constant/AppConst.kt")
        assertContains(appConst, "com.android.internal.view.menu.ListMenuItemView")
        assertContains(appConst, "androidx.appcompat.view.menu.ListMenuItemView")
    }

    @Test
    fun `popup chrome drops the shadow and the window animation`() {
        val source = readProjectFile(EINK_POPUP)
        // 阴影画在窗口外，量化宿主看不到它，只能关掉
        assertContains(source, "popup.elevation = 0f")
        // 淡入/缩放是中间帧，EPD 上不存在「不可见的中间帧」
        assertContains(source, "popup.setAnimationStyle(0)")
        // 阴影去掉后靠 1px 描边交代面板边界（与对话框同一套 drawable）
        assertContains(source, "R.drawable.bg_eink_border_dialog")
        assertContains(source, "ColorDrawable(Color.WHITE)")
    }

    @Test
    fun `menu popup quantization only touches wrap content panels`() {
        val source = readProjectFile(EINK_POPUP)
        assertContains(source, "popup.width == ViewGroup.LayoutParams.WRAP_CONTENT")
        assertContains(source, "EinkFrameHostInstaller.install(popup)")
        // 全宽贴条的测量由窗口决定，不能换宿主
        assertContains(source, "menu=\$isMenuPanel host=\$hosted")
    }

    @Test
    fun `checks and radios are forced to ink`() {
        val source = readProjectFile(EINK_POPUP)
        assertContains(source, "INK_TINT = ColorStateList.valueOf(INK)")
        assertContains(source, "root.buttonTintList = INK_TINT")
        assertContains(source, "root.checkMarkTintList = INK_TINT")
        assertContains(source, "EinkUiPolicy.attachWindow(root)")
    }

    @Test
    fun `system menu windows are quantized too`() {
        // 颜色收敛之后文字边缘仍是 200 多级灰；要真的 2 级必须把弹窗内容包进帧宿主
        assertContains(readProjectFile(EINK_POPUP), "EinkFrameHostInstaller.installInParent(root)")
        assertContains(
            readProjectFile("src/main/java/io/legado/app/epd/EinkFrameHost.kt"),
            "fun installInParent(child: View)"
        )
    }

    @Test
    fun `bare dialogs get the same window policy`() {
        assertContains(readProjectFile(EINK_POPUP), "fun attachDialog(dialog: Dialog)")
        // alert {} / input {} / selector {} 全走这个 builder，两条出口都要补
        val alertBuilder =
            readProjectFile("src/main/java/io/legado/app/lib/dialogs/AndroidAlertBuilder.kt")
        assertContains(alertBuilder, "EinkPopup.attachDialog(dialog)")
        assertContains(alertBuilder, "dialog.setOnShowListener { EinkPopup.attachDialog(dialog) }")
        assertContains(
            readProjectFile("src/main/java/io/legado/app/lib/dialogs/AndroidDialogs.kt"),
            "EinkPopup.attachDialog(it)"
        )
        // 阅读页划词菜单是另一种独立窗口
        assertContains(
            readProjectFile("src/main/java/io/legado/app/ui/book/read/TextActionMenu.kt"),
            "EinkPopup.prepare(this)"
        )
    }

    /**
     * 记账：被换掉的那两个颜色到底有多灰。
     *
     * `#EEEEEE` 是弹窗面板（`@color/background_menu`），`#383838` 是菜单文字
     * （`@color/menu_color_default`）。两者都落在中间灰里 —— 在只做 2 级量化、
     * 又没有经过量化宿主（即这两条弹窗路径原本的处境）的屏幕上，
     * 它们就原样显示成浅灰面板 + 灰字。
     */
    @Test
    fun `former popup colors really were grays`() {
        assertTrue(EinkGray.luminance(0xFFEEEEEE.toInt()) in 200..255)
        assertTrue(EinkGray.luminance(0xFF383838.toInt()) in 40..80)
        // 换成墨与纸之后，2 级下不会再有第三种亮度
        assertEquals(255, EinkGray.lut(2, dither = false)[0][238])
        assertEquals(0, EinkGray.lut(2, dither = false)[0][56])
    }

    @Test
    fun `eink border drawable is pure ink on paper`() {
        val border = readProjectFile("src/main/res/drawable/bg_eink_border_dialog.xml")
        assertContains(border, "<solid android:color=\"@color/white\"")
        assertContains(border, "android:color=\"@android:color/black\"")
        assertFalse(border.contains("@color/divider"))
    }

    private fun assertContains(text: String, expected: String) {
        assertTrue("Expected to contain $expected", text.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }

    private companion object {
        const val EINK_POPUP = "src/main/java/io/legado/app/epd/EinkPopup.kt"
    }
}
