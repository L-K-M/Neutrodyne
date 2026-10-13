// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `EpisodeDescriptionCodec` (02 episode_description): UTF-8 under 512 bytes stays raw (`0x00`
 * HTML, `0x02` plain text), at/above it is raw-DEFLATEd (`0x01`/`0x03`), and an unknown header
 * byte never throws — the whole blob is treated as pre-codec UTF-8 HTML. Codec-owned blobs are
 * stricter: a corrupt or truncated body, or output past the 2 MiB producer bound, fails with
 * `IllegalStateException`.
 */
class DescriptionCodecTest {
    @Test
    fun shortTextStaysRaw() {
        val text = "<p>Show notes — épisode 12 ☕</p>"
        val encoded = EpisodeDescriptionCodec.encode(text, DescriptionFormat.HTML)
        assertEquals(RAW, encoded[0])
        assertContentEquals(byteArrayOf(RAW) + text.encodeToByteArray(), encoded)
        assertEquals(DecodedDescription(text, DescriptionFormat.HTML), EpisodeDescriptionCodec.decode(encoded))
    }

    @Test
    fun shortPlainTextStaysRawUnderItsOwnHeader() {
        val text = "Show notes — épisode 12 ☕"
        val encoded = EpisodeDescriptionCodec.encode(text, DescriptionFormat.PLAIN_TEXT)
        assertEquals(RAW_TEXT, encoded[0])
        assertContentEquals(byteArrayOf(RAW_TEXT) + text.encodeToByteArray(), encoded)
        assertEquals(DecodedDescription(text, DescriptionFormat.PLAIN_TEXT), EpisodeDescriptionCodec.decode(encoded))
    }

    @Test
    fun boundaryAt512Bytes() {
        val below = "a".repeat(511)
        val at = "a".repeat(512)
        for (format in DescriptionFormat.entries) {
            assertEquals(rawTag(format), EpisodeDescriptionCodec.encode(below, format)[0])
            assertEquals(deflatedTag(format), EpisodeDescriptionCodec.encode(at, format)[0])
            assertEquals(
                DecodedDescription(at, format),
                EpisodeDescriptionCodec.decode(EpisodeDescriptionCodec.encode(at, format)),
            )
        }
    }

    @Test
    fun theBoundaryCountsUtf8BytesNotChars() {
        // 255×'é' = 510 bytes → RAW; 256×'é' = 512 bytes → DEFLATED.
        assertEquals(RAW, EpisodeDescriptionCodec.encode("é".repeat(255), DescriptionFormat.HTML)[0])
        assertEquals(DEFLATED, EpisodeDescriptionCodec.encode("é".repeat(256), DescriptionFormat.HTML)[0])
        assertEquals(RAW_TEXT, EpisodeDescriptionCodec.encode("é".repeat(255), DescriptionFormat.PLAIN_TEXT)[0])
        assertEquals(DEFLATED_TEXT, EpisodeDescriptionCodec.encode("é".repeat(256), DescriptionFormat.PLAIN_TEXT)[0])
    }

    @Test
    fun megabyteOfHtmlRoundTrips() {
        val text = "<p>${"lorem ipsum dolor sit amet ".repeat(40_000)}</p> 🎙️"
        val encoded = EpisodeDescriptionCodec.encode(text, DescriptionFormat.HTML)
        assertEquals(DEFLATED, encoded[0])
        assertEquals(DecodedDescription(text, DescriptionFormat.HTML), EpisodeDescriptionCodec.decode(encoded))
    }

    @Test
    fun megabyteOfPlainTextRoundTrips() {
        val text = "lorem ipsum dolor sit amet ".repeat(40_000) + "🎙️"
        val encoded = EpisodeDescriptionCodec.encode(text, DescriptionFormat.PLAIN_TEXT)
        assertEquals(DEFLATED_TEXT, encoded[0])
        assertEquals(DecodedDescription(text, DescriptionFormat.PLAIN_TEXT), EpisodeDescriptionCodec.decode(encoded))
    }

    @Test
    fun emptyStringRoundTrips() {
        val encoded = EpisodeDescriptionCodec.encode("", DescriptionFormat.HTML)
        assertEquals(DecodedDescription("", DescriptionFormat.HTML), EpisodeDescriptionCodec.decode(encoded))
        assertEquals(DecodedDescription("", DescriptionFormat.HTML), EpisodeDescriptionCodec.decode(byteArrayOf()))
    }

