// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.ExistingEpisodeKey
import ch.lkmc.neutrodyne.core.database.SqliteDriverBindings
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import org.junit.Rule
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAN M1 AC2: real HTTP, RSS adapter, refresher and Room, including persisted failure policy. */
class FeedRefreshContractTest {
    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val clock = TestClock()
    private val driver = DiffCountingDriver()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val parser = CountingParser()
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-refresh-contract")
            .toFile()

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private suspend fun seedFeed(body: String = rssBody(items = arrayOf(rssItem("ep-1")))): Pair<Long, Long> {
        val id =
            seedPodcast(
                db,
                feedUrl = server.url("/feed.xml").toString(),
                subscribedAt = NOW - 30 * DAY_MS,
                nextRefreshAt = NOW - 1,
                etag = ETAG_V1,
                lastModified = LAST_MODIFIED,
                contentSha256 = body.encodeUtf8().sha256().hex(),
                parserVersion = FeedParser.VERSION,
                // A recent full fetch and successful parse keep validators enabled.
                lastParseOk = true,
                lastFullFetchAt = NOW - DAY_MS,
                lastSuccessAt = NOW - DAY_MS,
                latestEpisodeAt = EP_SORT_DATE,
            )
        val epId =
            db
                .ingestDao()
                .insertEpisodes(
                    listOf(
                        episodeEntity(
                            podcastId = id,
                            identityKey = "g:ep-1",
                            title = "Pilot",
                            sortDate = EP_SORT_DATE,
                        ) {
                            copy(
                                guid = "ep-1",
                                enclosureUrl = "https://cdn.example.com/ep-1.mp3",
                                enclosureType = "audio/mpeg",
                                enclosureLength = 12_345,
                                contentHash = 0x2A,
                            )
                        },
                    ),
                ).single()
        db.episodeStateDao().upsert(episodeStateEntity(epId, playedAt = PLAYED_AT, updatedAt = PLAYED_AT))
        db.useWriterConnection { conn ->
            conn.usePrepared("INSERT INTO episode_description (episodeId, html) VALUES (?, ?)") {
                it.bindLong(1, epId)
                it.bindBlob(2, DESC_BYTES)
                it.step()
            }
        }
        return id to epId
    }

    private suspend fun forbidEpisodeWrites() {
        // Row equality cannot detect an UPDATE that rewrites identical values.
        db.useWriterConnection { conn ->
            for (op in listOf("INSERT", "UPDATE", "DELETE")) {
                conn.execSql(
                    "CREATE TRIGGER forbid_ep_${op.lowercase()} BEFORE $op ON episode" +
                        " BEGIN SELECT RAISE(ABORT, 'unexpected episode $op'); END",
                )
            }
        }
    }

    private fun request() =
        RefreshRequest(
            scope = RefreshScope.All,
            force = false,
            pagesOnly = false,
            origin = RefreshOrigin.PERIODIC,
            deadlineElapsedMs = RefreshRequest.NO_DEADLINE,
            dueSlackMs = 0,
            pagingBudgetMs = 0,
        )

    private suspend fun refresh(): Map<Long, FeedOutcome> =
        newRssAdapter(root, clock, parser = parser).use { bundle ->
            newRefresher(
                db,
                mapOf(SourceType.RSS to bundle.adapter),
                clock,
                tempFiles = bundle.tempFiles,
            ).run(request()).outcomes
        }

    private suspend fun episodeSide(
        podcastId: Long,
        epId: Long,
    ) = EpisodeSide(
        episode = assertNotNull(db.episodeDao().byId(epId)),
        state = assertNotNull(db.episodeStateDao().byEpisode(epId)),
        description = assertNotNull(db.episodeDao().observeDescription(epId).first()),
        existing = db.ingestDao().existing(podcastId),
    )

    private data class EpisodeSide(
        val episode: EpisodeEntity,
        val state: EpisodeStateEntity,
        val description: ByteArray,
        val existing: List<ExistingEpisodeKey>,
    )

