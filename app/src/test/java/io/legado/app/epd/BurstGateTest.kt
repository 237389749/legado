package io.legado.app.epd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstGateTest {

    @Test
    fun `first change starts holding`() {
        val gate = BurstGate(quietMs = 200, maxHoldMs = 600)
        assertTrue(gate.onChange(1000))
        assertTrue(gate.isHolding)
    }

    @Test
    fun `further changes keep holding without restarting the burst`() {
        val gate = BurstGate(quietMs = 200, maxHoldMs = 600)
        gate.onChange(1000)
        assertFalse(gate.onChange(1100))
        assertTrue(gate.isHolding)
    }

    @Test
    fun `releases after the quiet window`() {
        val gate = BurstGate(quietMs = 200, maxHoldMs = 600)
        gate.onChange(1000)
        gate.onChange(1100)
        assertFalse(gate.shouldRelease(1250))
        assertTrue(gate.shouldRelease(1300))
    }

    @Test
    fun `never holds longer than the cap even when content keeps changing`() {
        val gate = BurstGate(quietMs = 200, maxHoldMs = 600)
        gate.onChange(1000)
        var now = 1000L
        while (now < 1600) {
            now += 50
            gate.onChange(now)
        }
        assertTrue(gate.shouldRelease(now))
    }

    @Test
    fun `does not release when idle without a burst`() {
        val gate = BurstGate()
        assertFalse(gate.shouldRelease(10_000))
    }

    @Test
    fun `a new burst after release holds again`() {
        val gate = BurstGate(quietMs = 200, maxHoldMs = 600)
        gate.onChange(1000)
        gate.onReleased()
        assertTrue(gate.onChange(2000))
    }
}
