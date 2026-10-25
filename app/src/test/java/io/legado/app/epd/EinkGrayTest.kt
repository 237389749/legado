package io.legado.app.epd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkGrayTest {

    private fun ramp(size: Int): IntArray = IntArray(size) { i ->
        val v = i * 255 / (size - 1)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun distinctLuminance(pixels: IntArray): Set<Int> =
        pixels.map { EinkGray.luminance(it) }.toSet()

    @Test
    fun `without dithering a ramp collapses to exactly the requested levels`() {
        val px = ramp(256)
        EinkGray.quantizeArgb(px, 16, levels = 4, dither = false)
        assertEquals(4, distinctLuminance(px).size)
    }

    @Test
    fun `two level output only contains black and white`() {
        val px = ramp(256)
        EinkGray.quantizeArgb(px, 16, levels = 2, dither = false)
        assertEquals(setOf(0, 255), distinctLuminance(px))
    }

    @Test
    fun `dithering a flat mid gray produces both extremes`() {
        val w = 16
        val px = IntArray(w * w) { (0xFF shl 24) or 0x808080 }
        EinkGray.quantizeArgb(px, w, levels = 2, dither = true)
        val lum = distinctLuminance(px)
        assertTrue(lum.contains(0))
        assertTrue(lum.contains(255))
    }

    @Test
    fun `dithering keeps the average luminance close to the source`() {
        val w = 32
        val px = IntArray(w * w) { (0xFF shl 24) or 0x808080 }
        EinkGray.quantizeArgb(px, w, levels = 2, dither = true)
        val avg = px.sumOf { EinkGray.luminance(it) }.toDouble() / px.size
        assertTrue("average was $avg", avg in 110.0..145.0)
    }

    @Test
    fun `alpha is preserved`() {
        val px = intArrayOf((0x80 shl 24) or 0x404040)
        EinkGray.quantizeArgb(px, 1, levels = 2, dither = false)
        assertEquals(0x80, px[0] ushr 24)
    }

    @Test
    fun `quantized output is stable when run twice`() {
        val w = 8
        val px = ramp(w * w)
        EinkGray.quantizeArgb(px, w, levels = 4, dither = true)
        val once = px.copyOf()
        EinkGray.quantizeArgb(px, w, levels = 4, dither = true)
        assertTrue(once.contentEquals(px))
    }
}
