// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exercises the existing platform Atom parser, including its hostile-input guards. */
abstract class FeedYouTubeMetadataReaderTest(
    parser: FeedParser,
) {
    private val reader = FeedYouTubeMetadataReader(parser, Dispatchers.Unconfined)
    private val id = "UCBJycsmduvYEL83R_U4JriQ"
    private val other = "UCLA_DiR1FfKNvjuUpBHmylQ"

    @Test fun firstEntryChannelIdAndFeedAuthorAreKeptSeparately() =
        runTest {
            val parsed =
                reader.read(
                    atom(
                        """
            <entry><id>yt:video:3iRUwVzRDZQ</id><title>Video</title><yt:channelId>$id</yt:channelId></entry>
            <entry><id>yt:video:abcdefghijk</id><title>Other</title><yt:channelId>$other</yt:channelId></entry>
        """,
                    ).encodeToByteArray(),
                )!!
            assertEquals(id, parsed.channelId)
            assertEquals("Owner & Team", parsed.authorName)
            assertEquals("https://www.youtube.com/channel/$other", parsed.authorUrl)
            assertEquals(2, parsed.entryCount)
        }

    @Test fun emptyFeedCanOfferPlaylistAuthorButDoesNotInventEntries() =
        runTest {
            val parsed = reader.read(atom("").encodeToByteArray())!!
            assertNull(parsed.channelId)
            assertEquals(0, parsed.entryCount)
            assertEquals("https://www.youtube.com/channel/$other", parsed.authorUrl)
        }

    @Test fun feedLevelChannelIdDoesNotReplaceFirstEntryRule() =
        runTest {
            val parsed =
                reader.read(
                    atom(
                        "<yt:channelId>${id.drop(
                            2,
                        )}</yt:channelId><entry><id>x</id><title>Empty identity</title></entry>",
                    ).encodeToByteArray(),
                )!!
            assertNull(parsed.channelId)
            assertEquals(1, parsed.entryCount)
        }

    @Test fun malformedHostileNonAtomAndOversizedBodiesAreUnreadable() =
        runTest {
            for (body in listOf(
                "not XML",
                "<html>page</html>",
                "<rss version='2.0'><channel><title>RSS</title></channel></rss>",
                "<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///no'>]><feed>&x;</feed>",
                " ".repeat(2 * 1024 * 1024 + 1),
            )) {
                assertNull(reader.read(body.encodeToByteArray()), body.take(80))
            }
        }

    @Test fun invalidEntryIdIsNotAChannel() =
        runTest {
            val parsed =
                reader.read(
                    atom("<entry><id>x</id><yt:channelId>UCbad</yt:channelId></entry>").encodeToByteArray(),
                )!!
            assertNull(parsed.channelId)
        }

    private fun atom(entries: String) =
        """
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">
          <id>yt:channel:$other</id><title>Channel feed</title>
          <author><name>Owner &amp; Team</name><uri>https://www.youtube.com/channel/$other</uri></author>
          $entries
        </feed>
        """.trimIndent()
}
