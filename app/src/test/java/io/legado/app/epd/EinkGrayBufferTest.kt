package io.legado.app.epd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EinkGrayBufferTest {

    /** 按 ARGB_8888 的内存布局（R,G,B,A）写进原生序 buffer，模拟 Bitmap 的拷贝结果 */
    private fun toNativeInts(argb: IntArray): IntArray {
        val bytes = ByteBuffer.allocateDirect(argb.size * 4).order(ByteOrder.nativeOrder())
        for (c in argb) {
            bytes.put((c shr 16 and 0xFF).toByte())
            bytes.put((c shr 8 and 0xFF).toByte())
            bytes.put((c and 0xFF).toByte())
            bytes.put((c ushr 24).toByte())
        }
        bytes.position(0)
        val ints = IntArray(argb.size)
        bytes.asIntBuffer().get(ints)
        return ints
    }

    private fun fromNativeInts(native: IntArray): IntArray {
        val out = IntArray(native.size)
        for (i in native.indices) {
            val v = native[i]
            val r = v and 0xFF
            val g = v ushr 8 and 0xFF
            val b = v ushr 16 and 0xFF
            val a = v ushr 24 and 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    @Test
    fun `native path matches argb path`() {
        val w = 32
        val h = 16
        val src = IntArray(w * h) { i ->
            val v = i * 255 / (w * h - 1)
            (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or (v / 2)
        }
        val expected = src.copyOf()
        EinkGray.quantizeArgb(expected, w, 4, dither = true)

        val native = toNativeInts(src)
        EinkGray.quantizeNative(native, w, 4, dither = true)
        val actual = fromNativeInts(native)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `native path keeps only four levels`() {
        val w = 64
        val h = 8
        val src = IntArray(w * h) { i ->
            val v = i % 256
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val native = toNativeInts(src)
        EinkGray.quantizeNative(native, w, 4, dither = false)
        val out = fromNativeInts(native)
        assertEquals(setOf(0, 85, 170, 255), out.map { it and 0xFF }.toSet())
    }
}
