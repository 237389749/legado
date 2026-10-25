package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import io.legado.app.epd.EinkRuntime
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ReadView

/**
 * 墨水屏翻页。
 *
 * 与 [NoAnimPageDelegate] 的唯一区别：翻页后向 [EinkRuntime] 上报一次"语义事件"，
 * 由残影预算决定是否需要整屏全刷。
 *
 * 不做任何动画、不截图、不重绘：一次翻页 = 一次 EPD 提交。
 */
class EinkPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    override fun onAnimStart(animationSpeed: Int) {
        if (isCancel) {
            stopScroll()
            return
        }
        val chapterBefore = ReadBook.curTextChapter
        readView.fillPage(mDirection)
        EinkRuntime.onPageTurn(readView, chapterBefore !== ReadBook.curTextChapter)
        stopScroll()
    }

    override fun setBitmap() {
        // 不做截图缓存：墨水屏不需要动画帧
    }

    override fun onDraw(canvas: Canvas) {
        // 不做动画绘制
    }

    override fun onAnimStop() {
        // 无动画
    }
}
