// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.ingest.IngestionEventBus
import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.FeedRefresher
import ch.lkmc.neutrodyne.core.data.refresh.RefreshControllerImpl
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshReport
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Rule
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M1 AC8: real HTTP, RSS parsing, paging sessions and transactional Room writes. */
class PagingTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val clock = TestClock()
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()
    private val scheduler = FakeRefreshScheduler()
    private val eventBus = IngestionEventBus()
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-paging")
            .toFile()

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun fiftyPageBudgetKeepsTheCursorAndLoadOlderResumesInANewRun() =
        runTest {
            val id = subscribe()
            for (page in 2..LAST_BUDGET_PAGE) enqueuePage(page, page + 1)

            val first = run(scheduledRequest())

            assertIs<FeedOutcome.Ingested>(first.outcomes[id])
            assertCursor(id, LAST_BUDGET_PAGE + 1, PagingState.STOPPED)
            assertEquals(1 + PAGE_BUDGET, db.podcastDao().episodeCount(id))
            assertRequests((1..LAST_BUDGET_PAGE).toList())

            enqueuePage(LAST_BUDGET_PAGE + 1, LAST_BUDGET_PAGE + 2)
            enqueuePage(LAST_BUDGET_PAGE + 2)
            val resumed = loadOlder(id)

            assertIs<FeedOutcome.Ingested>(resumed.outcomes[id])
            assertCursor(id, null, PagingState.STOPPED)
            assertEquals(1 + PAGE_BUDGET + 2, db.podcastDao().episodeCount(id))
            assertRequests(listOf(LAST_BUDGET_PAGE + 1, LAST_BUDGET_PAGE + 2))
        }

    @Test
    fun subscribeBackfillStopsAfterCrossingTheItemCapAndManualPagingContinues() =
        runTest {
            val id = subscribe()
            seedEpisodeCount(id, AUTOMATIC_ITEM_CAP - 1)
            enqueuePage(2, 3, "cross-a", "cross-b")

            run(scheduledRequest())

            assertEquals(AUTOMATIC_ITEM_CAP + 1, db.podcastDao().episodeCount(id))
            assertCursor(id, 3, PagingState.STOPPED)
            assertRequests(listOf(1, 2))

            enqueuePage(3, 4, "manual-a", "manual-b")
            enqueuePage(4, items = arrayOf("manual-c"))
            loadOlder(id)

            assertEquals(AUTOMATIC_ITEM_CAP + 4, db.podcastDao().episodeCount(id))
            assertCursor(id, null, PagingState.STOPPED)
            assertRequests(listOf(3, 4))
        }

    @Test
    fun backgroundPagingUsesTheAutomaticCapEvenInAManualPageOneRefresh() =
        runTest {
            val id = subscribe()
            seedEpisodeCount(id, AUTOMATIC_ITEM_CAP - 1)
            server.enqueue(mockResponse(304))
            enqueuePage(2, 3, "cross-a", "cross-b")

            run(refreshRequest(id, RefreshOrigin.MANUAL))

            assertEquals(AUTOMATIC_ITEM_CAP + 1, db.podcastDao().episodeCount(id))
            assertCursor(id, 3, PagingState.STOPPED)
            assertRequests(listOf(1, 1, 2))
        }

    @Test
    fun httpFailureKeepsTheFailedPageCursorAndPageOneErrorState() =
        runTest {
            assertFailedPageRetries(mockResponse(500), FeedOutcome.Failed(FeedErrorKind.HTTP_SERVER, 500))
        }

    @Test
    fun parseFailureKeepsTheFailedPageCursorAndPageOneErrorState() =
        runTest {
            // The tolerant XML parser accepts truncated tags; an unknown charset is rejected.
            val malformed =
                "<?xml version=\"1.0\" encoding=\"not-a-charset\"?><rss version=\"2.0\"><channel/></rss>"
            assertFailedPageRetries(mockResponse(body = malformed), FeedOutcome.Failed(FeedErrorKind.PARSE_ERROR, null))
        }

    private suspend fun assertFailedPageRetries(
        failure: MockResponse,
        expected: FeedOutcome.Failed,
    ) {
        val id =
            seedPodcast(
                db,
                url(1),
                pagingNextUrl = url(2),
                pagingComplete = false,
                failureCount = 3,
                lastAttemptAt = clock.now() - 1,
                lastParseOk = false,
            ) {
                copy(lastErrorKind = FeedErrorKind.HTTP_NOT_FOUND, lastErrorDetail = "Page one failed")
            }
        val before = assertNotNull(db.podcastDao().byId(id))
        enqueuePage(2, 3)
        server.enqueue(failure)

        val report = run(pagesRequest(id, RefreshOrigin.SUBSCRIBE))

        assertEquals(expected, report.outcomes[id])
        assertCursor(id, 3, PagingState.PENDING)
        // Page one owns health and scheduling; older-page failures appear in the run outcome.
        assertEquals(before.copy(pagingNextUrl = url(3)), db.podcastDao().byId(id))
        assertEquals(listOf("episode-2"), episodes(id).map { it.guid })
        assertRequests(listOf(2, 3))

        enqueuePage(3, 4)
        enqueuePage(4)
        run(pagesRequest(id, RefreshOrigin.CONTINUATION))

        assertCursor(id, null, PagingState.STOPPED)
        assertEquals(setOf("episode-2", "episode-3", "episode-4"), episodes(id).map { it.guid }.toSet())
        assertEquals(before.copy(pagingNextUrl = null, pagingComplete = true), db.podcastDao().byId(id))
        assertRequests(listOf(3, 4))
    }

    @Test
    fun deadlineStopPersistsTheCommittedPageAndResumesAtTheNextPage() =
        runTest {
            val id = subscribe()
            enqueuePage(2, 3)
            var hookRuns = 0
            val hookFailures = mutableListOf<Throwable>()
            val hook =
                SyncIngestHook { podcastId ->
                    hookRuns++
                    // The engine logs and swallows hook failures (03 step 11), so the hook records
                    // its own: after commit, the inserted episode and its cursor must be visible.
                    runCatching {
                        assertEquals(id, podcastId)
                        assertEquals(2, db.podcastDao().episodeCount(id))
                        assertCursor(id, 3, PagingState.PENDING)
                    }.onFailure { hookFailures += it }
                    clock.elapsedMs = DEADLINE_MS - PAGING_MARGIN_MS
                }

            run(scheduledRequest().copy(deadlineElapsedMs = DEADLINE_MS), hook)

            assertEquals(1, hookRuns)
            hookFailures.firstOrNull()?.let { throw it }

            assertCursor(id, 3, PagingState.PENDING)
            assertEquals(2, db.podcastDao().episodeCount(id))
            assertRequests(listOf(1, 2))

            enqueuePage(3)
            run(pagesRequest(id, RefreshOrigin.CONTINUATION))

            assertCursor(id, null, PagingState.STOPPED)
            assertEquals(3, db.podcastDao().episodeCount(id))
            assertRequests(listOf(3))
        }

    @Test
    fun pagingBudgetStopPersistsTheNextPageForBackgroundContinuation() =
        runTest {
            val id = subscribe()
            enqueuePage(2, 3)
            val hook = SyncIngestHook { clock.elapsedMs = PAGING_BUDGET_MS }
            val request =
                refreshRequest(id, RefreshOrigin.PERIODIC).copy(force = false, pagingBudgetMs = PAGING_BUDGET_MS)

            run(request, hook)

            assertCursor(id, 3, PagingState.PENDING)
            assertEquals(2, db.podcastDao().episodeCount(id))
            assertRequests(listOf(1, 2))

            enqueuePage(3)
            run(request)

            assertCursor(id, null, PagingState.STOPPED)
            assertEquals(3, db.podcastDao().episodeCount(id))
            assertRequests(listOf(3))
        }

    @Test
    fun failedCursorWriteRollsBackTheEpisodesFromThatPage() =
        runTest {
            val id = subscribe()
            val before = assertNotNull(db.podcastDao().byId(id))
            execSql(
                "CREATE TRIGGER fail_paging_cursor BEFORE UPDATE OF pagingNextUrl ON podcast " +
                    "WHEN old.id = $id AND new.pagingNextUrl != old.pagingNextUrl " +
                    "BEGIN SELECT RAISE(ABORT, 'cursor write failed'); END",
            )
            enqueuePage(2, 3)

            val failed = run(scheduledRequest())

            assertEquals(FeedOutcome.Failed(FeedErrorKind.STORAGE, null), failed.outcomes[id])
            assertEquals(before, db.podcastDao().byId(id))
            assertEquals(listOf("episode-1"), episodes(id).map { it.guid })
            assertTrue(db.ingestDao().recordedGuids(id).none { it == "episode-2" })
            assertRequests(listOf(1, 2))

            execSql("DROP TRIGGER fail_paging_cursor")
            enqueuePage(2, 3)
            enqueuePage(3)
            run(scheduledRequest())

            assertCursor(id, null, PagingState.STOPPED)
            assertEquals(3, db.podcastDao().episodeCount(id))
            assertRequests(listOf(2, 3))
        }

    @Test
    fun pageOneRefreshPreservesOlderEpisodesAndDoesNotRestartCompletedPaging() =
        runTest {
            val id = subscribe()
            enqueuePage(2, 3)
            enqueuePage(3)
            run(scheduledRequest())
            val olderIds = episodes(id).filter { it.guid != "episode-1" }.map { it.id }
            val before = assertNotNull(db.podcastDao().byId(id))
            clock.nowMs += DAY_MS
            server.enqueue(mockResponse(body = pageBody(1, 2, "replacement")))

            run(refreshRequest(id, RefreshOrigin.MANUAL))

            assertCursor(id, null, PagingState.STOPPED)
            assertEquals(4, db.podcastDao().episodeCount(id))
            val refreshed = episodes(id)
            assertEquals(2, olderIds.size)
            assertTrue(refreshed.filter { it.id in olderIds }.all { it.inFeed })
            assertFalse(refreshed.single { it.guid == "episode-1" }.inFeed)
            assertTrue(refreshed.single { it.guid == "replacement" }.inFeed)
            assertEquals(before.pagingNextUrl, db.podcastDao().byId(id)?.pagingNextUrl)
            assertRequests(listOf(1, 2, 3, 1))
        }

    @Test
    fun pageOneRefreshDoesNotRewindPendingPagingOrFlipOlderEpisodes() =
        runTest {
            val id = subscribe()
            enqueuePage(2, 3)
            server.enqueue(mockResponse(500))
            run(scheduledRequest())
            assertCursor(id, 3, PagingState.PENDING)
            val olderId = episodes(id).single { it.guid == "episode-2" }.id
            clock.nowMs += DAY_MS
            server.enqueue(mockResponse(body = pageBody(1, 2, "replacement")))

            // Page one can run at the two-minute paging margin, while background paging cannot.
            run(refreshRequest(id, RefreshOrigin.MANUAL).copy(deadlineElapsedMs = PAGING_MARGIN_MS))

            assertCursor(id, 3, PagingState.PENDING)
            assertTrue(assertNotNull(db.episodeDao().byId(olderId)).inFeed)
            assertEquals(3, db.podcastDao().episodeCount(id))
            assertRequests(listOf(1, 2, 3, 1))

            enqueuePage(3)
            run(pagesRequest(id, RefreshOrigin.CONTINUATION))
            assertCursor(id, null, PagingState.STOPPED)
            assertRequests(listOf(3))
        }

    @Test
    fun olderPagesNeverMarkNewOrEmitNewEpisodes() =
        runTest {
            val id = subscribe()
            val emitted = mutableListOf<NewEpisodes>()
            val collector =
                backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    eventBus.newEpisodes.collect { emitted += it }
                }
            // Recent dates would qualify as new under REFRESH, so silence proves OLDER_PAGE semantics.
            enqueuePage(2, 3, "recent-a", "recent-b")
            enqueuePage(3, items = arrayOf("recent-c"))

            val report = run(scheduledRequest())
            collector.cancel()
            collector.join()

            assertEquals(4, db.podcastDao().episodeCount(id))
            assertTrue(episodes(id).all { !it.isNew && it.inFeed })
            assertTrue(report.newEpisodes.isEmpty())
            assertTrue(emitted.isEmpty())
            assertRequests(listOf(1, 2, 3))
        }

    @Test
    fun pendingImportNeverAutomaticallyBackfills() = runTest { assertUnknownStateDoesNotBackfill(RefreshOrigin.IMPORT) }

    @Test
    fun pendingRestoreNeverAutomaticallyBackfills() =
        runTest { assertUnknownStateDoesNotBackfill(RefreshOrigin.RESTORE) }

    @Test
    fun pendingSyncNeverAutomaticallyBackfills() = runTest { assertUnknownStateDoesNotBackfill(RefreshOrigin.SYNC) }

    private suspend fun assertUnknownStateDoesNotBackfill(origin: RefreshOrigin) {
        val id =
            seedPodcast(
                db,
                url(1),
                status = PodcastStatus.PENDING_FIRST_FETCH,
                initialFetch = true,
                pagingNextUrl = null,
                pagingComplete = false,
            )
        enqueuePage(1, 2)

        val initial = run(refreshRequest(id, origin))

        assertTrue(assertIs<FeedOutcome.Ingested>(initial.outcomes[id]).firstIngest)
        val stored = assertNotNull(db.podcastDao().byId(id))
        assertEquals(PodcastStatus.ACTIVE, stored.status)
        assertFalse(stored.initialFetch)
        assertCursor(id, 2, PagingState.STOPPED)
        assertTrue(episodes(id).all { !it.isNew })
        assertTrue(initial.newEpisodes.all { it.initialFetch })

        server.enqueue(mockResponse(304))
        run(refreshRequest(id, RefreshOrigin.PERIODIC))
        assertCursor(id, 2, PagingState.STOPPED)
        assertEquals(1, db.podcastDao().episodeCount(id))
        assertRequests(listOf(1, 1))
    }

    @Test
    fun backfillSettingOffDoesNotScheduleOrFetchOlderPages() =
        runTest {
            settings.set(FeedsSettingKeys.BACKFILL_PAGED_FEEDS, false)
            val id = subscribe()

            assertTrue(scheduler.nowRequests.isEmpty())
            assertCursor(id, 2, PagingState.STOPPED)
            run(refreshRequest(id, RefreshOrigin.PERIODIC).copy(force = false))

            assertEquals(1, db.podcastDao().episodeCount(id))
            assertRequests(listOf(1))
        }

    @Test
    fun repeatedCursorStopsWithoutFetchingTheSamePageTwice() =
        runTest {
            val id = subscribe()
            enqueuePage(2, 3)
            enqueuePage(3, 2)

            run(scheduledRequest())

            assertCursor(id, 2, PagingState.STOPPED)
            assertEquals(3, db.podcastDao().episodeCount(id))
            assertRequests(listOf(1, 2, 3))
        }

    @Test
    fun pageWithoutNewKeysStopsAndKeepsItsNextLink() =
        runTest {
            val id = subscribe()
            enqueuePage(2, 3, "episode-1")

            run(scheduledRequest())

            assertCursor(id, 3, PagingState.STOPPED)
            assertEquals(1, db.podcastDao().episodeCount(id))
            assertRequests(listOf(1, 2))
        }

    private fun url(page: Int) = server.url("/page/$page").toString()

    private fun pageBody(
        page: Int,
        next: Int?,
        vararg items: String,
    ): String {
        val rel = if (page % 2 == 1) "prev-archive" else "next"
        val link = next?.let { "<atom:link rel=\"$rel\" href=\"${url(it)}\"/>" }.orEmpty()
        val names = items.toList().ifEmpty { listOf("episode-$page") }
        val date = if (page == 1 || names.any { it.startsWith("recent-") }) PAGE_ONE_DATE else OLDER_DATE
        return """
            <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
              <channel><title>Paging Show</title><link>https://example.com/show</link>
                $link
                ${names.joinToString("\n") { rssItem(it, extra = "<pubDate>$date</pubDate>") }}
              </channel>
            </rss>
            """.trimIndent()
    }

    private fun enqueuePage(
        page: Int,
        next: Int? = null,
        vararg items: String,
    ) {
        server.enqueue(mockResponse(body = pageBody(page, next, *items)))
    }

    private suspend fun subscribe(): Long {
        enqueuePage(1, 2)
        return newResolver(root, db, clock).use { resolver ->
            val preview = assertIs<AddResolution.Feed>(resolver.resolver.resolve(url(1))).preview
            val subscribe =
                newSubscribe(db, resolver.cache, resolver.resolver, scheduler, settings, clock, eventBus = eventBus)
            assertIs<Outcome.Success<Long>>(subscribe.useCase(preview.previewId, emptySet())).value
        }
    }

    private fun pagesRequest(
        id: Long,
        origin: RefreshOrigin,
    ) = RefreshRequest(
        scope = RefreshScope.Podcasts(listOf(id)),
        force = false,
        pagesOnly = true,
        origin = origin,
    )

    private fun refreshRequest(
        id: Long,
        origin: RefreshOrigin,
    ) = RefreshRequest(
        scope = RefreshScope.Podcasts(listOf(id)),
        force = true,
        pagesOnly = false,
        origin = origin,
    )

    private fun scheduledRequest(): RefreshRequest {
        val scheduled = scheduler.nowRequests.single()
        return RefreshRequest(scheduled.scope, scheduled.force, scheduled.pagesOnly, scheduled.origin)
    }

    private suspend fun run(
        request: RefreshRequest,
        hook: SyncIngestHook = SyncIngestHook.None,
    ): RefreshReport {
        if (request.deadlineElapsedMs == RefreshRequest.NO_DEADLINE && request.pagingBudgetMs == 0L) {
            return withEngine(hook) { it.run(request) }
        }

        // Real HTTP must not let runTest auto-advance an active paging timeout before commit.
        // TestClock moves only at the explicit hook; the batch-flush timer stays virtual otherwise.
        return withContext(Dispatchers.Default) { withEngine(hook) { it.run(request) } }
    }

    private suspend fun loadOlder(id: Long): RefreshReport =
        withEngine { engine ->
            val previousRequests = scheduler.nowRequests.size
            val previousCursor = assertNotNull(db.podcastDao().byId(id)).pagingNextUrl
            coroutineScope {
                RefreshControllerImpl(scheduler, engine, db, clock, this).loadOlderEpisodes(id)
            }
            val reopened = assertNotNull(db.podcastDao().byId(id))
            assertEquals(previousCursor, reopened.pagingNextUrl)
            assertFalse(reopened.pagingComplete)
            val scheduled = scheduler.nowRequests.drop(previousRequests).single()
            assertEquals(RefreshScope.Podcasts(listOf(id)), scheduled.scope)
            assertFalse(scheduled.force)
            assertTrue(scheduled.pagesOnly)
            assertEquals(RefreshOrigin.MANUAL, scheduled.origin)
            engine.run(RefreshRequest(scheduled.scope, scheduled.force, scheduled.pagesOnly, scheduled.origin))
        }

    private suspend fun withEngine(
        hook: SyncIngestHook = SyncIngestHook.None,
        block: suspend (FeedRefresher) -> RefreshReport,
    ): RefreshReport =
        newRssAdapter(root, clock).use { bundle ->
            block(
                newRefresher(
                    db,
                    mapOf(SourceType.RSS to bundle.adapter),
                    clock,
                    settings,
                    eventBus,
                    syncHook = hook,
                    tempFiles = bundle.tempFiles,
                    scheduler = scheduler,
                ),
            )
        }

    private suspend fun assertCursor(
        id: Long,
        page: Int?,
        state: PagingState,
    ) {
        val row = assertNotNull(db.podcastDao().byId(id))
        assertEquals(page?.let { url(it) }, row.pagingNextUrl)
        assertEquals(state == PagingState.STOPPED, row.pagingComplete)
    }

    private suspend fun episodes(id: Long): List<EpisodeEntity> =
        db.ingestDao().existing(id).map {
            assertNotNull(db.episodeDao().byId(it.id))
        }

    private fun assertRequests(pages: List<Int>) {
        for (page in pages) {
            val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/page/$page", request.url.encodedPath)
            if (page != 1) {
                assertNull(request.headers["If-None-Match"])
                assertNull(request.headers["If-Modified-Since"])
            }
        }
        assertEquals(pages.size, server.requestCount - verifiedRequests)
        verifiedRequests = server.requestCount
    }

    private var verifiedRequests = 0

    private suspend fun seedEpisodeCount(
        id: Long,
        count: Int,
    ) {
        // One SQL insert avoids thousands of HTTP requests and irrelevant parser/diff work.
        val missing = count - db.podcastDao().episodeCount(id)
        execSql(
            """
            WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM numbers WHERE n < $missing)
            INSERT INTO episode (
                podcastId, identityKey, title, sortDate, feedOrder, firstSeenAt, lastSeenAt, contentHash
            )
            SELECT $id, 'seed:' || n, 'Seed ' || n, ${clock.now() - DAY_MS},
                n, ${clock.now()}, ${clock.now()}, 0 FROM numbers
            """.trimIndent(),
        )
        assertEquals(count, db.podcastDao().episodeCount(id))
    }

    private suspend fun execSql(sql: String) {
        db.useWriterConnection { writer -> writer.usePrepared(sql) { it.step() } }
    }

    private enum class PagingState { PENDING, STOPPED }

    private companion object {
        const val PAGE_BUDGET = 50
        const val LAST_BUDGET_PAGE = 1 + PAGE_BUDGET
        const val AUTOMATIC_ITEM_CAP = 5_000
        const val DEADLINE_MS = 180_000L
        const val PAGING_MARGIN_MS = 120_000L
        const val PAGING_BUDGET_MS = 10_000L
        const val DAY_MS = 86_400_000L
        const val PAGE_ONE_DATE = "Sun, 04 Oct 2026 00:00:00 GMT"
        const val OLDER_DATE = "Sun, 20 Sep 2026 00:00:00 GMT"
    }
}
