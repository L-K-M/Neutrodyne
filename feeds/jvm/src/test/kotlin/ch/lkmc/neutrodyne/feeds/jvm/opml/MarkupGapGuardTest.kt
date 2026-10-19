// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.parse.HostileEntityDeclaration
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertFailsWith

/**
 * The [MarkupGapGuard] automaton below the parser: encoding detection and the entity scan at the
 * byte level, where the JVM's missing UTF-32 decoder keeps the reader-level cases from reaching
 * the guarded bytes at all.
 */
class MarkupGapGuardTest {
    private fun drain(
        bytes: ByteArray,
        maxGap: Long = MAX_GAP,
    ) {
        MarkupGapGuard(ByteArrayInputStream(bytes), maxGap, MAX_ATTRS).use { guard ->
            val buf = ByteArray(8192)
            while (guard.read(buf) >= 0) {
                // read through; the automaton runs on the pulled bytes
            }
        }
    }

    /** UTF-32LE carries the same four-byte signature kxml2 sniffs: `FF FE 00 00`. */
    @Test
    fun utf32LeEntityIsRejected() {
        val doc = "<!DOCTYPE opml [<!ENTITY x \"y\">]><opml version=\"1.0\"/>"
        val bytes = BOM_UTF32LE + utf32Le(doc)
        assertFailsWith<HostileEntityDeclaration> { drain(bytes) }
    }

    /** UTF-32BE is the mirror signature: `00 00 FE FF`. */
    @Test
    fun utf32BeEntityIsRejected() {
        val doc = "<!DOCTYPE opml [<!ENTITY x \"y\">]><opml version=\"1.0\"/>"
        val bytes = BOM_UTF32BE + utf32Be(doc)
        assertFailsWith<HostileEntityDeclaration> { drain(bytes) }
    }

    /** The BOM-less UTF-32 signatures `3C 00 00 00` / `00 00 00 3C` select the same modes. */
    @Test
    fun utf32SignatureWithoutBomIsDetected() {
        val doc = "<opml version=\"1.0\"><!DOCTYPE opml [<!ENTITY x \"y\">]></opml>"
        assertFailsWith<HostileEntityDeclaration> { drain(utf32Le(doc)) }
        assertFailsWith<HostileEntityDeclaration> { drain(utf32Be(doc)) }
    }

    /** A benign UTF-32 document reads through untouched. */
    @Test
    fun utf32DocumentReadsThrough() {
        val doc = "<opml version=\"1.0\"><body><outline text=\"f\"/></body></opml>"
        drain(BOM_UTF32LE + utf32Le(doc))
        drain(BOM_UTF32BE + utf32Be(doc))
    }

    @Test(timeout = 5_000)
    fun malformedUtf32WordsCannotStopTheGuard() {
        for (unit in intArrayOf(Int.MIN_VALUE, -3, -2, -1)) {
            val littleEndian = utf32Le(unit)
            drain(BOM_UTF32LE + littleEndian)
            drain(BOM_UTF32BE + littleEndian.reversedArray())
        }
    }

    @Test(timeout = 5_000)
    fun malformedUtf32WordsCannotHideFollowingDeclarations() {
        val declaration = "<!DOCTYPE opml [<!ENTITY x \"y\">]><opml/>"
        for (unit in intArrayOf(Int.MIN_VALUE, -3, -2, -1)) {
            val littleEndian = utf32Le(unit)
            assertFailsWith<HostileEntityDeclaration> {
                drain(BOM_UTF32LE + littleEndian + utf32Le(declaration))
            }
            assertFailsWith<HostileEntityDeclaration> {
                drain(BOM_UTF32BE + littleEndian.reversedArray() + utf32Be(declaration))
            }
        }
    }

    /** In four-byte modes the markup gap still bounds `<`-free payload runs. */
    @Test
    fun utf32MarkupGapIsBounded() {
        val doc = "<opml version=\"1.0\"><body>" + "x".repeat(GAP_FLOOD_CHARS) + "</body></opml>"
        assertFailsWith<MarkupBoundExceeded> { drain(BOM_UTF32LE + utf32Le(doc)) }
    }

    private fun utf32Le(text: String): ByteArray {
        val out = ByteArray(text.length * 4)
        for (i in text.indices) {
            val c = text[i].code
            out[i * 4] = c.toByte()
            out[i * 4 + 1] = (c shr 8).toByte()
            out[i * 4 + 2] = (c shr 16).toByte()
            out[i * 4 + 3] = (c shr 24).toByte()
        }
        return out
    }

    private fun utf32Le(unit: Int): ByteArray =
        ByteArray(Int.SIZE_BYTES) { index -> (unit ushr (index * Byte.SIZE_BITS)).toByte() }

    private fun utf32Be(text: String): ByteArray {
        val out = ByteArray(text.length * 4)
        for (i in text.indices) {
            val c = text[i].code
            out[i * 4] = (c shr 24).toByte()
            out[i * 4 + 1] = (c shr 16).toByte()
            out[i * 4 + 2] = (c shr 8).toByte()
            out[i * 4 + 3] = c.toByte()
        }
        return out
    }

    private companion object {
        const val MAX_GAP = 128L
        const val MAX_ATTRS = 1_000
        const val GAP_FLOOD_CHARS = 256

        val BOM_UTF32LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
        val BOM_UTF32BE = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())
    }
}
