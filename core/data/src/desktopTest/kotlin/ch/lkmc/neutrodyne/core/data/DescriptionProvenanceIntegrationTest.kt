// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeDescriptionLoader
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.domain.DescriptionCompleteness
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptionProvenanceIntegrationTest {
    @Test fun realFullAtomNotesAreReusedWithoutFactsAndKeepLiteralFormat() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel)
                val text = "0:00 First\n0:10 Second &lt;b&gt;literal&lt;/b&gt;\n0:20 Last"
                val parsed =
                    assertIs<ParseResult.Ok>(
                        XmlPullFeedParser.discovered().parse(
                            {
                                Buffer().writeUtf8(
                                    """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">
                <title>Channel</title><entry><id>yt:video:aaaaaaaaaaA</id><yt:videoId>aaaaaaaaaaA</yt:videoId>
                <title>Atom title</title><content type="text">$text</content></entry></feed>""",
                                )
                            },
                            null,
                            YouTubeFeedUrls.canonical("UC" + "0".repeat(21) + "A"),
                        ),
                    )
                assertTrue(parsed.feed.warnings.isEmpty())
                newIngestor(f.db, f.clock, defaultDispatcher = StandardTestDispatcher(testScheduler)).ingest(
                    dueFeedOf(f.db, channel),
                    parsed.feed,
                    IngestContext(IngestMode.REFRESH, true, fetchMeta()),
                )
                val blob = f.db.episodeDao().description(id)!!
                val expected = "0:00 First\n0:10 Second <b>literal</b>\n0:20 Last"
                val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                repeat(2) { assertEquals(expected, loader.load(id)) }
                assertEquals(DescriptionFormat.PLAIN_TEXT, EpisodeDescriptionCodec.decode(blob).format)
                assertTrue(f.engine.factCalls.isEmpty())
                assertContentEquals(blob, f.db.episodeDao().description(id))
            }
        }

    @Test fun failedDescriptionTransactionRetriesWithoutPartialBlobOrDuplicateUpdates() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val first = f.episode(channel)
                val second = f.episode(channel, "bbbbbbbbbbB")
                val before = f.db.episodeDao().byId(first)
                f.db.useWriterConnection { c ->
                    c.usePrepared(
                        "CREATE TRIGGER reject_description BEFORE INSERT ON episode_description WHEN NEW.episodeId = $second BEGIN SELECT RAISE(ABORT, 'description failure'); END",
                    ) { it.step() }
                }
                val facts =
                    listOf(
                        fact("aaaaaaaaaaA", 500).copy(description = "First full"),
                        fact("bbbbbbbbbbB", 600).copy(description = "Second full"),
                    )
                assertFailsWith<Exception> { f.writer.applyFacts(channel, facts) }
                assertNull(f.db.episodeDao().description(first))
                assertNull(f.db.episodeDao().description(second))
                assertEquals(before, f.db.episodeDao().byId(first))
                f.db.useWriterConnection { c -> c.usePrepared("DROP TRIGGER reject_description") { it.step() } }
                f.writer.applyFacts(channel, facts)
                val committed = f.db.episodeDao().byId(first)
                val blob = f.db.episodeDao().description(first)!!
                f.writer.applyFacts(channel, facts)
                assertContentEquals(blob, f.db.episodeDao().description(first))
                assertEquals(committed, f.db.episodeDao().byId(first))
                val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                assertEquals("First full", loader.load(first))
                assertEquals("Second full", loader.load(second))
                assertTrue(f.engine.factCalls.isEmpty())
            }
        }

    @Test fun asyncFactsReturnsItsImmutableSnapshotWhenStorageChangesDuringTheWrite() =
        runTest {
            FactsFixture(this).use { f ->
                val id = f.episode(f.channel())
                f.engine.facts = { VideoFactsResult.Ok(fact(it).copy(description = "Complete facts snapshot")) }
                val writer =
                    object : YouTubeFactsWriter by f.writer {
                        override suspend fun fillDescriptionIfAbsent(
                            episodeId: Long,
                            description: String,
                            completeness: DescriptionCompleteness,
                        ) {
                            f.writer.fillDescriptionIfAbsent(episodeId, description, completeness)
                            f.db.ingestDao().fillYouTubeDescription(
                                episodeId,
                                EpisodeDescriptionCodec.encode(
                                    "Later Atom snapshot",
                                    DescriptionFormat.PLAIN_TEXT,
                                    DescriptionCompleteness.COMPLETE,
                                ),
                                "Later",
                            )
                        }
                    }
                val loader = YouTubeDescriptionLoader(f.db, f.engine, writer, f.clock, backgroundScope)
                assertEquals("Complete facts snapshot", loader.load(id))
                assertEquals(
                    "Later Atom snapshot",
                    EpisodeDescriptionCodec.decode(f.db.episodeDao().description(id)!!).text,
                )
                assertEquals("Later Atom snapshot", loader.load(id))
                assertEquals(1, f.engine.factCalls.size)
            }
        }

    @Test fun obsoleteEpisodeCannotReceiveOrReturnAsyncFacts() =
        runTest {
            FactsFixture(this).use { f ->
                val id = f.episode(f.channel())
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.engine.facts = {
                    entered.complete(Unit)
                    release.await()
                    VideoFactsResult.Ok(fact(it).copy(description = "Obsolete full notes"))
                }
                val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                val load = async { loader.load(id) }
                entered.await()
                f.db.useWriterConnection { c ->
                    c.usePrepared("UPDATE episode SET externalMediaId = 'bbbbbbbbbbB' WHERE id = $id") { it.step() }
                }
                release.complete(Unit)
                assertNull(load.await())
                assertNull(f.db.episodeDao().description(id))
            }
        }

    @Test fun legacyAndPublisherMarkersStayUnknownAndByteIdentical() =
        runTest {
            FactsFixture(this).use { f ->
                val id = f.episode(f.channel())
                val text = "<!-- ND:COMPLETE -->0:00 First\n0:10 Second\n0:20 Last"
                for (blob in listOf(
                    byteArrayOf(),
                    EpisodeDescriptionCodec.encode("", DescriptionFormat.PLAIN_TEXT),
                    text.encodeToByteArray(),
                    EpisodeDescriptionCodec.encode(text, DescriptionFormat.HTML),
                    EpisodeDescriptionCodec.encode(text.repeat(20), DescriptionFormat.PLAIN_TEXT),
                )) {
                    f.db.ingestDao().fillYouTubeDescription(id, blob, "Legacy snippet")
                    val loader = YouTubeDescriptionLoader(f.db, f.engine, f.writer, f.clock, backgroundScope)
                    assertNull(loader.load(id))
                    f.writer.fillDescriptionIfAbsent(id, "Replacement", DescriptionCompleteness.COMPLETE)
                    assertContentEquals(blob, f.db.episodeDao().description(id))
                }
                assertTrue(f.engine.factCalls.isEmpty())
            }
        }
}
