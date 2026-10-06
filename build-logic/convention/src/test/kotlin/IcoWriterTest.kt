// SPDX-License-Identifier: Unlicense
import java.awt.image.BufferedImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

import org.junit.Test

/** Header and directory bytes of our PNG-compressed ICO writer (01 Brand-asset generator; ICO is little-endian). */
class IcoWriterTest {
    @Test
    fun writesHeaderDirectoryAndPayloads() {
        val first = solid(16)
        val second = solid(32)
        val firstPng = PngBytes.of(first)
        val secondPng = PngBytes.of(second)

        val bytes = IcoWriter.write(listOf(first, second))

        assertEquals(0, u16(bytes, 0)) // reserved
        assertEquals(1, u16(bytes, 2)) // type: icon
        assertEquals(2, u16(bytes, 4)) // entry count
        // first directory entry
        assertEquals(16, bytes[6].toInt() and 0xFF)
        assertEquals(16, bytes[7].toInt() and 0xFF)
        assertEquals(0, bytes[8].toInt())
        assertEquals(0, bytes[9].toInt())
        assertEquals(1, u16(bytes, 10)) // colour planes
        assertEquals(32, u16(bytes, 12)) // bits per pixel
        assertEquals(firstPng.size, u32(bytes, 14))
        assertEquals(6 + 2 * 16, u32(bytes, 18)) // first payload right after the directory
        // second directory entry
        assertEquals(32, bytes[22].toInt() and 0xFF)
        assertEquals(secondPng.size, u32(bytes, 30))
        assertEquals(6 + 2 * 16 + firstPng.size, u32(bytes, 34))
        // payloads
        assertArrayEquals(firstPng, bytes.copyOfRange(u32(bytes, 18), u32(bytes, 18) + firstPng.size))
        assertArrayEquals(secondPng, bytes.copyOfRange(u32(bytes, 34), u32(bytes, 34) + secondPng.size))
        assertEquals(u32(bytes, 34) + secondPng.size, bytes.size)
        // the entries really are PNGs
        assertEquals(0x89, bytes[u32(bytes, 18)].toInt() and 0xFF)
        assertEquals('P'.code, bytes[u32(bytes, 18) + 1].toInt())
        assertEquals('N'.code, bytes[u32(bytes, 18) + 2].toInt())
        assertEquals('G'.code, bytes[u32(bytes, 18) + 3].toInt())
    }

    @Test
    fun storesTheSize256AsZero() {
        val bytes = IcoWriter.write(listOf(solid(256)))

        assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt())
    }

    @Test
    fun rejectsNonSquareAndOversizedEntries() {
        assertThrows<IllegalStateException> {
            IcoWriter.write(listOf(BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB)))
        }
        assertThrows<IllegalStateException> { IcoWriter.write(listOf(solid(512))) }
    }

    private fun solid(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) {
            for (x in 0 until size) image.setRGB(x, y, -0x1000000)
        }
        return image
    }

    /** little-endian reads, as the ICO format defines them */
    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (u16(bytes, offset + 2) shl 16) or u16(bytes, offset)
}
