package io.legado.app.epd

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 帧缓冲量化宿主。
 *
 * View 体系没有后处理钩子（ColorFilter 只能线性变换，RenderEffect 要 API 31），
 * 所以要做到「所见即量化」，只能把整棵视图树画进一张 Bitmap，
 * 量化像素后再贴回屏幕。
 *
 * 代价：每帧一次全屏绘制 + 1.5M 像素遍历（约 50ms）。
 * 这个代价在 LCD 上不可接受，但在 EPD 上完全不是问题——面板本身一帧就要几百毫秒，
 * 应用侧多花几十毫秒换「绝不出现脏灰」，是划算的。
 *
 * 注意：
 *  - 这里用**不抖动**的量化（threshold=0.5）。文字的抗锯齿边缘一旦抖动，
 *    就会变成满屏噪点；抖动留给图片（见 [EinkGrayTransformation]）。
 *  - 任何异常都直接降级为原样绘制，绝不让 App 白屏。
 */
class EinkFrameHost(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "EinkRefresh"

        /** 量化级数：2（纯黑白）/ 4 / 16（等于不量化） */
        var levels = 4

        /** 总开关，由 [EinkUiPolicy] 按「墨水屏模式」控制 */
        var enabled = true

        /** 单帧耗时超过这个值就记一条日志，用于评估代价 */
        private const val SLOW_FRAME_MS = 40
        private var slowFrameLogged = 0

        /** 两次量化之间的最小间隔：一次连续绘制只量化落定的那一帧 */
        private const val MIN_INTERVAL_MS = 120L
    }

    private var buffer: Bitmap? = null
    private var scratch: IntArray = IntArray(0)
    private var backgroundColor = Color.WHITE
    private var lastQuantizeAt = 0L
    private var catchUpPending = false

    init {
        setWillNotDraw(false)
        // 背景由上层窗口绘制，这里自己补一层，避免量化出透明像素
        backgroundColor = resolveBackground(context)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!enabled || levels > 16) {
            super.dispatchDraw(canvas)
            return
        }
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas)
            return
        }

        // 节流：一次连续绘制里只量化「落定」的那一帧。
        // 中间帧按原样画（便宜），120ms 后再补一次量化，保证最终画面是量化过的。
        val now = SystemClock.uptimeMillis()
        if (now - lastQuantizeAt < MIN_INTERVAL_MS) {
            super.dispatchDraw(canvas)
            scheduleCatchUp()
            return
        }

        val started = System.nanoTime()
        try {
            val bmp = ensureBuffer(w, h)
            val off = Canvas(bmp)
            off.drawColor(backgroundColor)
            super.dispatchDraw(off)
            val drawCost = (System.nanoTime() - started) / 1_000_000
            val t1 = System.nanoTime()
            bmp.getPixels(scratch, 0, w, 0, 0, w, h)
            val t2 = System.nanoTime()
            EinkGray.quantizeArgb(scratch, w, levels, dither = false)
            val t3 = System.nanoTime()
            bmp.setPixels(scratch, 0, w, 0, 0, w, h)
            val t4 = System.nanoTime()
            canvas.drawBitmap(bmp, 0f, 0f, null)
            lastQuantizeAt = now
            val total = (System.nanoTime() - started) / 1_000_000
            if (slowFrameLogged < 5) {
                slowFrameLogged++
                Log.i(
                    TAG,
                    "frame ${w}x$h total=${total}ms draw=$drawCost " +
                        "get=${(t2 - t1) / 1_000_000} quant=${(t3 - t2) / 1_000_000} " +
                        "set=${(t4 - t3) / 1_000_000}"
                )
            }
        } catch (t: Throwable) {
            // 出任何问题就永久关掉，宁可回到未量化
            enabled = false
            Log.w(TAG, "frame host disabled: $t")
            super.dispatchDraw(canvas)
        }
    }

    private fun scheduleCatchUp() {
        if (catchUpPending) return
        catchUpPending = true
        postDelayed({
            catchUpPending = false
            invalidate()
        }, MIN_INTERVAL_MS)
    }

    private fun ensureBuffer(w: Int, h: Int): Bitmap {
        val cur = buffer
        if (cur != null && cur.width == w && cur.height == h) return cur
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffer = bmp
        scratch = IntArray(w * h)
        return bmp
    }

    private fun resolveBackground(context: Context): Int {
        return try {
            val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            val d = a.getDrawable(0)
            a.recycle()
            if (d is ColorDrawable) d.color else Color.WHITE
        } catch (ignored: Throwable) {
            Color.WHITE
        }
    }
}

/**
 * 把 Activity 的内容区包进 [EinkFrameHost]。
 *
 * 只包 `android.R.id.content` 的唯一子 View——不动 View 树结构以外的东西，
 * 输入事件、findViewById、Fragment 容器都不受影响。
 */
object EinkFrameHostInstaller {

    private const val TAG = "EinkRefresh"

    fun install(activity: Activity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (content.childCount != 1) return false
        val child = content.getChildAt(0)
        if (child is EinkFrameHost) return true
        if (hasSurfaceView(child)) return false
        val lp = child.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return try {
            content.removeViewAt(0)
            val host = EinkFrameHost(activity)
            host.addView(child, ViewGroup.LayoutParams(lp.width, lp.height))
            content.addView(host, lp)
            Log.i(TAG, "frame host installed: ${activity.javaClass.simpleName}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "frame host install failed", t)
            false
        }
    }

    /** SurfaceView 画不进 Bitmap，遇到就放弃这一个界面 */
    private fun hasSurfaceView(root: View): Boolean {
        if (root is android.view.SurfaceView) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (hasSurfaceView(root.getChildAt(i))) return true
            }
        }
        return false
    }
}
