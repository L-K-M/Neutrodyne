// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.playback.ChapterRepositoryImpl
import ch.lkmc.neutrodyne.core.data.playback.Podcasting20ChaptersParser
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeDescriptionLoader
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.DescriptionCompleteness
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real TestDb, ingestion, codec, description loader and writer; only the extraction port is controlled. */
class YouTubeChapterIntegrationTest {
    @Test fun nextCurrentOccurrenceReplacesChangedCompleteNotesWithoutTouchingOtherSources() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                f.store(id)
                f.repo.ensureCurrentLoaded(id)
                val hidden = EpisodeChapter(0, null, "Hidden", null, null, ChapterSource.MP4, true)
                f.repo.replace(id, ChapterSource.MP4, listOf(hidden))
                f.store(id, "0:00 New\n0:11 Complete\n0:22 Snapshot")
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    listOf("New", "Complete", "Snapshot"),
                    f.repo
                        .observe(id)
                        .first()
                        .map { it.title },
                )
                assertEquals(
                    listOf(0L, 11_000L, 22_000L),
                    f.repo
                        .observe(id)
                        .first()
                        .map { it.startMs },
                )
                assertEquals(
                    1,
                    f.data.db
                        .chapterDao()
                        .ofSource(id, ChapterSource.MP4)
                        .size,
                )
                val completeChapters = f.repo.observe(id).first()
                for (completeness in listOf(DescriptionCompleteness.UNKNOWN, DescriptionCompleteness.TRUNCATED)) {
                    val blob = EpisodeDescriptionCodec.encode(NOTES, DescriptionFormat.PLAIN_TEXT, completeness)
                    f.data.db
                        .ingestDao()
                        .fillYouTubeDescription(id, blob, "unproven replacement")
                    f.repo.ensureCurrentLoaded(id)
                    assertEquals(completeChapters, f.repo.observe(id).first())
                    assertContentEquals(
                        blob,
                        f.data.db
                            .episodeDao()
                            .description(id),
                    )
                }
                assertTrue(
                    f.data.engine.factCalls
                        .isEmpty(),
                )
            }
        }

    @Test fun invalidCompleteReplacementCannotLeaveCachedChaptersAsAnApparentValidPrefix() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                f.store(id)
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    3,
                    f.repo
                        .observe(id)
                        .first()
                        .size,
                )
                f.store(id, "$NOTES\n0:20 Duplicate")
                f.repo.ensureCurrentLoaded(id)
                assertTrue(
                    f.repo
                        .observe(id)
                        .first()
                        .isEmpty(),
                )
                assertTrue(
                    f.data.engine.factCalls
                        .isEmpty(),
                )
            }
        }

    @Test fun defaultParserTruncationCannotPromoteItsShortPlausibleChapterPrefix() =
        runTest {
            Fixture(this).use { f ->
                val channel = f.data.channel()
                val id = f.data.episode(channel)
                val xml = """<feed xmlns="http://www.w3.org/2005/Atom"
                xmlns:yt="http://www.youtube.com/xml/schemas/2015"
                xmlns:media="http://search.yahoo.com/mrss/">
                <title>Channel</title><entry><id>yt:video:aaaaaaaaaaA</id>
                <yt:videoId>aaaaaaaaaaA</yt:videoId><title>Atom title</title><media:group>
                <media:description>$NOTES${" ".repeat(512 * 1024)}0:30 Fourth</media:description>
                </media:group></entry></feed>"""
                val parsed =
                    assertIs<ParseResult.Ok>(
                        XmlPullFeedParser.discovered().parse(
                            { Buffer().writeUtf8(xml) },
                            null,
                            YouTubeFeedUrls.canonical("UC" + "0".repeat(21) + "A"),
                        ),
                    )
                assertTrue(
                    parsed.feed.warnings.any {
                        it.code == ch.lkmc.neutrodyne.feeds.model.WarningCode.TEXT_TRUNCATED
                    },
                )
                newIngestor(
                    f.data.db,
                    f.data.clock,
                    defaultDispatcher = StandardTestDispatcher(testScheduler),
                ).ingest(
                    dueFeedOf(f.data.db, channel),
                    parsed.feed,
                    IngestContext(IngestMode.REFRESH, true, fetchMeta()),
                )
                val blob =
                    f.data.db
                        .episodeDao()
                        .description(id)!!
                assertEquals(NOTES, EpisodeDescriptionCodec.decode(blob).text)
                assertEquals(DescriptionCompleteness.TRUNCATED, EpisodeDescriptionCodec.decode(blob).completeness)
                f.repo.ensureCurrentLoaded(id)
                assertTrue(
                    f.repo
                        .observe(id)
                        .first()
                        .isEmpty(),
                )
                assertTrue(
                    f.data.engine.factCalls
                        .isEmpty(),
                )
                assertContentEquals(
                    blob,
                    f.data.db
                        .episodeDao()
                        .description(id),
                )
            }
        }

    @Test fun fullAtomNotesLoadOfflineWithoutFactsAndIdenticalWritesKeepUserState() =
        runTest {
            Fixture(this).use { f ->
                val channel = f.data.channel()
                val id = f.data.episode(channel, duration = 30_000)
                val xml = """<feed xmlns="http://www.w3.org/2005/Atom"
                xmlns:yt="http://www.youtube.com/xml/schemas/2015">
                <title>Channel</title><entry><id>yt:video:aaaaaaaaaaA</id>
                <yt:videoId>aaaaaaaaaaA</yt:videoId><title>Atom title</title>
                <content type="text">$NOTES</content></entry></feed>"""
                val parsed =
                    assertIs<ParseResult.Ok>(
                        XmlPullFeedParser.discovered().parse(
                            { Buffer().writeUtf8(xml) },
                            null,
                            YouTubeFeedUrls.canonical("UC" + "0".repeat(21) + "A"),
                        ),
                    )
                newIngestor(
                    f.data.db,
                    f.data.clock,
                    defaultDispatcher = StandardTestDispatcher(testScheduler),
                ).ingest(
                    dueFeedOf(f.data.db, channel),
                    parsed.feed,
                    IngestContext(IngestMode.REFRESH, true, fetchMeta()),
                )
                val state = EpisodeStateEntity(id, playedAt = 123, playCount = 7, isFavorite = true, updatedAt = 456)
                f.data.db
                    .episodeStateDao()
                    .upsert(state)
                val position = EpisodePositionEntity(id, 11_000, 30_000, PositionSource.STREAM, 456)
                f.data.db
                    .positionDao()
                    .upsert(position)
                f.data.db
                    .queueDao()
                    .insert(QueueEntryEntity(episodeId = id, orderKey = "V", addedAt = 123))
                val queue =
                    f.data.db
                        .queueDao()
                        .entries()
                val before =
                    f.data.db
                        .episodeDao()
                        .byId(id)
                val blob =
                    f.data.db
                        .episodeDao()
                        .description(id)!!
                f.network.setStatus(FakeNetworkMonitor.OFFLINE)
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    listOf(0L, 10_000L, 20_000L),
                    f.repo
                        .observe(id)
                        .first()
                        .map { it.startMs },
                )
                assertEquals(
                    listOf(10_000L, 20_000L, 30_000L),
                    f.repo
                        .observe(id)
                        .first()
                        .map { it.endMs },
                )
                assertTrue(
                    f.repo
                        .observe(id)
                        .first()
                        .all { it.source == ChapterSource.YOUTUBE_DESC },
                )
                f.sql(
                    "CREATE TRIGGER reject_chapter_write BEFORE INSERT ON chapter " +
                        "BEGIN SELECT RAISE(ABORT, 'identical write'); END",
                )
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    state,
                    f.data.db
                        .episodeStateDao()
                        .byEpisode(id),
                )
                assertEquals(
                    position,
                    f.data.db
                        .positionDao()
                        .byEpisode(id),
                )
                assertEquals(
                    queue,
                    f.data.db
                        .queueDao()
                        .entries(),
                )
                assertEquals(
                    before,
                    f.data.db
                        .episodeDao()
                        .byId(id),
                )
                assertContentEquals(
                    blob,
                    f.data.db
                        .episodeDao()
                        .description(id),
                )
                assertTrue(
                    f.data.engine.factCalls
                        .isEmpty(),
                )
            }
        }

    @Test fun genericBrowsingAndDownloadEntryNeverRequestsFacts() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                f.data.engine.facts = { VideoFactsResult.Ok(fact(it).copy(description = NOTES)) }
                repeat(2) { f.repo.ensureLoaded(id) }
                assertTrue(
                    f.data.engine.factCalls
                        .isEmpty(),
                )
                assertTrue(
                    f.repo
                        .observe(id)
                        .first()
                        .isEmpty(),
                )
                f.repo.ensureCurrentLoaded(id)
                assertEquals(1, f.data.engine.factCalls.size)
                assertEquals(
                    3,
                    f.repo
                        .observe(id)
                        .first()
                        .size,
                )
            }
        }

    @Test fun unknownAndTruncatedStoredNotesNeverAuthorizeOrRequestFacts() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                for (completeness in listOf(DescriptionCompleteness.UNKNOWN, DescriptionCompleteness.TRUNCATED)) {
                    val blob = EpisodeDescriptionCodec.encode(NOTES, DescriptionFormat.PLAIN_TEXT, completeness)
                    f.data.db
                        .ingestDao()
                        .fillYouTubeDescription(id, blob, "notes")
                    f.repo.ensureCurrentLoaded(id)
                    assertTrue(
                        f.repo
                            .observe(id)
                            .first()
                            .isEmpty(),
                    )
                    assertContentEquals(
                        blob,
                        f.data.db
                            .episodeDao()
                            .description(id),
                    )
                }
                assertTrue(
                    f.data.engine.factCalls
                        .isEmpty(),
                )
            }
        }

    @Test fun everyHigherSourceWinsBeforeFactsAndDuringAsyncFacts() =
        runTest {
            Fixture(this).use { f ->
                val channel = f.data.channel()
                for ((index, source) in ChapterSource.entries.dropLast(1).withIndex()) {
                    val id = f.data.episode(channel, "${index.toString().repeat(10)}A")
                    val higher = EpisodeChapter(0, null, source.name, null, null, source, false)
                    f.repo.replace(id, source, listOf(higher))
                    f.repo.ensureCurrentLoaded(id)
                    assertEquals(listOf(higher), f.repo.observe(id).first())
                    assertTrue(
                        f.data.engine.factCalls
                            .isEmpty(),
                    )
                    f.repo.replace(id, source, emptyList())
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    f.data.engine.facts = {
                        entered.complete(Unit)
                        release.await()
                        VideoFactsResult.Ok(fact(it).copy(description = NOTES))
                    }
                    val load = async { f.repo.ensureCurrentLoaded(id) }
                    entered.await()
                    f.repo.replace(id, source, listOf(higher))
                    release.complete(Unit)
                    load.await()
                    assertEquals(listOf(higher), f.repo.observe(id).first())
                    assertTrue(
                        f.data.db
                            .chapterDao()
                            .ofSource(id, ChapterSource.YOUTUBE_DESC)
                            .isEmpty(),
                    )
                    f.data.engine.factCalls
                        .clear()
                }
            }
        }

    @Test fun hiddenOnlyHigherSourceKeepsExistingSelectionFallback() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                f.store(id)
                f.repo.replace(
                    id,
                    ChapterSource.PSC,
                    listOf(EpisodeChapter(0, null, "Hidden", null, null, ChapterSource.PSC, true)),
                )
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    3,
                    f.repo
                        .observe(id)
                        .first()
                        .size,
                )
                assertEquals(
                    ChapterSource.YOUTUBE_DESC,
                    f.repo
                        .observe(id)
                        .first()
                        .first()
                        .source,
                )
                assertEquals(
                    1,
                    f.data.db
                        .chapterDao()
                        .ofSource(id, ChapterSource.PSC)
                        .size,
                )
            }
        }

    @Test fun asyncFactsUsesAcquiredSnapshotDespiteMutableBlobReplacement() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                f.data.engine.facts = { VideoFactsResult.Ok(fact(it).copy(description = NOTES)) }
                f.afterDescriptionWrite = { f.store(it, "0:00 Mutable\n0:11 Replacement\n0:22 Wrong") }
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    listOf("Intro", "Next", "End"),
                    f.repo
                        .observe(id)
                        .first()
                        .map { it.title },
                )
                assertTrue(
                    EpisodeDescriptionCodec
                        .decode(
                            f.data.db
                                .episodeDao()
                                .description(id)!!,
                        ).text
                        .contains("Mutable"),
                )
            }
        }

    @Test fun deletedOrReassignedRowsRejectInFlightSnapshot() =
        runTest {
            Fixture(this).use { f ->
                for (mutation in listOf(
                    "DELETE FROM episode WHERE id = ",
                    "UPDATE episode SET externalMediaId = 'bbbbbbbbbbB' WHERE id = ",
                    "UPDATE episode SET podcastId = %s WHERE id = ",
                )) {
                    val channel = f.data.channel(index = f.data.engine.factCalls.size)
                    val other = f.data.channel(index = 20 + f.data.engine.factCalls.size)
                    val id = f.data.episode(channel)
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    f.data.engine.facts = {
                        entered.complete(Unit)
                        release.await()
                        VideoFactsResult.Ok(fact(it).copy(description = NOTES))
                    }
                    val load = async { f.repo.ensureCurrentLoaded(id) }
                    entered.await()
                    f.sql(mutation.replace("%s", other.toString()) + id)
                    release.complete(Unit)
                    load.await()
                    assertTrue(
                        f.repo
                            .observe(id)
                            .first()
                            .isEmpty(),
                    )
                }
            }
        }

    @Test fun cancellationOfLastWaiterCancelsFactsAndAllowsRetry() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                val entered = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                f.data.engine.facts = {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                val load = async { f.repo.ensureCurrentLoaded(id) }
                entered.await()
                load.cancelAndJoin()
                cancelled.await()
                assertTrue(
                    f.repo
                        .observe(id)
                        .first()
                        .isEmpty(),
                )
                assertNull(
                    f.data.db
                        .episodeDao()
                        .description(id),
                )
                f.data.engine.facts = { VideoFactsResult.Ok(fact(it).copy(description = NOTES)) }
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    3,
                    f.repo
                        .observe(id)
                        .first()
                        .size,
                )
            }
        }

    @Test fun failedChapterInsertRollsBackWholeListAndRetriesStoredFactsWithoutRequest() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                f.data.engine.facts = { VideoFactsResult.Ok(fact(it).copy(description = NOTES)) }
                f.sql(
                    "CREATE TRIGGER reject_second_chapter BEFORE INSERT ON chapter WHEN NEW.ordinal = 1 " +
                        "BEGIN SELECT RAISE(ABORT, 'second chapter'); END",
                )
                assertFailsWith<Exception> { f.repo.ensureCurrentLoaded(id) }
                assertTrue(
                    f.repo
                        .observe(id)
                        .first()
                        .isEmpty(),
                )
                f.sql("DROP TRIGGER reject_second_chapter")
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    3,
                    f.repo
                        .observe(id)
                        .first()
                        .size,
                )
                assertEquals(1, f.data.engine.factCalls.size)
            }
        }

    @Test fun offlineAbsenceAndIncompleteFactsLeaveRetryAvailable() =
        runTest {
            Fixture(this).use { f ->
                val id = f.data.episode(f.data.channel())
                f.network.setStatus(FakeNetworkMonitor.OFFLINE)
                f.repo.ensureCurrentLoaded(id)
                assertTrue(
                    f.data.engine.factCalls
                        .isEmpty(),
                )
                f.network.setStatus(FakeNetworkMonitor.ONLINE)
                f.data.engine.facts =
                    { VideoFactsResult.Ok(fact(it).copy(description = NOTES, descriptionTruncated = true)) }
                f.repo.ensureCurrentLoaded(id)
                assertTrue(
                    f.repo
                        .observe(id)
                        .first()
                        .isEmpty(),
                )
                assertNull(
                    f.data.db
                        .episodeDao()
                        .description(id),
                )
                f.data.engine.facts = { VideoFactsResult.Ok(fact(it).copy(description = NOTES)) }
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    3,
                    f.repo
                        .observe(id)
                        .first()
                        .size,
                )
            }
        }

    private class Fixture(
        val scope: TestScope,
    ) : AutoCloseable {
        val data = FactsFixture(scope)
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        private val clients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent())
        var afterDescriptionWrite: suspend (Long) -> Unit = {}
        private val writer =
            object : YouTubeFactsWriter by data.writer {
                override suspend fun fillDescriptionIfAbsent(
                    episodeId: Long,
                    description: String,
                    completeness: DescriptionCompleteness,
                ) {
                    data.writer.fillDescriptionIfAbsent(episodeId, description, completeness)
                    afterDescriptionWrite(episodeId)
                }
            }
        val repo =
            ChapterRepositoryImpl(
                data.db,
                Podcasting20ChaptersParser(clients),
                network,
                data.clock,
                YouTubeDescriptionLoader(data.db, data.engine, writer, data.clock, scope.backgroundScope),
            )

        suspend fun store(
            id: Long,
            text: String = NOTES,
        ) {
            data.db.ingestDao().fillYouTubeDescription(
                id,
                EpisodeDescriptionCodec.encode(text, DescriptionFormat.PLAIN_TEXT, DescriptionCompleteness.COMPLETE),
                "notes",
            )
        }

        suspend fun sql(sql: String) {
            data.db.useWriterConnection { it.execSql(sql) }
        }

        override fun close() {
            clients.close()
            data.close()
        }
    }

    private companion object {
        const val NOTES = "0:00 Intro\n0:10 Next\n0:20 End"
    }
}
