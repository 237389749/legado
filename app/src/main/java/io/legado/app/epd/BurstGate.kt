package io.legado.app.epd

/**
 * 「突发内容」门控的纯逻辑：判断现在该继续压住画面，还是放行。
 *
 * 不碰 View、不碰线程、不读时钟，全部由调用方传入 now，便于单测。
 *
 * 语义：
 *  - 内容第一次变化 → 进入 hold
 *  - 之后持续变化 → 一直 hold（刷新静默计时）
 *  - 静默满 [quietMs] → 放行
 *  - 但最多 hold [maxHoldMs]，避免用户面对空白等太久
 */
class BurstGate(
    private val quietMs: Long = 200L,
    private val maxHoldMs: Long = 600L,
) {

    private var holding = false
    private var holdingSince = 0L
    private var lastChangeAt = 0L

    val isHolding: Boolean get() = holding

    /** 内容变化。返回 true 表示本次变化触发了新的 hold。 */
    fun onChange(now: Long): Boolean {
        lastChangeAt = now
        if (holding) return false
        holding = true
        holdingSince = now
        return true
    }

    /** 是否应当放行。 */
    fun shouldRelease(now: Long): Boolean {
        if (!holding) return false
        return now - lastChangeAt >= quietMs || now - holdingSince >= maxHoldMs
    }

    /** 放行后归位，等待下一次突发。 */
    fun onReleased() {
        holding = false
    }

    fun reset() {
        holding = false
        holdingSince = 0L
        lastChangeAt = 0L
    }
}
