// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `EpisodeDescriptionCodec` (02 episode_description): UTF-8 under 512 bytes stays raw (`0x00`),
 * at/above it is raw-DEFLATEd (`0x01`), and an unknown header byte never throws — the whole blob
 * is treated as pre-codec UTF-8.
 */
class DescriptionCodecTest {
    @Test
    fun shortTextStaysRaw() {
        val text = "<p>Show notes — épisode 12 ☕</p>"
        val encoded = EpisodeDescriptionCodec.encode(text)
        assertEquals(RAW, encoded[0])
        assertContentEquals(byteArrayOf(RAW) + text.encodeToByteArray(), encoded)
        assertEquals(text, EpisodeDescriptionCodec.decode(encoded))
    }

    @Test
    fun boundaryAt512Bytes() {
        val below = "a".repeat(511)
        val at = "a".repeat(512)
        assertEquals(RAW, EpisodeDescriptionCodec.encode(below)[0])
        assertEquals(DEFLATED, EpisodeDescriptionCodec.encode(at)[0])
        assertEquals(at, EpisodeDescriptionCodec.decode(EpisodeDescriptionCodec.encode(at)))
    }

    @Test
    fun megabyteOfHtmlRoundTrips() {
        val text = "<p>${"lorem ipsum dolor sit amet ".repeat(40_000)}</p> 🎙️"
        val encoded = EpisodeDescriptionCodec.encode(text)
        assertEquals(DEFLATED, encoded[0])
        assertEquals(text, EpisodeDescriptionCodec.decode(encoded))
    }

    @Test
    fun emptyStringRoundTrips() {
        val encoded = EpisodeDescriptionCodec.encode("")
        assertEquals("", EpisodeDescriptionCodec.decode(encoded))
        assertEquals("", EpisodeDescriptionCodec.decode(byteArrayOf()))
    }

    @Test
    fun unknownHeaderByteTreatsTheWholeBlobAsUtf8() {
        // A corrupt or pre-codec blob: an unrecognised first byte must not throw (02).
        val legacy = "<b>notes</b>".encodeToByteArray()
        val corrupted = byteArrayOf(0x7F) + legacy
        val decoded = EpisodeDescriptionCodec.decode(corrupted)
        assertEquals("\u007F<b>notes</b>", decoded)
        // And a plain blob without any header decodes as itself.
        assertEquals("<b>notes</b>", EpisodeDescriptionCodec.decode(legacy))
    }

    private companion object {
        const val RAW: Byte = 0x00
        const val DEFLATED: Byte = 0x01
    }
}
