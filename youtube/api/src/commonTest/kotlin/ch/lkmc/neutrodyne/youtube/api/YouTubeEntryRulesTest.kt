// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YouTubeEntryRulesTest {
    @Test fun firstFieldsWinButShortsClassificationUnions() {
        val result =
            merge(
                VariantResult.Ok(2, listOf(entry("a", title = "Second")), "Second author"),
                VariantResult.Ok(1, listOf(entry("a", title = "First")), "First author"),
            )
        assertEquals(
            "First",
            result.entries
                .single()
                .entry.title,
        )
        assertEquals("First author", result.authorName)
        assertTrue(result.entries.single().isShort)
        assertEquals(0, result.entries.single().feedOrder)
    }

    @Test fun publishedDescendingAndIdTiesAreDeterministic() {
        val result =
            merge(
                VariantResult.Ok(1, listOf(entry("z", 10), entry("b", 20), entry("a", 20), entry("x", null)), "Author"),
            )
        assertEquals(listOf("a", "b", "z", "x"), result.entries.map { it.entry.videoId })
        assertEquals(listOf(0, 1, 2, 3), result.entries.map { it.feedOrder })
    }

    @Test fun rawWindowFloorPrecedesFallbackShortsFiltering() {
        val raw = (0..14).map { entry("$it", it.toLong(), link = "https://www.youtube.com/shorts/$it") }
        val result = YouTubeEntryRules.merge(listOf(VariantResult.Ok(1, raw, "Author", fallback = true)), 1)
        assertTrue(result.entries.isEmpty())
        assertEquals(0L, result.absenceFloor)
    }

    @Test fun maximumFloorAcrossDifferentVariantWindowsProtectsHistory() {
        val long = (0..14).map { entry("long$it", it.toLong()) }
        val short = (100..114).map { entry("short$it", it.toLong()) }
        assertEquals(
            100L,
            merge(VariantResult.Ok(1, long, "Author"), VariantResult.Ok(2, short, "Author")).absenceFloor,
        )
    }

    @Test fun shortAndEmptyListsAreCompleteButAnyFailureDisablesAbsence() {
        val short = VariantResult.Ok(1, listOf(entry("a")), "Author")
        assertEquals(Long.MIN_VALUE, merge(short, VariantResult.Empty(2)).absenceFloor)
        assertEquals(Long.MIN_VALUE, merge(VariantResult.Empty(1), VariantResult.Empty(2)).absenceFloor)
        assertEquals(Long.MAX_VALUE, merge(short, VariantResult.Failed(2, 503, null)).absenceFloor)
    }

    @Test fun disabledFailuresDoNotDisableAbsence() {
        val results = listOf(VariantResult.Ok(1, listOf(entry("a")), "Author"), VariantResult.Failed(2, 503, null))
        assertEquals(Long.MIN_VALUE, YouTubeEntryRules.merge(results, 1).absenceFloor)
    }

    @Test fun normalizedAuthorParticipatesInDigest() {
        fun author(value: String?) = merge(VariantResult.Ok(1, listOf(entry("a")), value))
        assertEquals(author("  Cafe\u0301 \n Team ").digest, author("Café Team").digest)
        assertNotEquals(author("Author").digest, author("Renamed").digest)
        assertEquals("Café Team", author(" Cafe\u0301\tTeam ").authorName)
    }

    @Test fun digestAndHashIncludePublishedTextAndShorts() {
        val base = entry("a")

        fun digest(
            e: YouTubeEntry,
            bit: Int = 1,
        ) = merge(VariantResult.Ok(bit, listOf(e), "Author")).digest
        assertNotEquals(digest(base), digest(base.copy(title = "Edited")))
        assertNotEquals(digest(base), digest(base.copy(description = "Edited")))
        assertNotEquals(digest(base), digest(base.copy(publishedAtMs = 99)))
        assertNotEquals(digest(base), digest(base, 2))
        assertNotEquals(hash(false), hash(true))
    }

    @Test fun framedDigestCannotConfusePipesAndNewlinesInPublisherText() {
        val a = entry("a", title = "title|1|false\nx")
        val b = a.copy(title = "title", description = "1|false\nx")
        assertNotEquals(
            merge(VariantResult.Ok(1, listOf(a), "Author")).digest,
            merge(VariantResult.Ok(1, listOf(b), "Author")).digest,
        )
        assertEquals(64, merge(VariantResult.Empty(1)).digest.length)
    }

    @Test fun fallbackUsesLinksRatherThanThePrimaryBitToClassifyShorts() {
        val result =
            YouTubeEntryRules.merge(
                listOf(
                    VariantResult.Ok(
                        2,
                        listOf(entry("a"), entry("b", link = "https://www.youtube.com/shorts/b?feature=share")),
                        "Author",
                        true,
                    ),
                ),
                2,
            )
        assertFalse(result.entries.first().isShort)
        assertTrue(result.entries.last().isShort)
    }

    @Test fun canonicalUrlAndThumbnailRulesValidateTheirInputs() {
        val id = "UCaaaaaaaaaaaaaaaaaaaaaA"
        for (bits in 1..7) {
            val urls = YouTubeFeedUrls.pollUrls(id, bits)
            assertEquals(listOf(1, 2, 4).filter { it and bits != 0 }, urls.map { it.bit })
            assertTrue(urls.all { it.url.startsWith("https://www.youtube.com/feeds/videos.xml?playlist_id=UU") })
        }
        assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa", YouTubeFeedUrls.watch("aaaaaaaaaaa"))
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg", YouTubeEntryRules.thumbnail("aaaaaaaaaaa"))
    }

    private fun entry(
        id: String,
        published: Long? = 1,
        title: String = "Title",
        link: String? = null,
    ) = YouTubeEntry(id, title, published, published?.toString(), published ?: 500, "Description", link)

    private fun merge(vararg results: VariantResult) = YouTubeEntryRules.merge(results.toList(), 7)

    private fun hash(short: Boolean) = YouTubeEntryRules.contentHash("Title", 1, "raw", "Description", short)
}