    private fun assertEpisodeSideSame(
        before: EpisodeSide,
        after: EpisodeSide,
    ) {
        assertEquals(before.episode, after.episode)
        assertEquals(before.state, after.state)
        assertContentEquals(before.description, after.description)
        assertEquals(before.existing, after.existing)
    }

    private fun assertConditionalRequest() {
        val recorded = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", recorded.method)
        assertEquals("/feed.xml", recorded.url.encodedPath)
        assertEquals(ETAG_V1, recorded.headers["If-None-Match"])
        assertEquals(LAST_MODIFIED, recorded.headers["If-Modified-Since"])
    }

    @Test
    fun changedBodyParsesDiffsAndPersistsThroughRoom() =
        runTest {
            val (id, epId) = seedFeed()
            val state = assertNotNull(db.episodeStateDao().byEpisode(epId))
            val body = rssBody(items = arrayOf(rssItem("ep-1", title = "Pilot v2"), rssItem("ep-2")))
            server.enqueue(mockResponse(body = body))

            assertIs<FeedOutcome.Ingested>(refresh()[id])

            // Positive control for the counters used by the shortcut tests.
            assertEquals(1, parser.callCount)
            assertEquals(1, driver.diffReadCount)
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(body.encodeUtf8().sha256().hex(), stored.contentSha256)
            assertEquals(FeedParser.VERSION, stored.parserVersion)
            assertEquals(NOW, stored.lastAttemptAt)
            assertEquals(NOW, stored.lastSuccessAt)
            assertEquals(NOW + 240 * MINUTE_MS, stored.nextRefreshAt)
            assertEquals(2, db.podcastDao().episodeCount(id))
            assertEquals("Pilot v2", db.episodeDao().byId(epId)?.title)
            assertNotNull(db.episodeDao().byIdentityKey(id, "g:ep-2"))
            assertEquals(state, db.episodeStateDao().byEpisode(epId))
        }

    @Test
    fun conditional304WritesNoEpisodesAndPersistsSuccess() =
        runTest {
            val (id, epId) = seedFeed()
            forbidEpisodeWrites()
            val before = episodeSide(id, epId)
            val diffReads = driver.diffReadCount
            server.enqueue(mockResponse(code = 304, body = ""))

            assertEquals(FeedOutcome.NotModified, refresh()[id])

            assertConditionalRequest()
            assertEquals(0, parser.callCount)
            assertEquals(diffReads, driver.diffReadCount)
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(NOW, stored.lastAttemptAt)
            assertEquals(NOW, stored.lastSuccessAt)
            assertEquals(NOW + 240 * MINUTE_MS, stored.nextRefreshAt)
            assertEquals(ETAG_V1, stored.etag)
            assertEquals(LAST_MODIFIED, stored.lastModified)
            assertEquals(NOW - DAY_MS, stored.lastFullFetchAt)
            assertEquals(FeedParser.VERSION, stored.parserVersion)
            assertFalse(stored.gone)
            assertFalse(stored.needsCredentials)
            assertNull(stored.lastErrorKind)
            assertEquals(0, stored.failureCount)
            assertEpisodeSideSame(before, episodeSide(id, epId))
        }

    @Test
    fun byteIdentical200SkipsParseDiffAndEpisodeWrites() =
        runTest {
            val body = rssBody(items = arrayOf(rssItem("ep-1")))
            val (id, epId) = seedFeed(body)
            forbidEpisodeWrites()
            val before = episodeSide(id, epId)
            val diffReads = driver.diffReadCount
            server.enqueue(mockResponse(200, body, "ETag" to ETAG_V2))

            assertEquals(FeedOutcome.Unchanged, refresh()[id])

            assertConditionalRequest()
            assertEquals(0, parser.callCount)
            assertEquals(diffReads, driver.diffReadCount, "identical bytes must not enter the diff")
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(NOW, stored.lastAttemptAt)
            assertEquals(NOW, stored.lastSuccessAt)
            assertEquals(NOW + 240 * MINUTE_MS, stored.nextRefreshAt)
            assertEquals(ETAG_V2, stored.etag)
            assertEquals(LAST_MODIFIED, stored.lastModified)
            assertEquals(NOW - DAY_MS, stored.lastFullFetchAt)
            assertEquals(body.encodeUtf8().sha256().hex(), stored.contentSha256)
            assertNull(stored.lastErrorKind)
            assertEquals(0, stored.failureCount)
            assertEpisodeSideSame(before, episodeSide(id, epId))
        }

