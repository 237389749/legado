package io.legado.app.epd

import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/** 语义级刷新意图。业务只说「发生了什么」，由 [RefreshGovernor] 决定怎么刷。 */
enum class RefreshIntent {
    /** 普通翻页：交给系统按当前 scope 处理，不额外提交 */
    PAGE_TURN,

    /** 跨章：翻页后做一次整屏全刷，清掉正文区的残影 */
    CHAPTER_CHANGE,

    /** 攒够 N 页：同上 */
    FULL_CLEAN
}

/**
 * 残影预算。
 *
 * 墨水屏的残影是「同一区域被重复刷新」累积出来的物理量，
 * 所以必须有一个显式的计数器来偿还，而不是指望系统自觉。
 */
class RefreshGovernor(private val backend: RefreshBackend) {

    /** 每 N 页强制整屏全刷一次；0 表示关闭 */
    var fullRefreshInterval = 30

    private var pageTurns = 0

    /**
     * 纯决策，不碰 View，便于单测。
     *
     * 跨章不重置计数：章节切换本身就是一次全刷，计数继续累加，
     * 这样「每 N 页」的预算不会被章节边界无限重置而失效。
     */
    fun nextIntent(chapterChanged: Boolean): RefreshIntent {
        pageTurns++
        return when {
            chapterChanged -> RefreshIntent.CHAPTER_CHANGE
            fullRefreshInterval > 0 && pageTurns % fullRefreshInterval == 0 -> RefreshIntent.FULL_CLEAN
            else -> RefreshIntent.PAGE_TURN
        }
    }

    fun onPageTurn(view: View, chapterChanged: Boolean) {
        val intent = nextIntent(chapterChanged)
        if (intent == RefreshIntent.PAGE_TURN) return
        Log.d(TAG, "page #$pageTurns -> $intent, clean after draw")
        afterNextDraw(view) {
            val ok = backend.clearGhosting()
            Log.d(TAG, "clearGhosting($intent) -> $ok")
        }
    }

    fun reset() {
        pageTurns = 0
    }

    /**
     * 等这一帧画完再动作。
     *
     * 直接在 onAnimStart 里调 clearGhosting 会刷掉「上一页」，必须等新内容上屏。
     * ViewTreeObserver.OnDrawListener 在绘制前回调，post 出去的消息在本帧 traversal
     * 之后执行，正好落在「画面已更新」这个时刻。
     */
    private fun afterNextDraw(view: View, action: () -> Unit) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive) {
            view.post(action)
            return
        }
        var fired = false
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (fired) return
                fired = true
                view.post {
                    try {
                        observer.removeOnDrawListener(this)
                    } catch (ignored: Throwable) {
                    }
                    action()
                }
            }
        }
        try {
            observer.addOnDrawListener(listener)
        } catch (ignored: Throwable) {
            view.post(action)
            return
        }
        // 兜底：内容没变就不会有 draw，500ms 后摘掉监听，避免泄漏
        view.postDelayed({
            if (!fired) {
                fired = true
                try {
                    observer.removeOnDrawListener(listener)
                } catch (ignored: Throwable) {
                }
            }
        }, 500)
    }

    private companion object {
        const val TAG = "EinkRefresh"
    }
}
