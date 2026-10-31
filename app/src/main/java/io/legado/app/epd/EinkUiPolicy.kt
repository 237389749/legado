package io.legado.app.epd

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.config.AppConfig

/**
 * 墨水屏 UI 策略：与厂商无关的「呈现时序」约束。
 *
 * 目前两件事：
 *  1. 列表一次性成型（[EinkListGate]）
 *  2. 去掉与 EPD 冲突的表现层：item 动画、边缘过滚动光晕
 *
 * 开关跟系统的「墨水屏模式」绑定，不依赖厂商后端，任何设备都能生效。
 */
object EinkUiPolicy {

    private const val TAG = "EinkRefresh"

    /** 是否把整个 Activity 内容区做帧缓冲量化 */
    private const val quantizeFrame = true

    /** 量化级数：2 = 纯黑白，4 = 四级灰，16+ = 关闭 */
    private const val frameLevels = 2

    private var installed = false

    private val hooked = java.util.WeakHashMap<View, Boolean>()

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                if (!AppConfig.isEInkMode) return
                val root = activity.window?.decorView ?: return
                if (quantizeFrame) {
                    EinkFrameHost.levels = frameLevels
                    EinkFrameHost.enabled = true
                    EinkFrameHostInstaller.install(activity)
                }
                root.post {
                    EinkPalette.resetLog()
                    Log.d(TAG, "policy pass1 on ${activity.javaClass.simpleName}")
                    applyTo(root)
                }
                // 列表/空态是数据回来之后才出现的，一次性扫描抓不到；
                // 挂到 OnGlobalLayout 上，每次布局后再扫一遍（设置过色就会退出，不会形成循环）。
                installLayoutHook(root)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        Log.i(TAG, "ui policy installed")
    }

    fun applyTo(root: View) {
        EinkPalette.clamp(root)
        EinkWidgets.apply(root)
        applyLists(root)
    }

    /**
     * 给一个独立 window（对话框）挂策略。
     *
     * Activity 有 `onActivityResumed` 这条统一入口，对话框没有——不主动挂的话，
     * 偏好设置里的开关、列表 gate 全都不生效（这正是「设置里的开关在黑白下
     * 只剩一团黑」的原因）。
     */
    fun attachWindow(root: View) {
        root.post { applyTo(root) }
        installLayoutHook(root)
    }

    private fun installLayoutHook(root: View) {
        if (hooked.containsKey(root)) return
        hooked[root] = true
        val observer = root.viewTreeObserver
        observer.addOnGlobalLayoutListener {
            if (!AppConfig.isEInkMode) return@addOnGlobalLayoutListener
            try {
                applyTo(root)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun applyLists(root: View) {
        if (root is RecyclerView) {
            applyRecycler(root)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyLists(root.getChildAt(i))
            }
        }
    }

    private fun applyRecycler(rv: RecyclerView) {
        if (rv.itemAnimator != null) rv.itemAnimator = null
        if (rv.overScrollMode != View.OVER_SCROLL_NEVER) {
            rv.overScrollMode = View.OVER_SCROLL_NEVER
        }
        EinkListGate.attach(rv)
        attachChildStyler(rv)
    }

    /**
     * 列表滚动时新绑定的行不在上一次全局布局的遍历结果里，
     * 只靠 `OnGlobalLayout` 会漏掉它们（实测：偏好设置往下滑，新出现的开关
     * 还是原样的「一团黑」）。行一挂上来就补一次黑白化，最稳。
     */
    private fun attachChildStyler(rv: RecyclerView) {
        if (rv.getTag(R.id.eink_widget_styled) != null) return
        rv.setTag(R.id.eink_widget_styled, true)
        rv.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    try {
                        EinkWidgets.apply(view)
                    } catch (ignored: Throwable) {
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
    }
}