    @Test
    fun html200PersistsErrorAndPreservesEpisodeAndState() =
        runTest {
            val (id, epId) = seedFeed()
            val before = episodeSide(id, epId)
            val podcast = assertNotNull(db.podcastDao().byId(id))
            server.enqueue(
                mockResponse(200, "<html><body>not a feed</body></html>", "ETag" to ETAG_V2),
            )

            assertEquals(FeedErrorKind.NOT_A_FEED, assertIs<FeedOutcome.Failed>(refresh()[id]).kind)

            assertEquals(0, parser.callCount)
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(NOW, stored.lastAttemptAt)
            assertEquals(podcast.lastSuccessAt, stored.lastSuccessAt)
            assertEquals(1, stored.failureCount)
            assertEquals(FeedErrorKind.NOT_A_FEED, stored.lastErrorKind)
            assertTrue(stored.nextRefreshAt in (NOW + 24 * MINUTE_MS)..(NOW + 36 * MINUTE_MS))
            assertEquals(podcast.etag, stored.etag)
            assertEquals(podcast.contentSha256, stored.contentSha256)
            assertTrue(stored.lastParseOk)
            assertFalse(stored.gone)
            assertFalse(stored.needsCredentials)
            assertEpisodeSideSame(before, episodeSide(id, epId))
        }

    @Test
    fun http410PersistsGoneAndExcludesFurtherRefresh() =
        runTest {
            val (id, epId) = seedFeed()
            val before = episodeSide(id, epId)
            server.enqueue(mockResponse(code = 410, body = ""))

            val outcome = assertIs<FeedOutcome.Failed>(refresh()[id])
            assertEquals(FeedErrorKind.HTTP_GONE, outcome.kind)
            assertEquals(410, outcome.httpStatus)
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertTrue(stored.gone)
            assertFalse(stored.needsCredentials)
            assertEquals(NOW, stored.lastAttemptAt)
            assertEquals(1, stored.failureCount)
            assertEquals(FeedErrorKind.HTTP_GONE, stored.lastErrorKind)
            assertEquals(NOW - 1, stored.nextRefreshAt)
            assertTrue(db.podcastDao().dueForRefresh(NOW + DAY_MS, scopeAll = true).isEmpty())
            assertTrue(refresh().isEmpty())
            assertEquals(1, server.requestCount)
            assertEpisodeSideSame(before, episodeSide(id, epId))
        }

    @Test
    fun basic401PersistsNeedsCredentials() = runTest { assertBasicChallenge(401) }

    @Test
    fun basic403PersistsNeedsCredentials() = runTest { assertBasicChallenge(403) }

    private suspend fun assertBasicChallenge(code: Int) {
        val (id, epId) = seedFeed()
        val before = episodeSide(id, epId)
        server.enqueue(mockResponse(code = code, body = "", "WWW-Authenticate" to "Basic realm=\"feeds\""))

        val outcome = assertIs<FeedOutcome.Failed>(refresh()[id])
        assertEquals(FeedErrorKind.HTTP_AUTH, outcome.kind)
        assertEquals(code, outcome.httpStatus)
        val stored = assertNotNull(db.podcastDao().byId(id))
        assertTrue(stored.needsCredentials)
        assertFalse(stored.gone)
        assertEquals(FeedErrorKind.HTTP_AUTH, stored.lastErrorKind)
        assertEquals(1, stored.failureCount)
        assertEquals(NOW, stored.lastAttemptAt)
        assertEquals(NOW - 1, stored.nextRefreshAt)
        assertTrue(db.podcastDao().dueForRefresh(NOW + DAY_MS, scopeAll = true).isEmpty())
        assertTrue(refresh().isEmpty())
        assertEquals(1, server.requestCount)
        assertEpisodeSideSame(before, episodeSide(id, epId))
    }

