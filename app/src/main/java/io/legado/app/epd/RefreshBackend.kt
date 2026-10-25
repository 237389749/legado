package io.legado.app.epd

import android.util.Log

/**
 * 刷新后端。业务只表达「意图」，由这里翻译成厂商调用。
 *
 * 不依赖厂商 SDK：全部反射，失败即降级为 no-op，绝不抛到业务层。
 */
interface RefreshBackend {

    val name: String

    val available: Boolean

    /** 设置本应用的 scope 波形；uiValue 见 [DeviceProfile] */
    fun applyAppScope(uiValue: Int): Boolean

    /** 整屏清残影：GC16 + FULL */
    fun clearGhosting(): Boolean

    /** 按指定波形整屏重绘 */
    fun repaintAll(uiValue: Int): Boolean
}

object NoopRefreshBackend : RefreshBackend {

    override val name = "noop"

    override val available = false

    override fun applyAppScope(uiValue: Int) = false

    override fun clearGhosting() = false

    override fun repaintAll(uiValue: Int) = false
}

/**
 * Onyx（BOOX）实现。
 *
 * 反射目标（Poke6 实测可用，见 E-Ink-Launcher/RefreshModeHelper.java）：
 *  - android.onyx.ViewUpdateHelper.applyAppScopeUpdate(String pkg, boolean, int, int ui, int)
 *  - android.onyx.ViewUpdateHelper.clearAppScopeUpdate(boolean)
 *  - android.onyx.ViewUpdateHelper.repaintEverything()
 *  - android.onyx.ViewUpdateHelper.repaintEverything(int ui)
 *
 * 注意：系统侧 per-app 刷新配置若不为 NONE，EPDC 会忽略这里的 scope，
 * 直接按系统档位算波形。所以 scope 只是「加速手段」，
 * 真正的兜底是 [RefreshGovernor] 的残影预算。
 */
class OnyxRefreshBackend(private val pkg: String) : RefreshBackend {

    override val name = "onyx"

    private val clazz: Class<*>? = DeviceProfile.onyxHelperClass()

    override val available: Boolean get() = clazz != null

    private fun find(name: String, vararg types: Class<*>): java.lang.reflect.Method? {
        return try {
            clazz?.getMethod(name, *types)
        } catch (ignored: Throwable) {
            null
        }
    }

    override fun applyAppScope(uiValue: Int): Boolean {
        val m = find(
            "applyAppScopeUpdate",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) ?: return false
        return try {
            m.invoke(null, pkg, true, 0, uiValue, Int.MAX_VALUE)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "applyAppScope($uiValue) failed", t)
            false
        }
    }

    override fun clearGhosting(): Boolean = repaintAll(DeviceProfile.UI_GC16_FULL)

    override fun repaintAll(uiValue: Int): Boolean {
        val m = find("repaintEverything", Int::class.javaPrimitiveType!!) ?: return false
        return try {
            m.invoke(null, uiValue)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "repaintAll($uiValue) failed", t)
            false
        }
    }

    fun clearAppScope(): Boolean {
        val m = find("clearAppScopeUpdate", Boolean::class.javaPrimitiveType!!) ?: return false
        return try {
            m.invoke(null, true)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "clearAppScope failed", t)
            false
        }
    }

    private companion object {
        const val TAG = "EinkRefresh"
    }
}
