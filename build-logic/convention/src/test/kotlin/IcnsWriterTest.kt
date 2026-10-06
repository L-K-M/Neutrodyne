// SPDX-License-Identifier: Unlicense
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals

import org.junit.Test

/** Header and entry bytes of our PNG-compressed ICNS writer (01 Brand-asset generator). */
class IcnsWriterTest {
    @Test
    fun writesMagicLengthsAndEntries() {
        val small = solid(128)
        val large = solid(512)
        val smallPng = PngBytes.of(small)
        val largePng = PngBytes.of(large)

        val bytes = IcnsWriter.write(listOf(IcnsEntry("ic07", small), IcnsEntry("ic10", large)))

        assertEquals("icns", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(bytes.size, u32(bytes, 4))
        // first entry
        assertEquals("ic07", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(8 + smallPng.size, u32(bytes, 12))
        // second entry starts right after the first
        val secondOffset = 8 + u32(bytes, 12)
        assertEquals("ic10", String(bytes, secondOffset, 4, Charsets.US_ASCII))
        assertEquals(8 + largePng.size, u32(bytes, secondOffset + 4))
        assertEquals(bytes.size, secondOffset + u32(bytes, secondOffset + 4))
        // the payloads really are PNGs
        assertEquals(0x89, bytes[16].toInt() and 0xFF)
        assertEquals(0x89, bytes[secondOffset + 8].toInt() and 0xFF)
        assertEquals('P'.code, bytes[secondOffset + 9].toInt() and 0xFF)
        assertEquals('N'.code, bytes[secondOffset + 10].toInt() and 0xFF)
        assertEquals('G'.code, bytes[secondOffset + 11].toInt() and 0xFF)
    }

    @Test
    fun rejectsTypeCodesThatAreNotFourCharacters() {
        assertThrows<IllegalStateException> { IcnsWriter.write(listOf(IcnsEntry("ic7", solid(16)))) }
    }

    private fun solid(size: Int): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
