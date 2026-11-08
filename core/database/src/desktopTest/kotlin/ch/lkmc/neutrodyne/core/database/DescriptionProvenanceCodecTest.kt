// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.DescriptionCompleteness
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DescriptionProvenanceCodecTest {
    @Test fun frozenReaderRendersNewWriterExactlyForBothFormatsAndCompletenessValues() {
        for (format in DescriptionFormat.entries) {
            for (completeness in listOf(DescriptionCompleteness.COMPLETE, DescriptionCompleteness.TRUNCATED)) {
                for (text in listOf("", "0:00 <b>literal</b> &copy; 🎙", "字".repeat(512), "notes ".repeat(200_000))) {
                    val encoded = EpisodeDescriptionCodec.encode(text, format, completeness)
                    assertEquals(DecodedDescription(text, format), FrozenW9DescriptionCodec.decode(encoded))
                    assertEquals(
                        DecodedDescription(text, format, completeness),
                        EpisodeDescriptionCodec.decode(encoded),
                    )
                }
            }
        }
    }

    @Test fun newReaderHandlesFrozenRawCompressedEmptyAndLegacyWriterAsUnknown() {
        for (format in DescriptionFormat.entries) {
            for (text in listOf("", "0:00 First", "é".repeat(256), "字".repeat(512 * 1024))) {
                val old = FrozenW9DescriptionCodec.encode(text, format)
                assertEquals(
                    DecodedDescription(text, format, DescriptionCompleteness.UNKNOWN),
                    EpisodeDescriptionCodec.decode(old),
                )
                assertContentEquals(old, EpisodeDescriptionCodec.encode(text, format))
                assertContentEquals(old, EpisodeDescriptionCodec.encode(text, format, DescriptionCompleteness.UNKNOWN))
            }
        }
        for (legacy in listOf(
            byteArrayOf(),
            "<!-- ND:COMPLETE -->notes".encodeToByteArray(),
            byteArrayOf(0x7f, 4, 5),
        )) {
            assertEquals(FrozenW9DescriptionCodec.decode(legacy), EpisodeDescriptionCodec.decode(legacy))
        }
    }

    @Test fun provenanceIsOutsideTextAndRequiresTheExactStreamEnd() {
        val marked =
            EpisodeDescriptionCodec.encode(
                "notes",
                DescriptionFormat.PLAIN_TEXT,
                DescriptionCompleteness.COMPLETE,
            )
        val old = FrozenW9DescriptionCodec.encode("notes".repeat(200), DescriptionFormat.PLAIN_TEXT)
        val marker = "\u0000Neutrodyne.description\u0000C"
        val payload = marker.repeat(100)
        for (blob in listOf(
            payload.encodeToByteArray(),
            FrozenW9DescriptionCodec.encode(marker, DescriptionFormat.PLAIN_TEXT),
            FrozenW9DescriptionCodec.encode(payload, DescriptionFormat.HTML),
            FrozenW9DescriptionCodec.encode(payload, DescriptionFormat.PLAIN_TEXT),
            // A valid second stream and marker may not lend its provenance to the first stream.
            old + marked,
        )) {
            assertEquals(DescriptionCompleteness.UNKNOWN, EpisodeDescriptionCodec.decode(blob).completeness)
        }
        for (end in 1..20) {
            val incompleteTrailer = marked.copyOf(marked.size - end)
            // The DEFLATE stream is intact; missing metadata is UNKNOWN, never complete.
            assertEquals("notes", EpisodeDescriptionCodec.decode(incompleteTrailer).text)
            assertEquals(
                DescriptionCompleteness.UNKNOWN,
                EpisodeDescriptionCodec.decode(incompleteTrailer).completeness,
            )
        }
        val changed = marked.copyOf().also { it[it.lastIndex] = 99 }
        for (blob in listOf(changed, marked + byteArrayOf(0), old + "COMPLETE".encodeToByteArray())) {
            assertEquals(DescriptionCompleteness.UNKNOWN, EpisodeDescriptionCodec.decode(blob).completeness)
        }
    }

    @Test fun markedProducerBoundAndCorruptionRemainControlledForFrozenAndNewReaders() {
        for (format in DescriptionFormat.entries) {
            val atCap = "a".repeat(2 * 1024 * 1024)
            val blob = EpisodeDescriptionCodec.encode(atCap, format, DescriptionCompleteness.COMPLETE)
            assertEquals(atCap, FrozenW9DescriptionCodec.decode(blob).text)
            assertEquals(atCap, EpisodeDescriptionCodec.decode(blob).text)
            assertFailsWith<IllegalArgumentException> {
                EpisodeDescriptionCodec.encode(atCap + "a", format, DescriptionCompleteness.COMPLETE)
            }
            // Keep only the compressed tag and one body byte, so neither reader can finish.
            assertFailsWith<IllegalStateException> { EpisodeDescriptionCodec.decode(blob.copyOf(2)) }
            assertFailsWith<IllegalStateException> { FrozenW9DescriptionCodec.decode(blob.copyOf(2)) }
        }
    }
}
