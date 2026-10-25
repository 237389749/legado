package io.legado.app.epd

import android.os.SystemClock
import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap

/**
 * 把「结果陆续到达」的列表压成「一次成型」。
 *
 * 机制：给 Adapter 挂一个观察者，收到任何变化就 `suppressLayout(true)`，
 * 静默 200ms（最长 600ms）后放行。压住期间 RecyclerView 不重新布局，
 * 屏幕上保持上一帧的完整画面，不会出现「item 一个个冒出来」的中间态；
 * 放行时一次性 layout + 一次绘制 = 一次 EPD 提交。
 *
 * 只对「适配器变化」生效，正常滚动完全不受影响。
 */
object EinkListGate {

    private const val TAG = "EinkRefresh"
    private const val TICK_MS = 50L

    private val attached = WeakHashMap<RecyclerView, Gate>()

    fun attach(rv: RecyclerView) {
        if (attached.containsKey(rv)) return
        val adapter = rv.adapter ?: return
        val gate = Gate(rv)
        attached[rv] = gate
        adapter.registerAdapterDataObserver(gate)
        Log.i(TAG, "list gate attached id=${rv.id} adapter=${adapter.javaClass.simpleName}")
    }

    private class Gate(private val rv: RecyclerView) : RecyclerView.AdapterDataObserver() {

        private val burst = BurstGate()

        private val tick = object : Runnable {
            override fun run() {
                if (!burst.isHolding) return
                if (burst.shouldRelease(SystemClock.uptimeMillis())) {
                    release()
                } else {
                    rv.postDelayed(this, TICK_MS)
                }
            }
        }

        private fun onChange() {
            val now = SystemClock.uptimeMillis()
            if (!burst.onChange(now)) return
            try {
                rv.suppressLayout(true)
            } catch (ignored: Throwable) {
            }
            rv.postDelayed(tick, TICK_MS)
            Log.d(TAG, "gate hold id=${rv.id}")
        }

        private fun release() {
            burst.onReleased()
            try {
                rv.suppressLayout(false)
            } catch (ignored: Throwable) {
            }
            rv.requestLayout()
            Log.d(TAG, "gate release id=${rv.id} items=${rv.adapter?.itemCount}")
        }

        override fun onChanged() = onChange()

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onChange()

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = onChange()

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onChange()

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onChange()

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = onChange()
    }
}
