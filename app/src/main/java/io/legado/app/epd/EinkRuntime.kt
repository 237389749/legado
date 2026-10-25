package io.legado.app.epd

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.View
import io.legado.app.help.config.AppConfig

/**
 * 墨水屏运行时门面。
 *
 * 只有两个入口：
 *  - [install]：Application 启动时装配后端
 *  - [onPageTurn]：阅读页翻页后上报
 *
 * 一切以"墨水屏模式"（themeMode == 3）为开关，没有新增 UI。
 */
object EinkRuntime {

    /**
     * 是否强制把本应用的 scope 钉成 DU（1bpp / 22 帧 / 不闪）。
     *
     * 默认关闭：DU 会让图片和封面也变成纯黑白，需要先实测正文可读性再打开。
     * 打开方式：改成 true 重新打包，或在调试时反射调用 [applyFastScope]。
     */
    private const val FORCE_FAST_SCOPE = false

    private var backend: RefreshBackend = NoopRefreshBackend
    private var governor: RefreshGovernor? = null
    private var installed = false

    val backendName: String get() = backend.name

    val isAvailable: Boolean get() = installed && backend.available

    /** 开关跟系统的"墨水屏模式"绑定，用户切主题即生效，无需重启 */
    val isActive: Boolean get() = isAvailable && AppConfig.isEInkMode

    fun install(context: Context) {
        if (installed) return
        installed = true
        backend = when (DeviceProfile.vendor) {
            EinkVendor.ONYX -> OnyxRefreshBackend(context.packageName)
            else -> NoopRefreshBackend
        }
        governor = RefreshGovernor(backend)
        Log.i(
            TAG,
            "install vendor=${DeviceProfile.vendor} model=${DeviceProfile.model} " +
                "backend=${backend.name} available=${backend.available} " +
                "a2FullScreenOnly=${DeviceProfile.a2FullScreenOnly}"
        )
        (context as? Application)?.let { EinkUiPolicy.install(it) }
        if (isActive) {
            if (FORCE_FAST_SCOPE) applyFastScope()
            else Log.i(TAG, "fast scope disabled, ghost budget only")
        }
    }

    /** 阅读页翻页后上报；chapterChanged 由调用方比对当前章节对象得出 */
    fun onPageTurn(view: View, chapterChanged: Boolean) {
        if (!isActive) return
        governor?.onPageTurn(view, chapterChanged)
    }

    /**
     * 打开/关闭时各调一次，用于把正文钉到 DU。
     * 注意：系统 per-app 刷新配置不为 NONE 时可能不生效。
     */
    fun applyFastScope(): Boolean {
        if (!isAvailable) return false
        val ok = backend.applyAppScope(DeviceProfile.UI_DU)
        Log.i(TAG, "applyFastScope(DU) -> $ok")
        return ok
    }

    fun clearScope(): Boolean {
        val b = backend as? OnyxRefreshBackend ?: return false
        val ok = b.clearAppScope()
        Log.i(TAG, "clearScope -> $ok")
        return ok
    }

    private const val TAG = "EinkRefresh"
}
