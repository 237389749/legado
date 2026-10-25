package io.legado.app.epd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkPaletteTest {

    @Test
    fun `opaque black keeps its luminance`() {
        assertEquals(0, EinkPalette.compositeLuminance(0xFF000000.toInt()))
    }

    @Test
    fun `half transparent black composites to mid gray`() {
        val lum = EinkPalette.compositeLuminance(0x80000000.toInt())
        assertTrue("lum was $lum", lum in 120..135)
    }

    @Test
    fun `light gray text composites above the threshold`() {
        assertTrue(EinkPalette.compositeLuminance(0xFFC0C0C0.toInt()) > 0x7F)
    }

    @Test
    fun `dark gray text stays below the threshold`() {
        assertTrue(EinkPalette.compositeLuminance(0xFF444444.toInt()) <= 0x7F)
    }
}
