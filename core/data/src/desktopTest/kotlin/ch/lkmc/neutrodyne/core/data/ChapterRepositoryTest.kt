// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.playback.ChapterRepositoryImpl
import ch.lkmc.neutrodyne.core.data.playback.Podcasting20ChaptersParser
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChapterRepositoryTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val clock = TestClock()
    private val db = newDb(clock)
    private val clients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent())
    private val client = clients.client(HttpClientKind.API)
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val repo =
        ChapterRepositoryImpl(
            db,
            Podcasting20ChaptersParser(
                object : NeutrodyneHttpClients {
                    override fun client(kind: HttpClientKind) = client
                },
            ),
            network,
            clock,
        )

    @After fun close() {
        clients.close()
        db.close()
    }

    @Test fun prioritySourceIsolationAndHiddenFallback() =
        runBlocking {
            val id = seed()
            for (source in listOf(
                ChapterSource.MP4,
                ChapterSource.ID3,
                ChapterSource.PSC,
                ChapterSource.PODCASTING20_JSON,
            )) {
                repo.replace(id, source, listOf(row(source)))
                assertEquals(
                    source,
                    repo
                        .observe(id)
                        .first()
                        .single()
                        .source,
                )
            }
            repo.replace(
                id,
                ChapterSource.PODCASTING20_JSON,
                listOf(row(ChapterSource.PODCASTING20_JSON).copy(hidden = true)),
            )
            assertEquals(
                ChapterSource.PSC,
                repo
                    .observe(id)
                    .first()
                    .single()
                    .source,
            )
            assertEquals(1, db.chapterDao().ofSource(id, ChapterSource.ID3).size)
        }

    @Test fun noOpDoesNotWriteAndFailureRollsBack() =
        runBlocking {
            val id = seed()
            val rows = listOf(row())
            repo.replace(id, ChapterSource.PSC, rows)
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER reject_chapter_delete BEFORE DELETE ON chapter " +
                        "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                )
            }
            repo.replace(id, ChapterSource.PSC, rows)
            assertFailsWith<Exception> { repo.replace(id, ChapterSource.PSC, listOf(row().copy(title = "changed"))) }
            assertEquals(rows, repo.observe(id).first())
            db.useWriterConnection {
                it.execSql("DROP TRIGGER reject_chapter_delete")
                it.execSql(
                    "CREATE TRIGGER reject_chapter_insert BEFORE INSERT ON chapter " +
                        "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                )
            }
            assertFailsWith<Exception> {
                repo.replace(id, ChapterSource.PSC, listOf(row().copy(startMs = 10)))
            }
            assertEquals(rows, repo.observe(id).first())
        }

    @Test fun changedListsEmitAndCascadeEmitsEmpty() =
        runBlocking {
            val id = seed()
            val changed = async(start = CoroutineStart.UNDISPATCHED) { repo.observe(id).first { it.isNotEmpty() } }
            repo.replace(id, ChapterSource.PSC, listOf(row()))
            assertEquals(listOf(row()), changed.await())
            val deleted = async(start = CoroutineStart.UNDISPATCHED) { repo.observe(id).first { it.isEmpty() } }
            db.podcastDao().deleteCascade(db.episodeDao().byId(id)!!.podcastId, clock.now())
            assertTrue(deleted.await().isEmpty())
            repo.replace(id, ChapterSource.ID3, listOf(row(ChapterSource.ID3)))
            assertTrue(repo.observe(id).first().isEmpty())
        }

    @Test fun mismatchedSourcesRejectedBeforeMutation() =
        runBlocking {
            val id = seed()
            assertFailsWith<IllegalArgumentException> { repo.replace(id, ChapterSource.ID3, listOf(row())) }
            assertTrue(repo.observe(id).first().isEmpty())
        }

    @Test fun offlineDoesNotConsumeAttemptAndCacheWorksOffline() =
        runBlocking {
            val id = seed()
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            repo.replace(id, ChapterSource.PSC, listOf(row()))
            repo.ensureLoaded(id)
            assertEquals(0, serverRule.server.requestCount)
            network.setStatus(FakeNetworkMonitor.ONLINE)
            respond()
            repo.ensureLoaded(id)
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            assertEquals(
                ChapterSource.PODCASTING20_JSON,
                repo
                    .observe(id)
                    .first()
                    .single()
                    .source,
            )
        }

    @Test fun persistedJsonSurvivesRepositoryRecreationWithoutNetwork() =
        runBlocking {
            val id = seed()
            respond()
            repo.ensureLoaded(id)
            val another =
                ChapterRepositoryImpl(
                    db,
                    Podcasting20ChaptersParser(
                        object : NeutrodyneHttpClients {
                            override fun client(kind: HttpClientKind) = client
                        },
                    ),
                    network,
                    clock,
                )
            another.ensureLoaded(id)
            assertEquals(1, serverRule.server.requestCount)
            assertEquals(
                ChapterSource.PODCASTING20_JSON,
                another
                    .observe(id)
                    .first()
                    .single()
                    .source,
            )
        }

    @Test fun failuresPreserveLowerSourcesAndBackOffFor24Hours() =
        runBlocking {
            val id = seed()
            repo.replace(id, ChapterSource.PSC, listOf(row()))
            serverRule.server.enqueue(MockResponse.Builder().code(500).build())
            repo.ensureLoaded(id)
            repo.ensureLoaded(id)
            assertEquals(1, serverRule.server.requestCount)
            assertEquals(
                ChapterSource.PSC,
                repo
                    .observe(id)
                    .first()
                    .single()
                    .source,
            )
            clock.elapsedMs += 24 * 60 * 60 * 1000L
            respond()
            repo.ensureLoaded(id)
            assertEquals(2, serverRule.server.requestCount)
        }

    @Test fun changedUrlClearsFailedAttemptAllowance() =
        runBlocking {
            val id = seed()
            serverRule.server.enqueue(MockResponse.Builder().code(500).build())
            repo.ensureLoaded(id)
            setUrl(id, serverRule.server.url("/replacement").toString())
            respond()
            repo.ensureLoaded(id)
            assertEquals(2, serverRule.server.requestCount)
            assertEquals(
                ChapterSource.PODCASTING20_JSON,
                repo
                    .observe(id)
                    .first()
                    .single()
                    .source,
            )
        }

    @Test fun concurrentCallsCoalesce() =
        runBlocking {
            val id = seed()
            respond(delay = true)
            val first = async { repo.ensureLoaded(id) }
            request()
            val second = async { repo.ensureLoaded(id) }
            first.await()
            second.await()
            assertEquals(1, serverRule.server.requestCount)
        }

    @Test fun urlChangeInvalidatesInflightAndResetsBackoff() =
        runBlocking {
            val id = seed()
            respond(delay = true)
            val old = async { repo.ensureLoaded(id) }
            request()
            setUrl(id, serverRule.server.url("/new").toString())
            old.await()
            assertTrue(repo.observe(id).first().isEmpty())
            respond()
            repo.ensureLoaded(id)
            assertEquals(
                ChapterSource.PODCASTING20_JSON,
                repo
                    .observe(id)
                    .first()
                    .single()
                    .source,
            )
        }

    @Test fun unsubscribeCannotResurrectInflightRows() =
        runBlocking {
            val id = seed()
            respond(delay = true)
            val old = async { repo.ensureLoaded(id) }
            request()
            db.podcastDao().deleteCascade(db.episodeDao().byId(id)!!.podcastId, clock.now())
            old.await()
            assertTrue(repo.observe(id).first().isEmpty())
        }

    @Test fun cancellationDoesNotBackOff() =
        runBlocking {
            val id = seed()
            respond(delay = true)
            val old = async { repo.ensureLoaded(id) }
            request()
            old.cancelAndJoin()
            respond()
            repo.ensureLoaded(id)
            assertEquals(2, serverRule.server.requestCount)
        }

    @Test fun callerTimeoutDoesNotConsumeRetryAllowance() =
        runBlocking {
            val id = seed()
            val response =
                MockResponse
                    .Builder()
                    .body("{}")
                    .bodyDelay(2, TimeUnit.SECONDS)
                    .build()
            serverRule.server.enqueue(response)
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                kotlinx.coroutines.withTimeout(1_000) { repo.ensureLoaded(id) }
            }
            assertEquals(1, serverRule.server.requestCount)
            respond()
            repo.ensureLoaded(id)
            assertEquals(2, serverRule.server.requestCount)
        }

    @Test fun successfulEmptyAndHiddenOnlyPreserveFallback() =
        runBlocking {
            val id = seed()
            repo.replace(id, ChapterSource.PSC, listOf(row()))
            for (chapters in listOf("[]", """[{"startTime":0,"toc":false}]""")) {
                val body = """{"version":"1","chapters":$chapters}"""
                serverRule.server.enqueue(MockResponse.Builder().body(body).build())
                repo.ensureLoaded(id)
                assertEquals(
                    ChapterSource.PSC,
                    repo
                        .observe(id)
                        .first()
                        .single()
                        .source,
                )
            }
        }

    @Test fun existingPscFixtureFlowsThroughRealIngestAndRepository() =
        runBlocking {
            val root = checkNotNull(System.getProperty("neutrodyne.rootDir"))
            val xml = java.io.File(root, "feeds/src/test/resources/feeds/psc-chapters.xml").readText()
            val parser = XmlPullFeedParser.discovered()
            val parsed =
                parser.parse({ okio.Buffer().writeUtf8(xml) }, null, "https://example.test/feed")
                    as ParseResult.Ok
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            newIngestor(db, clock).ingest(
                dueFeedOf(db, podcast),
                parsed.feed,
                IngestContext(
                    IngestMode.INITIAL,
                    false,
                    fetchMeta(),
                ),
            )
            val episode = db.episodeDao().byIdentityKey(podcast, "g:psc-1")!!
            val starts = repo.observe(episode.id).first().map { it.startMs }
            assertEquals(listOf(0L, 42_000L, 1_875_250L, 3_700_000L), starts)
        }

    private suspend fun seed(): Long {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        ingestUrl(podcast, serverRule.server.url("/chapters").toString(), IngestMode.INITIAL)
        return db.episodeDao().byIdentityKey(podcast, "g:chapter-test")!!.id
    }

    private suspend fun setUrl(
        id: Long,
        url: String,
    ) {
        ingestUrl(db.episodeDao().byId(id)!!.podcastId, url, IngestMode.REFRESH)
    }

    private suspend fun ingestUrl(
        podcast: Long,
        url: String,
        mode: IngestMode,
    ) {
        newIngestor(db, clock).ingest(
            dueFeedOf(db, podcast),
            parsedFeed(items = listOf(parsedEpisode(0, guid = "chapter-test", chaptersUrl = url))),
            IngestContext(mode, false, fetchMeta()),
        )
    }

    private fun respond(delay: Boolean = false) {
        val body = """{"version":"1","chapters":[{"startTime":0,"title":"JSON"}]}"""
        val response = MockResponse.Builder().body(body)
        if (delay) response.bodyDelay(300, TimeUnit.MILLISECONDS)
        serverRule.server.enqueue(response.build())
    }

    private suspend fun request() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            serverRule.server.takeRequest()
        }

    private fun row(source: ChapterSource = ChapterSource.PSC) =
        EpisodeChapter(0, null, "chapter", null, null, source, false)
}
