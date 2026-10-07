// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
