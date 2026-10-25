package io.legado.app.epd

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshGovernorTest {

    private val backend = object : RefreshBackend {
        override val name = "fake"
        override val available = true
        override fun applyAppScope(uiValue: Int) = true
        override fun clearGhosting() = true
        override fun repaintAll(uiValue: Int) = true
    }

    @Test
    fun `normal page turn asks for no extra commit`() {
        val governor = RefreshGovernor(backend)
        assertEquals(RefreshIntent.PAGE_TURN, governor.nextIntent(chapterChanged = false))
    }

    @Test
    fun `chapter change always asks for a full clean`() {
        val governor = RefreshGovernor(backend)
        governor.nextIntent(chapterChanged = false)
        assertEquals(RefreshIntent.CHAPTER_CHANGE, governor.nextIntent(chapterChanged = true))
    }

    @Test
    fun `every Nth page asks for a full clean`() {
        val governor = RefreshGovernor(backend)
        governor.fullRefreshInterval = 3
        assertEquals(RefreshIntent.PAGE_TURN, governor.nextIntent(false))
        assertEquals(RefreshIntent.PAGE_TURN, governor.nextIntent(false))
        assertEquals(RefreshIntent.FULL_CLEAN, governor.nextIntent(false))
        assertEquals(RefreshIntent.PAGE_TURN, governor.nextIntent(false))
    }

    @Test
    fun `zero interval disables the periodic clean`() {
        val governor = RefreshGovernor(backend)
        governor.fullRefreshInterval = 0
        repeat(50) {
            assertEquals(RefreshIntent.PAGE_TURN, governor.nextIntent(false))
        }
    }

    @Test
    fun `chapter change does not reset the page budget`() {
        val governor = RefreshGovernor(backend)
        governor.fullRefreshInterval = 3
        governor.nextIntent(false)
        governor.nextIntent(true)
        assertEquals(RefreshIntent.FULL_CLEAN, governor.nextIntent(false))
    }

    @Test
    fun `reset restarts the counting`() {
        val governor = RefreshGovernor(backend)
        governor.fullRefreshInterval = 2
        governor.nextIntent(false)
        governor.reset()
        assertEquals(RefreshIntent.PAGE_TURN, governor.nextIntent(false))
        assertEquals(RefreshIntent.FULL_CLEAN, governor.nextIntent(false))
    }
}