    @Test
    fun unknownHeaderByteTreatsTheWholeBlobAsUtf8Html() {
        // A corrupt or pre-codec blob: an unrecognised first byte must not throw (02), and the
        // pre-codec payload has always meant markup.
        val legacy = "<b>notes</b>".encodeToByteArray()
        val corrupted = byteArrayOf(0x7F) + legacy
        assertEquals(
            DecodedDescription("\u007F<b>notes</b>", DescriptionFormat.HTML),
            EpisodeDescriptionCodec.decode(corrupted),
        )
        // And a plain blob without any header decodes as itself.
        assertEquals(
            DecodedDescription("<b>notes</b>", DescriptionFormat.HTML),
            EpisodeDescriptionCodec.decode(legacy),
        )
    }

    @Test
    fun aCorruptDeflateBodyFailsControlled() {
        // A recognised DEFLATED header with a bad body is a corruption signal — a controlled
        // failure, never partial show notes ("never throws" covers only the unknown-header
        // fallback). Both format headers behave the same.
        for (format in DescriptionFormat.entries) {
            val encoded = EpisodeDescriptionCodec.encode("<p>${"long ".repeat(200)}</p>", format)
            assertEquals(deflatedTag(format), encoded[0])
            val truncated = encoded.copyOf(encoded.size - 4)
            val flipped =
                encoded.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
            assertFailsWith<IllegalStateException> { EpisodeDescriptionCodec.decode(truncated) }
            assertFailsWith<IllegalStateException> { EpisodeDescriptionCodec.decode(flipped) }
        }
    }

    @Test
    fun aBlobInflatingPastTheProducerBoundFailsInsteadOfExpanding() {
        // Every producer is capped at 512 Ki chars (03 ParseLimits.maxTextChars), so 2 MiB of
        // UTF-8 bounds legitimate output — a bomb blob must die before it grows the heap. The
        // blob is crafted directly: encode itself now rejects input past the bound.
        for (tag in listOf(DEFLATED, DEFLATED_TEXT)) {
            val bomb = byteArrayOf(tag) + deflatedBody("a".repeat(3 * 1024 * 1024))
            assertFailsWith<IllegalStateException> { EpisodeDescriptionCodec.decode(bomb) }
        }
        for (tag in listOf(RAW, RAW_TEXT)) {
            val oversized = byteArrayOf(tag) + ByteArray(2 * 1024 * 1024 + 1)
            assertFailsWith<IllegalStateException> { EpisodeDescriptionCodec.decode(oversized) }
        }
        // The boundary stays reachable: exactly 2 MiB of inflated output still round-trips.
        val atBound = EpisodeDescriptionCodec.encode("a".repeat(2 * 1024 * 1024), DescriptionFormat.HTML)
        assertEquals(2 * 1024 * 1024, EpisodeDescriptionCodec.decode(atBound).text.length)
    }

    @Test
    fun encodeRejectsInputPastTheCodecBoundBeforePersisting() {
        // A custom ParseLimits (e.g. 768 Ki chars) can legally emit text the decoder would
        // reject: encode must fail first so the blob is never written. A successful encode
        // must always round-trip — the aligned bound keeps that invariant.
        assertFailsWith<IllegalArgumentException> {
            EpisodeDescriptionCodec.encode("a".repeat(2 * 1024 * 1024 + 1), DescriptionFormat.HTML)
        }
        // The multibyte case: 786 432 CJK chars = 2 359 296 UTF-8 bytes, also past the bound.
        assertFailsWith<IllegalArgumentException> {
            EpisodeDescriptionCodec.encode("字".repeat(768 * 1024), DescriptionFormat.PLAIN_TEXT)
        }
        // 512 Ki multibyte chars inside the bound still encode and round-trip.
        val cjk = "字".repeat(512 * 1024)
        assertEquals(
            DecodedDescription(cjk, DescriptionFormat.PLAIN_TEXT),
            EpisodeDescriptionCodec.decode(EpisodeDescriptionCodec.encode(cjk, DescriptionFormat.PLAIN_TEXT)),
        )
    }

    /** Raw-DEFLATE of [text]'s UTF-8 — the wire body of a `0x01`/`0x03` blob, built around `encode`'s own bound. */
    private fun deflatedBody(text: String): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(text.encodeToByteArray())
            deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun rawTag(format: DescriptionFormat): Byte = if (format == DescriptionFormat.HTML) RAW else RAW_TEXT

    private fun deflatedTag(format: DescriptionFormat): Byte =
        if (format == DescriptionFormat.HTML) DEFLATED else DEFLATED_TEXT

    private companion object {
        const val RAW: Byte = 0x00
        const val DEFLATED: Byte = 0x01
        const val RAW_TEXT: Byte = 0x02
        const val DEFLATED_TEXT: Byte = 0x03
    }
}