    @Test
    fun retryAfterSecondsDefers503UntilStoredDeadline() = runTest { assertRetryAfter(503, "21600") }

    @Test
    fun retryAfterHttpDateDefers429UntilStoredDeadline() =
        runTest { assertRetryAfter(429, "Sun, 04 Oct 2026 06:00:00 GMT") }

    private suspend fun assertRetryAfter(
        code: Int,
        header: String,
    ) {
        val (id, epId) = seedFeed()
        val before = episodeSide(id, epId)
        // Six hours exceeds every first-failure backoff jitter (24 to 36 minutes).
        val nextRefresh = NOW + 360 * MINUTE_MS
        val kind = if (code == 429) FeedErrorKind.HTTP_RATE_LIMITED else FeedErrorKind.HTTP_SERVER
        server.enqueue(mockResponse(code = code, body = "", "Retry-After" to header))

        assertEquals(kind, assertIs<FeedOutcome.Failed>(refresh()[id]).kind)
        val stored = assertNotNull(db.podcastDao().byId(id))
        assertEquals(nextRefresh, stored.nextRefreshAt)
        assertEquals(NOW, stored.lastAttemptAt)
        assertEquals(NOW - DAY_MS, stored.lastSuccessAt)
        assertEquals(1, stored.failureCount)
        assertEquals(kind, stored.lastErrorKind)
        assertFalse(stored.gone)
        assertFalse(stored.needsCredentials)
        assertEpisodeSideSame(before, episodeSide(id, epId))

        clock.nowMs = nextRefresh - 1
        assertTrue(refresh().isEmpty())
        assertEquals(1, server.requestCount)
        clock.nowMs = nextRefresh
        server.enqueue(mockResponse(code = 304, body = ""))
        assertEquals(FeedOutcome.NotModified, refresh()[id])
        assertEquals(2, server.requestCount)
        val recovered = assertNotNull(db.podcastDao().byId(id))
        assertEquals(nextRefresh, recovered.lastSuccessAt)
        assertEquals(nextRefresh + 240 * MINUTE_MS, recovered.nextRefreshAt)
        assertEquals(0, recovered.failureCount)
        assertNull(recovered.lastErrorKind)
        assertEpisodeSideSame(before, episodeSide(id, epId))
    }

    private class CountingParser : FeedParser {
        private val delegate = XmlPullFeedParser.discovered()
        private val calls = AtomicInteger()
        val callCount get() = calls.get()

        override fun parse(
            open: () -> Source,
            httpCharset: String?,
            baseUrl: String,
            limits: ParseLimits,
        ): ParseResult {
            calls.incrementAndGet()
            return delegate.parse(open, httpCharset, baseUrl, limits)
        }
    }

    private class DiffCountingDriver(
        private val delegate: SQLiteDriver = SqliteDriverBindings.sqliteDriver(),
    ) : SQLiteDriver by delegate {
        private val reads = AtomicInteger()
        val diffReadCount get() = reads.get()

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    // IngestDao.existing is the diff's mandatory stored-identity read. Counting
                    // execution also catches a reused prepared statement; snapshots are taken
                    // outside the measured refresh window. The changed-body test calibrates it.
                    if (!sql.startsWith("SELECT id, identityKey, guid,") ||
                        !sql.contains("FROM episode WHERE podcastId")
                    ) {
                        return statement
                    }
                    return object : SQLiteStatement by statement {
                        private var started = false

                        override fun step(): Boolean {
                            if (!started) {
                                reads.incrementAndGet()
                                started = true
                            }
                            return statement.step()
                        }

                        override fun reset() {
                            statement.reset()
                            started = false
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS
        const val ETAG_V1 = "W/\"v1\""
        const val ETAG_V2 = "\"v2\""
        const val LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT"
        const val EP_SORT_DATE = NOW - 60 * MINUTE_MS
        const val PLAYED_AT = NOW - 1_000L
        val DESC_BYTES = byteArrayOf(0x48, 0x54, 0x4D, 0x4C)
    }
}
