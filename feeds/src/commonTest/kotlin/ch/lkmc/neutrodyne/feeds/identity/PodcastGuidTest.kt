// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `PodcastGuid.parse` validation and `derive` reproducing both spec examples (03 podcast:guid). */
class PodcastGuidTest {
    @Test
    fun parseValidatesAndLowercases() {
        assertEquals(
            "9b024349-ccf0-5f69-a609-6b82873eab3c",
            PodcastGuid.parse("9B024349-CCF0-5F69-A609-6B82873EAB3C"),
        )
        assertEquals(
            "9b024349-ccf0-5f69-a609-6b82873eab3c",
            PodcastGuid.parse(" 9b024349-ccf0-5f69-a609-6b82873eab3c "),
        )
    }

    @Test
    fun parseRejectsMalformed() {
        assertNull(PodcastGuid.parse("9b024349-ccf0-5f69-a609-6b82873eab3")) // too short
        assertNull(PodcastGuid.parse("9b024349ccf05f69a6096b82873eab3c")) // no dashes
        assertNull(PodcastGuid.parse("9b024349-ccf0-5f69-a609-6b82873eab3z")) // non-hex
        assertNull(PodcastGuid.parse(""))
        assertNull(PodcastGuid.parse("not a guid"))
        // The nil UUID is junk, not an identity: two broken feeds must not dedupe to the same show.
        assertNull(PodcastGuid.parse("00000000-0000-0000-0000-000000000000"))
        // 32 hex digits and 4 hyphens, but the groups are not 8-4-4-4-12.
        assertNull(PodcastGuid.parse("9b024349-ccf05f-69a60-96b8-2873eab3c"))
    }

    @Test
    fun deriveReproducesSpecExampleNashownotes() {
        assertEquals(
            "917393e3-1b1e-5cef-ace4-edaa54e1f810",
            PodcastGuid.derive("mp3s.nashownotes.com/pc20rss.xml"),
        )
    }

    @Test
    fun deriveReproducesSpecExamplePodnews() {
        assertEquals(
            "9b024349-ccf0-5f69-a609-6b82873eab3c",
            PodcastGuid.derive("podnews.net/rss"),
        )
    }

    @Test
    fun deriveStripsSchemeAndTrailingSlashes() {
        assertEquals(PodcastGuid.derive("podnews.net/rss"), PodcastGuid.derive("https://podnews.net/rss"))
        assertEquals(PodcastGuid.derive("podnews.net/rss"), PodcastGuid.derive("https://podnews.net/rss/"))
        assertEquals(PodcastGuid.derive("example.com/feed"), PodcastGuid.derive("http://example.com/feed///"))
    }
}
