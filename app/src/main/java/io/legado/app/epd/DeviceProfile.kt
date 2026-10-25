package io.legado.app.epd

import android.os.Build

/**
 * 墨水屏设备档案。
 *
 * 这里只放「设备事实」，不放策略。策略在 [RefreshGovernor]。
 * 数据来源：Poke6 波形实测（E-Ink-Launcher/WAVEFORM_TO_DISPLAY.md）。
 */
enum class EinkVendor { ONYX, HISENSE, KOBO, UNKNOWN }

object DeviceProfile {

    /** Onyx 的 UI/EPD 值：GC16(2) | FULL(32) | WAIT(64)，整屏清残影用 */
    const val UI_GC16_FULL = 98

    /** 纯黑白 1bpp，22 帧，走 update[0] 局部刷新 */
    const val UI_DU = 1

    /** 16 级灰阶 4bpp，38 帧，全摆动（会闪） */
    const val UI_GC16 = 2

    /** 最快 10 帧，但只能全屏 update[1]，残影最大 */
    const val UI_A2 = 4

    val model: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    val vendor: EinkVendor by lazy {
        when {
            onyxHelperClass() != null -> EinkVendor.ONYX
            matches("hisense", "touch") -> EinkVendor.HISENSE
            matches("kobo", "rakuten") -> EinkVendor.KOBO
            else -> EinkVendor.UNKNOWN
        }
    }

    val isEink: Boolean get() = vendor != EinkVendor.UNKNOWN

    /** Poke6 上 A2 只能全屏刷新（update[1]），因此禁止任何连续交互使用 A2 */
    val a2FullScreenOnly: Boolean by lazy {
        vendor == EinkVendor.ONYX && model.contains("Poke6", ignoreCase = true)
    }

    fun onyxHelperClass(): Class<*>? {
        return try {
            Class.forName("android.onyx.ViewUpdateHelper")
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun matches(vararg keys: String): Boolean {
        val hay = "$model ${Build.DEVICE} ${Build.PRODUCT}".lowercase()
        return keys.any { hay.contains(it) }
    }
}
