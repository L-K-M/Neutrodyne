// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.directory

import ch.lkmc.neutrodyne.feeds.discovery.AddInputNormalizer
import ch.lkmc.neutrodyne.feeds.discovery.HostAction
import ch.lkmc.neutrodyne.feeds.discovery.HostRecognizer
import ch.lkmc.neutrodyne.feeds.discovery.NormalizedInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectoryParsersTest {
    @Test fun unknownAndNullFieldsRemainOptional() {
        val row =
            DirectoryParsers.apple(
                """{"results":[{"collectionName":null,"feedUrl":null,"unknown":true},{"collectionName":"Show","feedUrl":"file:///private","trackCount":4}]}""",
            )
        assertNull(row.first().feedUrl)
        assertEquals("file:///private", row.last().feedUrl)
        assertEquals(listOf(1, 2), row.map { it.rank })
    }

    @Test fun malformedOversizedAndWrongRootAreRejected() {
        for (text in listOf("{", "[]", " ".repeat(DirectoryParsers.MAX_JSON_BYTES + 1))) {
            assertFails { DirectoryParsers.apple(text) }
        }
        assertFails { DirectoryParsers.fyyd("""{"status":0,"data":[]}""") }
        assertFails { DirectoryParsers.podcastIndex("""{"status":false,"feeds":[]}""") }
    }

    @Test fun indexEndpointSpecificDatesAndDeadFlag() {
        val row =
            DirectoryParsers
                .podcastIndex(
                    """{"status":true,"feed":{"url":"https://a.example/f","newestItemPubdate":123,"dead":1,"medium":"music"}}""",
                ).single()
        assertEquals(123000, row.lastEpisodeAt)
        assertTrue(row.dead)
        assertEquals("music", row.medium)
    }

    @Test fun hostsUseExactAuthoritiesAndIdPaths() {
        for (input in listOf(
            "https://podcasts.apple.com/us/podcast/show/id12",
            "https://itunes.apple.com/podcast/id12?i=2",
            "https://pod.link/12",
            "https://overcast.fm/itunes12",
        )) {
            assertEquals(HostAction.AppleLookup("12"), HostRecognizer.recognize(input))
        }
        assertEquals(
            HostAction.PodcastIndexLookup("12"),
            HostRecognizer.recognize("https://podcastindex.org/podcast/12"),
        )
        assertEquals(HostAction.FyydPage, HostRecognizer.recognize("https://fyyd.de/podcast/name"))
        for (input in listOf(
            "https://evil.example/podcasts.apple.com/id12",
            "https://pod.link.evil.example/12",
            "https://pod.link/no",
            "javascript:alert(1)",
            "https://podcasts.apple.com/a/id12evil",
        )) {
            assertEquals(HostAction.Fetch, HostRecognizer.recognize(input))
        }
    }

    @Test fun malformedGoogleAndCyclicWrappersStayBounded() {
        assertEquals(NormalizedInput.Invalid, AddInputNormalizer.normalize("https://podcasts.google.com/feed/%%%"))
        assertEquals(
            NormalizedInput.Invalid,
            AddInputNormalizer.normalize(
                "neutrodyne://subscribe?url=neutrodyne%3A%2F%2Fsubscribe%3Furl%3Dneutrodyne%253A%252F%252Fsubscribe",
            ),
        )
        for (url in listOf("file:///a", "javascript://example.org/a", "content://example.org/a")) {
            assertEquals(NormalizedInput.Invalid, AddInputNormalizer.normalize(url))
        }
    }
}
