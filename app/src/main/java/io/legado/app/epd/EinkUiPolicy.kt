package io.legado.app.epd

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
    private const val frameLevels = 4

    private var installed = false

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
                root.post { applyTo(root) }
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
        if (root is RecyclerView) {
            applyRecycler(root)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyTo(root.getChildAt(i))
            }
        }
    }

    private fun applyRecycler(rv: RecyclerView) {
        if (rv.itemAnimator != null) rv.itemAnimator = null
        if (rv.overScrollMode != View.OVER_SCROLL_NEVER) {
            rv.overScrollMode = View.OVER_SCROLL_NEVER
        }
        EinkListGate.attach(rv)
    }
}
