// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Rule
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PLAN M1 acceptance 4/7 — the refresh engine's run semantics of 03 "Refresh scheduling": due
 * selection, force and scopes, the outcome table's column writes, failure policies, deadline
 * behaviour, announcements and paging sessions. The scripted `StubSourceAdapter` isolates the
 * engine; `RssSourceAdapter` over MockWebServer covers the unconditional-fetch rule (AC7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshEngineTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val clock = TestClock()
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()

    private lateinit var root: File

    @AfterTest
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun setUpRoot() {
        if (!::root.isInitialized) {
            root =
                kotlin.io.path
                    .createTempDirectory("nd-refresh-test")
                    .toFile()
        }
    }

    private fun refresher(adapter: StubSourceAdapter) =
        newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings)

    private suspend fun due(
        feedUrl: String,
        nextRefreshAt: Long? = 0L,
        block: PodcastEntity.() -> PodcastEntity = { this },
    ): Long =
        seedPodcast(
            db,
            feedUrl = feedUrl,
            feedKey = feedUrl,
            nextRefreshAt = nextRefreshAt,
            subscribedAt = NOW - 30 * DAY,
            block = block,
        )

    private fun request(
        scope: RefreshScope = RefreshScope.All,
        force: Boolean = false,
        pagesOnly: Boolean = false,
        origin: RefreshOrigin = RefreshOrigin.PERIODIC,
        dueSlackMs: Long = 0,
        deadlineElapsedMs: Long = RefreshRequest.NO_DEADLINE,
        pagingBudgetMs: Long = 0,
    ) = RefreshRequest(
        scope = scope,
        force = force,
        pagesOnly = pagesOnly,
        origin = origin,
        deadlineElapsedMs = deadlineElapsedMs,
        dueSlackMs = dueSlackMs,
        pagingBudgetMs = pagingBudgetMs,
    )

    // --- Selection ------------------------------------------------------------------------------

    @Test
    fun onlyDueFeedsAreFetched() =
        runTest {
            val adapter = stubAdapter()
            val stale = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            val future = due("https://b.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            val noSchedule = due("https://c.example.com/f", nextRefreshAt = null)
            due("https://d.example.com/f", nextRefreshAt = 0) { copy(gone = true) }
            due("https://e.example.com/f", nextRefreshAt = 0) { copy(needsCredentials = true) }

            val report = refresher(adapter).run(request())

            assertEquals(setOf(stale, noSchedule), adapter.calls.map { it.first }.toSet())
            assertEquals(setOf(stale, noSchedule), report.outcomes.keys)
            assertEquals(0, report.remaining)
            assertFalse(report.stoppedByDeadline)
            assertTrue(future > 0)
        }

    @Test
    fun forceRunMarksEveryHealthyFeedDue() =
        runTest {
            val adapter = stubAdapter()
            val a = due("https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            val b = due("https://b.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            due("https://c.example.com/f", nextRefreshAt = 0) { copy(gone = true) }

            val report = refresher(adapter).run(request(force = true))

            assertEquals(setOf(a, b), adapter.calls.map { it.first }.toSet())
            assertEquals(2, report.outcomes.size)
        }

    @Test
    fun podcastScopeNarrowsTheSelection() =
        runTest {
            val adapter = stubAdapter()
            val a = due("https://a.example.com/f")
            due("https://b.example.com/f")

            val report = refresher(adapter).run(request(scope = RefreshScope.Podcasts(listOf(a))))

            assertEquals(listOf(a), adapter.calls.map { it.first })
            assertEquals(setOf(a), report.outcomes.keys)
        }

    @Test
    fun dueSlackPullsInSoonDueFeeds() =
        runTest {
            val adapter = stubAdapter()
            val soon = due("https://a.example.com/f", nextRefreshAt = NOW + 60 * 60_000L)

            refresher(adapter).run(request())
            assertTrue(adapter.calls.isEmpty())

            refresher(adapter).run(request(dueSlackMs = 61 * 60_000L))
            assertEquals(listOf(soon), adapter.calls.map { it.first })
        }

    // --- Outcome table ----------------------------------------------------------------------------

    @Test
    fun parsedOutcomeIngestsAndWritesTheSuccessRow() =
        runTest {
            val id = due("https://a.example.com/f")
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            adapterParsed(
                                parsedFeed(
                                    items = listOf(parsedEpisode(0, guid = "e1", pubDate = NOW - DAY)),
                                ),
                                meta = fetchMeta(etag = "et"),
                            ),
                    ),
                )

            val report = refresher(adapter).run(request())

            val outcome = report.outcomes.getValue(id)
            assertIs<FeedOutcome.Ingested>(outcome)
            assertEquals(1, outcome.inserted)
            val stored = db.podcastDao().byId(id)!!
            assertEquals("et", stored.etag)
            assertEquals(NOW, stored.lastSuccessAt)
            assertEquals(0, stored.failureCount)
            assertTrue((stored.nextRefreshAt ?: 0) > NOW)
            assertEquals(FeedParser.VERSION, stored.parserVersion)
            assertNotNull(db.episodeDao().byIdentityKey(id, "g:e1"))
        }

    @Test
    fun notModifiedAdvancesTheScheduleWithoutIngesting() =
        runTest {
            val id =
                due("https://a.example.com/f", nextRefreshAt = NOW - 1) {
                    copy(etag = "keep", lastSuccessAt = NOW - DAY)
                }
            val adapter =
                stubAdapter(
                    mutableMapOf("https://a.example.com/f" to AdapterResult.NotModified(meta = null)),
                )

            val report = refresher(adapter).run(request())

            assertEquals(FeedOutcome.NotModified, report.outcomes[id])
            val stored = db.podcastDao().byId(id)!!
            assertEquals("keep", stored.etag)
            assertEquals(NOW, stored.lastSuccessAt)
            assertTrue((stored.nextRefreshAt ?: 0) > NOW)
        }

    @Test
    fun unchangedStillRunsAfterIngestWithEmptyLists() =
        runTest {
            val id = due("https://a.example.com/f")
            val adapter =
                stubAdapter(
                    mutableMapOf("https://a.example.com/f" to AdapterResult.Unchanged(fetchMeta())),
                )

            val report = refresher(adapter).run(request())

            assertEquals(FeedOutcome.Unchanged, report.outcomes[id])
            assertEquals(listOf(Triple(id, emptyList<Long>(), emptyList<Long>())), adapter.afterIngestCalls)
        }

    @Test
    fun offlineFailureLeavesTheFeedDueAndUntouched() =
        runTest {
            val id = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.Failed(
                                FeedErrorKind.OFFLINE,
                                http = null,
                                retryAfterMs = null,
                                transient = true,
                            ),
                    ),
                )

            val report = refresher(adapter).run(request())

            assertIs<FeedOutcome.Failed>(report.outcomes[id])
            val stored = db.podcastDao().byId(id)!!
            // OFFLINE writes nothing: the feed stays due with its old schedule (03 policy table).
            assertEquals(NOW - 1, stored.nextRefreshAt)
            assertNull(stored.lastAttemptAt)
            assertEquals(0, stored.failureCount)
        }

    @Test
    fun http410MarksTheFeedGone() =
        runTest {
            val id = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.Failed(
                                FeedErrorKind.HTTP_GONE,
                                http = 410,
                                retryAfterMs = null,
                                transient = false,
                            ),
                    ),
                )

            val report = refresher(adapter).run(request())

            assertEquals(FeedOutcome.Failed(FeedErrorKind.HTTP_GONE, 410), report.outcomes[id])
            val stored = db.podcastDao().byId(id)!!
            assertTrue(stored.gone)
            assertEquals(1, stored.failureCount)
            // "unchanged; excluded by gone = 1" — the schedule stays for diagnostics.
            assertEquals(NOW - 1, stored.nextRefreshAt)
        }

    @Test
    fun basicChallengeSetsNeedsCredentials() =
        runTest {
            val id = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.Failed(
                                FeedErrorKind.HTTP_AUTH,
                                http = 401,
                                retryAfterMs = null,
                                transient = true,
                            ),
                    ),
                )

            refresher(adapter).run(request())

            val stored = db.podcastDao().byId(id)!!
            assertTrue(stored.needsCredentials)
            assertEquals(FeedErrorKind.HTTP_AUTH, stored.lastErrorKind)
            assertEquals(NOW - 1, stored.nextRefreshAt)
        }

    @Test
    fun failuresBackOffExponentiallyAndHonourRetryAfter() =
        runTest {
            val id = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.Failed(
                                FeedErrorKind.HTTP_SERVER,
                                http = 500,
                                retryAfterMs = null,
                                transient = true,
                            ),
                    ),
                )

            refresher(adapter).run(request())
            var stored = db.podcastDao().byId(id)!!
            assertEquals(1, stored.failureCount)
            // n=1: 30 min with a 0.8–1.2 jitter (03 failure row).
            assertInWindow(stored.nextRefreshAt!! - NOW, 24 * 60_000L, 36 * 60_000L)

            clock.nowMs += DAY
            refresher(adapter).run(request())
            stored = db.podcastDao().byId(id)!!
            assertEquals(2, stored.failureCount)
            // n=2: 60 min with the same jitter.
            assertInWindow(stored.nextRefreshAt!! - clock.now(), 48 * 60_000L, 72 * 60_000L)

            // A `Retry-After` beyond the backoff wins (capped at 7 days). The feed backs off past
            // `now`, so the next run needs a persisted force (03's `refreshNow` writes `0` first).
            db.podcastDao().forceDue(scopeAll = false, ids = listOf(id))
            adapter.results["https://a.example.com/f"] =
                AdapterResult.Failed(
                    FeedErrorKind.HTTP_RATE_LIMITED,
                    http = 429,
                    retryAfterMs = 2 * 60 * 60_000L,
                    transient = true,
                )
            refresher(adapter).run(request())
            stored = db.podcastDao().byId(id)!!
            assertEquals(3, stored.failureCount)
            assertTrue(stored.nextRefreshAt!! - clock.now() >= 2 * 60 * 60_000L)
        }

    @Test
    fun parseErrorClearsLastParseOk() =
        runTest {
            val id = due("https://a.example.com/f") { copy(lastParseOk = true) }
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.Failed(
                                FeedErrorKind.PARSE_ERROR,
                                http = null,
                                retryAfterMs = null,
                                transient = true,
                                detail = "NOT_A_FEED: root",
                            ),
                    ),
                )

            refresher(adapter).run(request())

            val stored = db.podcastDao().byId(id)!!
            assertFalse(stored.lastParseOk)
            assertEquals(FeedErrorKind.PARSE_ERROR, stored.lastErrorKind)
        }

    @Test
    fun localNetworkUnsupportedSchedulesADayOut() =
        runTest {
            val id = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.Failed(
                                FeedErrorKind.LOCAL_NETWORK_UNSUPPORTED,
                                http = null,
                                retryAfterMs = null,
                                transient = true,
                            ),
                    ),
                )

            refresher(adapter).run(request())

            val stored = db.podcastDao().byId(id)!!
            assertEquals(NOW + DAY, stored.nextRefreshAt)
            assertEquals(FeedErrorKind.LOCAL_NETWORK_UNSUPPORTED, stored.lastErrorKind)
        }

    @Test
    fun adapterThrowMapsToUnknownFailure() =
        runTest {
            val id = due("https://a.example.com/f")
            val adapter = stubAdapter(onFetch = { _, _ -> error("adapter exploded") })

            val report = refresher(adapter).run(request())

            assertEquals(FeedOutcome.Failed(FeedErrorKind.UNKNOWN, null), report.outcomes[id])
            assertEquals(FeedErrorKind.UNKNOWN, db.podcastDao().byId(id)!!.lastErrorKind)
        }

    @Test
    fun assignedKeyCollisionIngestsWithoutConflict() =
        runTest {
            // Feed A's stored rows plus the colliding document of IngestDiffTest's construction:
            // the second item's assigned t: key exactly matches row D's — the diff claims it in
            // pass 1 rather than aborting on UNIQUE (S3).
            val a = due("https://a.example.com/f")
            val day = NOW - DAY
            newIngestor(db, clock)
                .ingest(
                    dueFeedOf(db, a),
                    parsedFeed(
                        items =
                            listOf(
                                parsedEpisode(
                                    0,
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "B",
                                    pubDate = day,
                                ),
                                parsedEpisode(
                                    1,
                                    guid = "gc",
                                    enclosureUrl = "https://cdn.example.com/x.mp3",
                                    title = "C",
                                    pubDate = day,
                                ),
                                parsedEpisode(
                                    2,
                                    enclosureUrl = null,
                                    externalMediaId = "m1",
                                    title = "T",
                                    pubDate = day,
                                ),
                            ),
                    ),
                    IngestContext(mode = IngestMode.INITIAL, partial = false, fetch = fetchMeta()),
                )
            // The v1 ingest rescheduled A far into the future; force it due for this run.
            db.podcastDao().forceDue(scopeAll = false, ids = listOf(a))
            val b = due("https://b.example.com/f")
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            adapterParsed(
                                parsedFeed(
                                    items =
                                        listOf(
                                            parsedEpisode(
                                                0,
                                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                                title = "B2",
                                                pubDate = day,
                                            ),
                                            parsedEpisode(
                                                1,
                                                enclosureUrl = "https://cdn.example.com/x.mp3",
                                                title = "T",
                                                pubDate = day,
                                            ),
                                        ),
                                ),
                            ),
                        "https://b.example.com/f" to
                            adapterParsed(parsedFeed(items = listOf(parsedEpisode(0, guid = "ok")))),
                    ),
                )

            val report = refresher(adapter).run(request())

            assertIs<FeedOutcome.Ingested>(report.outcomes[a])
            assertIs<FeedOutcome.Ingested>(report.outcomes[b])
            assertEquals(3, db.podcastDao().episodeCount(a))
            assertFalse(db.episodeDao().byIdentityKey(a, "g:gc")!!.inFeed)
        }

    @Test
    fun aRejectedIngestKeepsTheStoredValidators() =
        runTest {
            val id =
                due("https://a.example.com/f", nextRefreshAt = NOW - 1) {
                    copy(etag = "old-etag", lastModified = "old-lm")
                }
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            adapterParsed(
                                parsedFeed(
                                    items =
                                        listOf(
                                            parsedEpisode(
                                                0,
                                                guid = "a",
                                                enclosureUrl = null,
                                                externalMediaId = null,
                                            ),
                                        ),
                                ),
                                meta = fetchMeta(etag = "new-etag", lastModified = "new-lm"),
                            ),
                    ),
                )

            val report = refresher(adapter).run(request())

            assertEquals(FeedOutcome.Failed(FeedErrorKind.NO_MEDIA, null), report.outcomes[id])
            val stored = db.podcastDao().byId(id)!!
            // The body produced no ingest: the response's validators are not adopted — keeping
            // the stored pair keeps the next attempt conditional on committed state (S15).
            assertEquals("old-etag", stored.etag)
            assertEquals("old-lm", stored.lastModified)
            assertEquals(FeedErrorKind.NO_MEDIA, stored.lastErrorKind)
            assertNull(stored.contentSha256)
        }

    // --- Deadline and concurrency ------------------------------------------------------------------

    @Test
    fun pastDeadlineReportsAllDueAsRemaining() =
        runTest {
            val adapter = stubAdapter()
            due("https://a.example.com/f")
            due("https://b.example.com/f")

            val report = refresher(adapter).run(request(deadlineElapsedMs = clock.elapsedRealtime()))

            assertTrue(report.stoppedByDeadline)
            assertEquals(2, report.remaining)
            assertEquals(emptyMap(), report.outcomes)
            assertTrue(adapter.calls.isEmpty())
        }

    @Test
    fun queuedRunBehindAnActiveRunReportsRemaining() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    gate.await()
                    AdapterResult.NotModified(meta = null)
                })
            due("https://a.example.com/f")

            val engine = refresher(adapter)
            val first = backgroundScope.async { engine.run(request()) }
            testScheduler.runCurrent()

            val report = engine.run(request(deadlineElapsedMs = clock.elapsedRealtime() + 35_000L))

            assertTrue(report.stoppedByDeadline)
            assertEquals(1, report.remaining)
            assertEquals(emptyMap(), report.outcomes)

            gate.complete(Unit)
            assertEquals(1, first.await().outcomes.size)
        }

    @Test
    fun aForcedScopeStaysDueWhenTheRunNeverStarts() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    gate.await()
                    AdapterResult.NotModified(meta = null)
                })
            due("https://a.example.com/f")
            val b = due("https://b.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            val engine = refresher(adapter)
            val first = backgroundScope.async { engine.run(request()) }
            testScheduler.runCurrent()

            // The forced request persists its due marks before it blocks on the run mutex: the
            // timed-out run still leaves B due for a later selection (R4).
            val report =
                engine.run(
                    request(
                        scope = RefreshScope.Podcasts(listOf(b)),
                        force = true,
                        // Exactly the 30 s margin past now → tryLock fails behind the held run.
                        deadlineElapsedMs = clock.elapsedRealtime() + 30_000L,
                    ),
                )

            assertTrue(report.stoppedByDeadline)
            assertEquals(0L, db.podcastDao().byId(b)!!.nextRefreshAt)
            assertEquals(1, report.remaining)

            gate.complete(Unit)
            first.await()
        }

    @Test
    fun aWaitingForcedRunRefetchesAfterAStaleBackoffLands() =
        runTest {
            // The automatic run holds the engine mutex while its fetch of the feed is parked; the
            // forced retry's due marks then sit in the open until the stale 500's backoff
            // overwrites them. The waiting run must re-apply force inside the mutex — before
            // selection — or it selects nothing and reports a false success (r3 F3).
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            val id =
                seedPodcast(
                    db,
                    feedUrl = "https://a.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                    failureCount = 3,
                )
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    gate.await()
                    AdapterResult.Failed(
                        FeedErrorKind.HTTP_SERVER,
                        http = 500,
                        retryAfterMs = null,
                        transient = true,
                    )
                })
            val engine = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings)
            val automatic = backgroundScope.async { engine.run(request()) }
            testScheduler.runCurrent()
            assertEquals(listOf(id to FetchMode.REFRESH), adapter.calls)

            val manual =
                backgroundScope.async {
                    engine.run(
                        request(
                            scope = RefreshScope.Podcasts(listOf(id)),
                            force = true,
                            origin = RefreshOrigin.MANUAL,
                        ),
                    )
                }
            testScheduler.runCurrent()
            assertEquals(0L, db.podcastDao().byId(id)!!.nextRefreshAt)

            gate.complete(Unit)
            automatic.await()
            val report = manual.await()

            // The stale backoff committed first; the re-applied force still retried the feed.
            assertEquals(2, adapter.calls.count { it.first == id })
            assertEquals(FeedOutcome.Failed(FeedErrorKind.HTTP_SERVER, 500), report.outcomes[id])
        }

    @Test
    fun aRetryRunClearsABlockRewrittenByAnInflightOutcome() =
        runTest {
            // The block leg of r3 F3: Try again clears the failure block and forces the run, then
            // the in-flight fetch lands a 410 — `gone` is set again before the retry wins the
            // mutex. A RETRY-origin run clears the scope's blocks inside the mutex, so the stale
            // outcome cannot swallow the retry.
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            val id =
                seedPodcast(
                    db,
                    feedUrl = "https://a.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                    failureCount = 3,
                )
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    gate.await()
                    AdapterResult.Failed(
                        FeedErrorKind.HTTP_GONE,
                        http = 410,
                        retryAfterMs = null,
                        transient = false,
                    )
                })
            val engine = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings)
            val automatic = backgroundScope.async { engine.run(request()) }
            testScheduler.runCurrent()

            // `PodcastRepository.retry`'s two legs: the immediate clear, then the RETRY run.
            db.podcastDao().clearRefreshBlock(id)
            val retry =
                backgroundScope.async {
                    engine.run(
                        request(
                            scope = RefreshScope.Podcasts(listOf(id)),
                            force = true,
                            origin = RefreshOrigin.RETRY,
                        ),
                    )
                }
            testScheduler.runCurrent()
            assertEquals(0L, db.podcastDao().byId(id)!!.nextRefreshAt)

            gate.complete(Unit)
            automatic.await()
            val report = retry.await()

            assertEquals(2, adapter.calls.count { it.first == id })
            assertEquals(FeedOutcome.Failed(FeedErrorKind.HTTP_GONE, 410), report.outcomes[id])
        }

    @Test
    fun aTimedOutForcedRunRestoresMarksAnInflightOutcomeOverwrote() =
        runTest {
            // The timeout leg of r3 F3: feed A keeps the mutex past the retry's wait budget while
            // feed B's stale 500 flushes mid-wait through the batch deadline — the marks it
            // overwrote must be re-applied before the timed-out run reports, so the retry stays
            // due for the next selection instead of vanishing.
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            val a =
                seedPodcast(
                    db,
                    feedUrl = "https://a.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                )
            val b =
                seedPodcast(
                    db,
                    feedUrl = "https://b.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                    failureCount = 3,
                )
            val adapter =
                stubAdapter(onFetch = { feed, _ ->
                    (if (feed.id == a) gateA else gateB).await()
                    AdapterResult.Failed(
                        FeedErrorKind.HTTP_SERVER,
                        http = 500,
                        retryAfterMs = null,
                        transient = true,
                    )
                })
            val engine = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings)
            val automatic = backgroundScope.async { engine.run(request()) }
            testScheduler.runCurrent()
            assertEquals(setOf(a, b), adapter.calls.map { it.first }.toSet())

            val retry =
                backgroundScope.async {
                    engine.run(
                        request(
                            scope = RefreshScope.Podcasts(listOf(b)),
                            force = true,
                            origin = RefreshOrigin.MANUAL,
                            deadlineElapsedMs = clock.elapsedRealtime() + 60_000L,
                        ),
                    )
                }
            testScheduler.runCurrent()
            assertEquals(0L, db.podcastDao().byId(b)!!.nextRefreshAt)

            // B's stale outcome buffers; the batcher's 5 s deadline flushes it while A's fetch
            // still holds the engine mutex. The deadline runs on the monotonic clock, so the
            // 5 s bump must move `elapsedMs`, not only the wall `nowMs`.
            gateB.complete(Unit)
            testScheduler.runCurrent()
            clock.nowMs += 5_001L
            clock.elapsedMs += 5_001L
            advanceTimeBy(5_001L)
            testScheduler.runCurrent()
            assertTrue((db.podcastDao().byId(b)!!.nextRefreshAt ?: 0) > 0)

            advanceTimeBy(31_000L)
            val report = retry.await()

            assertTrue(report.stoppedByDeadline)
            assertEquals(emptyMap(), report.outcomes)
            assertEquals(1, report.remaining)
            assertEquals(0L, db.podcastDao().byId(b)!!.nextRefreshAt)

            gateA.complete(Unit)
            automatic.await()

            // A plain later selection picks the restored mark up — durable, not reported-only.
            engine.run(request())
            assertEquals(2, adapter.calls.count { it.first == b })
        }

    @Test
    fun aTimedOutRetryReenqueuesSoALateBackoffCannotEraseIt() =
        runTest {
            timedOutRetryScenario(
                AdapterResult.Failed(
                    FeedErrorKind.HTTP_SERVER,
                    http = 500,
                    retryAfterMs = null,
                    transient = true,
                ),
                FeedOutcome.Failed(FeedErrorKind.HTTP_SERVER, 500),
            )
        }

    @Test
    fun aTimedOutRetryReenqueuesSoALateGoneCannotEraseIt() =
        runTest {
            timedOutRetryScenario(
                AdapterResult.Failed(
                    FeedErrorKind.HTTP_GONE,
                    http = 410,
                    retryAfterMs = null,
                    transient = false,
                ),
                FeedOutcome.Failed(FeedErrorKind.HTTP_GONE, 410),
            )
        }

    /**
     * The r4 F3 reproduction, both stale-outcome legs: feed A's fetch holds the engine mutex
     * past the scoped retry's 30 s wait margin; B's fetch is released only *after* the retry
     * timed out, so its stale backoff/`gone` lands on the marks the timeout re-applied. A
     * request carrying intent must re-enqueue itself — scope, force and `RETRY` origin intact —
     * rather than degrade to a non-forced continuation that neither forces the feed nor
     * clears its block; when the re-enqueued request runs, its intent is re-applied under the
     * mutex and the retry executes.
     */
    private suspend fun TestScope.timedOutRetryScenario(
        stale: AdapterResult,
        expectedOutcome: FeedOutcome,
    ) {
        val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val a =
            seedPodcast(
                db,
                feedUrl = "https://a.example.com/f",
                nextRefreshAt = NOW - 1,
                subscribedAt = NOW - 30 * DAY,
            )
        val b =
            seedPodcast(
                db,
                feedUrl = "https://b.example.com/f",
                nextRefreshAt = NOW - 1,
                subscribedAt = NOW - 30 * DAY,
                failureCount = 3,
            )
        val adapter =
            stubAdapter(onFetch = { feed, _ ->
                (if (feed.id == a) gateA else gateB).await()
                stale
            })
        val engine = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings)
        val automatic = backgroundScope.async { engine.run(request()) }
        testScheduler.runCurrent()
        assertEquals(setOf(a, b), adapter.calls.map { it.first }.toSet())

        // `PodcastRepository.retry`'s legs: the immediate block clear, then the scoped forced
        // RETRY request that queues behind the automatic run's mutex.
        db.podcastDao().clearRefreshBlock(b)
        val retryRequest =
            request(
                scope = RefreshScope.Podcasts(listOf(b)),
                force = true,
                origin = RefreshOrigin.RETRY,
                deadlineElapsedMs = clock.elapsedRealtime() + 60_000L,
            )
        val retry = backgroundScope.async { engine.run(retryRequest) }
        testScheduler.runCurrent()
        assertEquals(0L, db.podcastDao().byId(b)!!.nextRefreshAt)

        advanceTimeBy(31_000L)
        val report = retry.await()

        assertTrue(report.stoppedByDeadline)
        assertTrue(report.reenqueued)

        // B's fetch resolves *after* the retry timed out: the stale outcome commits on top of
        // the timeout's re-applied marks and erases them — the swallow the request alone
        // could not survive.
        gateB.complete(Unit)
        testScheduler.runCurrent()
        clock.nowMs += 5_001L
        clock.elapsedMs += 5_001L
        advanceTimeBy(5_001L)
        testScheduler.runCurrent()
        assertTrue(
            db
                .podcastDao()
                .dueForRefresh(clock.now(), scopeAll = false, ids = listOf(b))
                .isEmpty(),
        )

        gateA.complete(Unit)
        automatic.await()

        // What the caller re-enqueues is the request it ran: its intent is re-applied under
        // the mutex, so the stale outcome cannot consume the retry — B is fetched again.
        val rerun = engine.run(retryRequest)
        assertEquals(2, adapter.calls.count { it.first == b })
        assertEquals(expectedOutcome, rerun.outcomes[b])
    }

    @Test
    fun pagingRechecksTheBudgetAfterPermitWaits() =
        runTest {
            // Eight pending pages share six global permits; each page burns 30 s of fake uptime,
            // so the sessions that waited out round one must re-check the 10 s budget before
            // starting a page of their own (R3): six pages run, two sessions stop queued. The
            // yield lets every permit holder pass the recheck before the first page's burn lands.
            val feeds =
                (0 until 8).map { i ->
                    due("https://$i.example.com/f", nextRefreshAt = NOW + 60 * DAY) {
                        copy(pagingNextUrl = "https://$i.example.com/f?page=2", pagingComplete = false)
                    }
                }
            val adapter =
                stubAdapter(
                    onFetch = { feed, _ ->
                        yield()
                        clock.elapsedMs += 30_000L
                        adapterParsed(
                            parsedFeed(items = listOf(parsedEpisode(0, guid = "p${feed.id}"))),
                        )
                    },
                )

            refresher(adapter).run(request(pagingBudgetMs = 10_000L))

            assertEquals(6, adapter.calls.size)
            assertTrue(adapter.calls.all { it.second == FetchMode.OLDER_PAGE })
            assertTrue(feeds.size == 8)
        }

    // --- Announcements and diagnostics -------------------------------------------------------------

    @Test
    fun firstFetchEmitsEveryInsertedIdAsInitialFetch() =
        runTest {
            val id =
                due("https://a.example.com/f") {
                    copy(status = PodcastStatus.PENDING_FIRST_FETCH, initialFetch = true)
                }
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            adapterParsed(
                                parsedFeed(
                                    items =
                                        listOf(
                                            parsedEpisode(0, guid = "a"),
                                            parsedEpisode(1, guid = "b"),
                                        ),
                                ),
                            ),
                    ),
                )

            val report = refresher(adapter).run(request())

            val outcome = report.outcomes.getValue(id)
            assertIs<FeedOutcome.Ingested>(outcome)
            assertTrue(outcome.firstIngest)
            val announcement = report.newEpisodes.single()
            assertEquals(id, announcement.podcastId)
            assertTrue(announcement.initialFetch)
            assertEquals(2, announcement.episodeIds.size)
            assertEquals(PodcastStatus.ACTIVE, db.podcastDao().byId(id)!!.status)
        }

    @Test
    fun announcementsCarryTheAdapterFilteredNewIds() =
        runTest {
            val id = due("https://a.example.com/f")
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            adapterParsed(
                                parsedFeed(
                                    items =
                                        listOf(
                                            parsedEpisode(0, guid = "a", pubDate = NOW),
                                            parsedEpisode(1, guid = "b", pubDate = NOW),
                                        ),
                                ),
                            ),
                    ),
                    announce = { _, _, newIds -> newIds.take(1) },
                )

            val report = refresher(adapter).run(request())

            val announcement = report.newEpisodes.single()
            assertEquals(1, announcement.episodeIds.size)
            assertFalse(announcement.initialFetch)
        }

    @Test
    fun runWritesTheDiagnosticSettings() =
        runTest {
            due("https://a.example.com/f")
            refresher(stubAdapter()).run(request())

            assertEquals(NOW, settings.get(FeedsSettingKeys.LAST_RUN_FINISHED_AT))
            assertEquals(NOW, settings.get(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT))
            val summary = settings.get(FeedsSettingKeys.LAST_RUN_SUMMARY)
            assertTrue(summary.contains("\"origin\":\"PERIODIC\""))
            assertTrue(summary.contains("\"attempted\":1"))
        }

    // --- Paging -------------------------------------------------------------------------------------

    @Test
    fun pagesOnlyRunsThePagingSessionToCompletion() =
        runTest {
            val id =
                due("https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY) {
                    copy(
                        pagingNextUrl = "https://a.example.com/f?page=2",
                        pagingComplete = false,
                    )
                }
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f?page=2" to
                            adapterParsed(
                                parsedFeed(
                                    items = listOf(parsedEpisode(10, guid = "p2", pubDate = NOW - 10 * DAY)),
                                ).copy(paging = Paging(next = "https://a.example.com/f?page=3")),
                            ),
                        "https://a.example.com/f?page=3" to
                            adapterParsed(
                                parsedFeed(
                                    items = listOf(parsedEpisode(11, guid = "p3", pubDate = NOW - 11 * DAY)),
                                ),
                            ),
                    ),
                )

            val report = refresher(adapter).run(request(pagesOnly = true))

            assertEquals(
                listOf(id to FetchMode.OLDER_PAGE, id to FetchMode.OLDER_PAGE),
                adapter.calls,
            )
            assertIs<FeedOutcome.Ingested>(report.outcomes[id])
            val stored = db.podcastDao().byId(id)!!
            assertTrue(stored.pagingComplete)
            assertEquals(2, db.podcastDao().episodeCount(id))
        }

    @Test
    fun dueRunsPagePendingFeedsInTheBackground() =
        runTest {
            // Queries and ingest prep run on the test scheduler: the paging phase is
            // cancellation-bounded now, and a real dispatcher would let the timeout fire while a
            // commit is parked on a worker thread — rolling back the page this test checks.
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val id =
                seedPodcast(
                    db,
                    feedUrl = "https://a.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                    pagingNextUrl = "https://a.example.com/f?page=2",
                    pagingComplete = false,
                )
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to AdapterResult.NotModified(meta = null),
                        "https://a.example.com/f?page=2" to
                            adapterParsed(
                                parsedFeed(
                                    items = listOf(parsedEpisode(10, guid = "p2", pubDate = NOW - 10 * DAY)),
                                ),
                            ),
                    ),
                )

            newRefresher(
                db,
                mapOf(SourceType.RSS to adapter),
                clock,
                settings,
                ingestor =
                    newIngestor(
                        db,
                        clock,
                        settings,
                        defaultDispatcher = StandardTestDispatcher(testScheduler),
                    ),
            ).run(request(pagingBudgetMs = 120_000L))

            assertEquals(
                listOf(id to FetchMode.REFRESH, id to FetchMode.OLDER_PAGE),
                adapter.calls,
            )
            assertTrue(db.podcastDao().byId(id)!!.pagingComplete)
        }

    @Test
    fun aHungBackgroundPageIsCutAtThePagingBudget() =
        runTest {
            // A page still in flight when the paging budget lands is cancelled instead of
            // overrunning the run: the feed keeps its paging cursor and nothing commits (03
            // Engine run step 7). The desktop lane relies on this — it has no run deadline.
            val gate = CompletableDeferred<Unit>()
            val id =
                due("https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY) {
                    copy(pagingNextUrl = "https://a.example.com/f?page=2", pagingComplete = false)
                }
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    gate.await()
                    AdapterResult.NotModified(meta = null)
                })

            val report = refresher(adapter).run(request(pagingBudgetMs = 2_000L))

            assertEquals(listOf(id to FetchMode.OLDER_PAGE), adapter.calls)
            val stored = db.podcastDao().byId(id)!!
            assertEquals("https://a.example.com/f?page=2", stored.pagingNextUrl)
            assertFalse(stored.pagingComplete)
            assertEquals(0, db.podcastDao().episodeCount(id))
            assertEquals(emptyMap(), report.outcomes)
        }

    @Test
    fun aHungBackgroundPageIsCutBeforeTheRunDeadline() =
        runTest {
            // The Android shape: the run's deadline bounds the whole execution and the paging
            // phase's stop is PAGING_MARGIN_MS before it — an in-flight page is cancelled at
            // that stop rather than riding past the deadline.
            val gate = CompletableDeferred<Unit>()
            val id =
                due("https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY) {
                    copy(pagingNextUrl = "https://a.example.com/f?page=2", pagingComplete = false)
                }
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    gate.await()
                    AdapterResult.NotModified(meta = null)
                })

            refresher(adapter).run(request(deadlineElapsedMs = clock.elapsedRealtime() + 300_000L))

            assertEquals(listOf(id to FetchMode.OLDER_PAGE), adapter.calls)
            assertFalse(db.podcastDao().byId(id)!!.pagingComplete)
        }

    @Test
    fun aPagesOnlySessionIsCutAtThePagingBound() =
        runTest {
            // A pagesOnly session carries the same bound: the in-flight page is cancelled at
            // `pagingStopAt` and the feed counts as a leftover, so the caller can continue later.
            val gate = CompletableDeferred<Unit>()
            val id =
                due("https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY) {
                    copy(pagingNextUrl = "https://a.example.com/f?page=2", pagingComplete = false)
                }
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    gate.await()
                    AdapterResult.NotModified(meta = null)
                })

            val report = refresher(adapter).run(request(pagesOnly = true, pagingBudgetMs = 2_000L))

            assertEquals(listOf(id to FetchMode.OLDER_PAGE), adapter.calls)
            assertEquals(1, report.remaining)
            assertFalse(db.podcastDao().byId(id)!!.pagingComplete)
        }

    @Test
    fun aHungPageKeepsThePagesCommittedEarlierInTheRun() =
        runTest {
            // Page 2 commits; page 3 hangs and is cancelled at the bound. Only the unfinished
            // page rolls back — the cursor advanced to page 3 and page 2's episode stays. Queries
            // and ingest prep run on the test scheduler so the bound fires only at the gate,
            // never while a commit is parked on a real thread.
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            val id =
                seedPodcast(
                    db,
                    feedUrl = "https://a.example.com/f",
                    nextRefreshAt = NOW + 60 * DAY,
                    subscribedAt = NOW - 30 * DAY,
                    pagingNextUrl = "https://a.example.com/f?page=2",
                    pagingComplete = false,
                )
            val adapter =
                stubAdapter(
                    onFetch = { feed, _ ->
                        if (feed.pagingNextUrl == "https://a.example.com/f?page=3") {
                            gate.await()
                        }
                        adapterParsed(
                            parsedFeed(
                                items =
                                    listOf(parsedEpisode(10, guid = "p2", pubDate = NOW - 10 * DAY)),
                            ).copy(paging = Paging(next = "https://a.example.com/f?page=3")),
                        )
                    },
                )

            val report =
                newRefresher(
                    db,
                    mapOf(SourceType.RSS to adapter),
                    clock,
                    settings,
                    ingestor =
                        newIngestor(
                            db,
                            clock,
                            settings,
                            defaultDispatcher = StandardTestDispatcher(testScheduler),
                        ),
                ).run(request(pagesOnly = true, pagingBudgetMs = 2_000L))

            assertEquals(2, adapter.calls.size)
            assertIs<FeedOutcome.Ingested>(report.outcomes[id])
            val stored = db.podcastDao().byId(id)!!
            assertEquals("https://a.example.com/f?page=3", stored.pagingNextUrl)
            assertFalse(stored.pagingComplete)
            assertEquals(1, db.podcastDao().episodeCount(id))
        }

    // --- AC7: the parser-version / full-fetch rule over the real RSS adapter -------------------------

    @Test
    fun parserVersionBelowCurrentFetchesUnconditionally() =
        runTest {
            setUpRoot()
            val feedUrl = server.url("/feed.xml").toString()
            val id =
                due(feedUrl, nextRefreshAt = NOW - 1) {
                    copy(
                        etag = "e-old",
                        parserVersion = 0,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                    )
                }
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("v2")))))
            val bundle = newRssAdapter(root, clock)
            try {
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        settings,
                        tempFiles = bundle.tempFiles,
                    )

                val report = engine.run(request())

                assertIs<FeedOutcome.Ingested>(report.outcomes[id])
                val recorded = server.takeRequest()
                assertNull(recorded.headers["If-None-Match"])
                assertNull(recorded.headers["If-Modified-Since"])
                assertEquals(FeedParser.VERSION, db.podcastDao().byId(id)!!.parserVersion)
            } finally {
                bundle.close()
            }
        }

    @Test
    fun currentParserVersionSendsTheStoredValidators() =
        runTest {
            setUpRoot()
            val feedUrl = server.url("/feed.xml").toString()
            val id =
                due(feedUrl, nextRefreshAt = NOW - 1) {
                    copy(
                        etag = "e-now",
                        lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                        parserVersion = FeedParser.VERSION,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                    )
                }
            server.enqueue(mockResponse(code = 304, body = ""))
            val bundle = newRssAdapter(root, clock)
            try {
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        settings,
                        tempFiles = bundle.tempFiles,
                    )

                val report = engine.run(request())

                assertEquals(FeedOutcome.NotModified, report.outcomes[id])
                val recorded = server.takeRequest()
                assertEquals("e-now", recorded.headers["If-None-Match"])
                assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", recorded.headers["If-Modified-Since"])
            } finally {
                bundle.close()
            }
        }

    @Test
    fun failedParseAndStaleFullFetchAlsoFetchUnconditionally() =
        runTest {
            setUpRoot()
            due(server.url("/a.xml").toString(), nextRefreshAt = NOW - 1) {
                copy(
                    etag = "e1",
                    lastParseOk = false,
                    parserVersion = FeedParser.VERSION,
                    lastFullFetchAt = NOW,
                )
            }
            due(server.url("/b.xml").toString(), nextRefreshAt = NOW - 1) {
                copy(
                    etag = "e2",
                    parserVersion = FeedParser.VERSION,
                    lastParseOk = true,
                    lastFullFetchAt = NOW - 15 * DAY,
                )
            }
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("x")))))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("y")))))
            val bundle = newRssAdapter(root, clock)
            try {
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        settings,
                        tempFiles = bundle.tempFiles,
                    )

                engine.run(request())

                repeat(2) {
                    val recorded = server.takeRequest()
                    assertNull(recorded.headers["If-None-Match"])
                }
            } finally {
                bundle.close()
            }
        }

    @Test
    fun staleFullFetchStaysConditionalWhenMetered() =
        runTest {
            setUpRoot()
            val feedUrl = server.url("/feed.xml").toString()
            due(feedUrl, nextRefreshAt = NOW - 1) {
                copy(
                    etag = "e-m",
                    parserVersion = FeedParser.VERSION,
                    lastParseOk = true,
                    lastFullFetchAt = NOW - 15 * DAY,
                )
            }
            server.enqueue(mockResponse(code = 304, body = ""))
            val metered =
                FakeNetworkMonitor(
                    ch.lkmc.neutrodyne.core.common.NetworkStatus(
                        isConnected = true,
                        isValidated = true,
                        isMetered = true,
                        isVpn = false,
                    ),
                )
            val bundle = newRssAdapter(root, clock, network = metered)
            try {
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        settings,
                        tempFiles = bundle.tempFiles,
                    )

                engine.run(request())

                assertEquals("e-m", server.takeRequest().headers["If-None-Match"])
            } finally {
                bundle.close()
            }
        }

    @Test
    fun aParserBumpReParsesAByteIdenticalBody() =
        runTest {
            setUpRoot()
            val feedUrl = server.url("/feed.xml").toString()
            val body = rssBody(items = arrayOf(rssItem("re")))
            val sha =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(body.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            val id =
                due(feedUrl, nextRefreshAt = NOW - 1) {
                    copy(
                        contentSha256 = sha,
                        parserVersion = FeedParser.VERSION - 1,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                    )
                }
            server.enqueue(mockResponse(body = body))
            val bundle = newRssAdapter(root, clock)
            try {
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        settings,
                        tempFiles = bundle.tempFiles,
                    )

                val report = engine.run(request())

                // The stored sha matches the served bytes, but the stale parser version forces a
                // re-parse — `Unchanged` would leave rows parsed by the old parser in place (S5).
                assertIs<FeedOutcome.Ingested>(report.outcomes[id])
                assertEquals(FeedParser.VERSION, db.podcastDao().byId(id)!!.parserVersion)
            } finally {
                bundle.close()
            }
        }

    @Test
    fun fhCompleteOverridesAnArchiveLinkForPartial() =
        runTest {
            setUpRoot()
            val feedUrl = server.url("/feed.xml").toString()
            val id = due(feedUrl, nextRefreshAt = NOW - 1)
            // An episode absent from the served document: whether it stays `inFeed` exposes the
            // adapter's `partial` flag (03 step 8 only runs for complete documents). Its pubDate
            // sits ten days below the document's items, so a partial window would keep it —
            // the flag cannot pass for the wrong reason.
            val stored =
                newIngestor(db, clock)
                    .ingest(
                        dueFeedOf(db, id),
                        parsedFeed(items = listOf(parsedEpisode(0, guid = "old-ep", pubDate = NOW - 10 * DAY))),
                        IngestContext(mode = IngestMode.INITIAL, partial = false, fetch = fetchMeta()),
                    )
            assertEquals(1, stored.inserted.size)
            db.podcastDao().forceDue(scopeAll = false, ids = listOf(id))

            // fh:complete + an archive link: the link alone would mark the document partial —
            // fh:complete overrides it (S10), so the absent row flips out of the feed.
            val doc =
                """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:fh="http://purl.org/syndication/history/1.0">
  <channel><title>Archived Show</title><link>https://example.com/s</link>
    <fh:complete/>
    <atom:link rel="prev-archive" href="https://example.com/s/2.xml"/>
    ${rssItem("new-ep")}
  </channel></rss>"""
            server.enqueue(mockResponse(body = doc))
            val bundle = newRssAdapter(root, clock)
            try {
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        settings,
                        tempFiles = bundle.tempFiles,
                    )

                val report = engine.run(request())

                assertIs<FeedOutcome.Ingested>(report.outcomes[id])
                assertFalse(db.episodeDao().byId(stored.inserted.single())!!.inFeed)
            } finally {
                bundle.close()
            }
        }

    @Test
    fun anEndedShowWithAnArchiveLinkKeepsAbsentEpisodesPartial() =
        runTest {
            setUpRoot()
            val feedUrl = server.url("/feed.xml").toString()
            val id = due(feedUrl, nextRefreshAt = NOW - 1)
            // Same shape as above, but the document is show-complete (itunes:complete), NOT
            // feed-history-complete: the archive link still marks the window partial (03).
            val stored =
                newIngestor(db, clock)
                    .ingest(
                        dueFeedOf(db, id),
                        parsedFeed(items = listOf(parsedEpisode(0, guid = "old-ep", pubDate = NOW - 10 * DAY))),
                        IngestContext(mode = IngestMode.INITIAL, partial = false, fetch = fetchMeta()),
                    )
            assertEquals(1, stored.inserted.size)
            db.podcastDao().forceDue(scopeAll = false, ids = listOf(id))

            // itunes:complete ends the SHOW; without fh:complete the prev-archive link means older
            // pages exist, so the absent row below the window must not flip out (S10).
            val doc =
                """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel><title>Ended Show</title><link>https://example.com/s</link>
    <itunes:complete>yes</itunes:complete>
    <atom:link rel="prev-archive" href="https://example.com/s/2.xml"/>
    ${rssItem("new-ep")}
  </channel></rss>"""
            server.enqueue(mockResponse(body = doc))
            val bundle = newRssAdapter(root, clock)
            try {
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        settings,
                        tempFiles = bundle.tempFiles,
                    )

                val report = engine.run(request())

                assertIs<FeedOutcome.Ingested>(report.outcomes[id])
                assertTrue(db.episodeDao().byId(stored.inserted.single())!!.inFeed)
            } finally {
                bundle.close()
            }
        }

    // --- Finalisation flush failure -------------------------------------------------------------

    @Test
    fun aFailedFinalFlushFailsTheRunInsteadOfReportingCompletion() =
        runTest {
            // A persistent write failure: the outcome's sentinel etag aborts the fetch-state
            // UPDATE at the database, so the final flush's rows never commit.
            val id = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            db.installFlushFailure()
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.NotModified(meta = fetchMeta(etag = FLUSH_FAIL_ETAG)),
                    ),
                )
            val engine = refresher(adapter)

            val thrown =
                assertNotNull(
                    suspendRunCatching { engine.run(request()) }.exceptionOrNull(),
                    "a failed final flush must not return a completed report",
                )
            assertTrue(thrown.message.orEmpty().contains(FLUSH_FAIL_ETAG))

            // Cleanup still ran, but nothing claims a committed run.
            assertFalse(engine.status.value.running)
            assertNull(engine.status.value.lastRunFinishedAt)
            assertEquals(0L, settings.get(FeedsSettingKeys.LAST_RUN_FINISHED_AT))
            assertEquals(0L, settings.get(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT))
            assertEquals("", settings.get(FeedsSettingKeys.LAST_RUN_SUMMARY))

            // The uncommitted feed stays due — the next selection self-heals it.
            val stored = db.podcastDao().byId(id)!!
            assertNull(stored.etag)
            assertEquals(NOW - 1, stored.nextRefreshAt)
            assertTrue(
                db.podcastDao().dueForRefresh(clock.now(), scopeAll = true).any { it.id == id },
            )
        }

    @Test
    fun aTransientFlushFailureRecoversOnTheNextRun() =
        runTest {
            val id = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            db.installFlushFailure()
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            AdapterResult.NotModified(meta = fetchMeta(etag = FLUSH_FAIL_ETAG)),
                    ),
                )
            val engine = refresher(adapter)

            assertNotNull(suspendRunCatching { engine.run(request()) }.exceptionOrNull())

            // Once the fault clears the still-due feed is refetched and its outcome commits.
            db.clearFlushFailure()
            adapter.results["https://a.example.com/f"] =
                AdapterResult.NotModified(meta = fetchMeta(etag = "e-ok"))
            val report = engine.run(request())

            assertEquals(FeedOutcome.NotModified, report.outcomes[id])
            val stored = db.podcastDao().byId(id)!!
            assertEquals("e-ok", stored.etag)
            assertEquals(NOW, stored.lastSuccessAt)
            assertEquals(NOW, settings.get(FeedsSettingKeys.LAST_ALL_RUN_FINISHED_AT))
        }

    @Test
    fun aFailedBarrierFlushPropagatesAndTheRunCommitsTheRetainedRows() =
        runTest {
            // The user-action barrier (unsubscribe, "Try again") surfaces a flush failure to its
            // caller; the batcher keeps the unwritten rows, so the run's final flush still
            // commits them once the fault clears.
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            val a =
                seedPodcast(
                    db,
                    feedUrl = "https://a.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                )
            val b =
                seedPodcast(
                    db,
                    feedUrl = "https://b.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                )
            val adapter =
                stubAdapter(onFetch = { feed, _ ->
                    if (feed.id == b) gate.await()
                    AdapterResult.NotModified(
                        meta = fetchMeta(etag = if (feed.id == a) FLUSH_FAIL_ETAG else "e-b"),
                    )
                })
            db.installFlushFailure()
            val engine = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings)
            val run = backgroundScope.async { engine.run(request()) }
            testScheduler.runCurrent()

            assertNotNull(
                suspendRunCatching { engine.flushFetchStates() }.exceptionOrNull(),
                "the barrier flush must propagate a write failure to the user action",
            )

            db.clearFlushFailure()
            gate.complete(Unit)
            val report = run.await()

            assertEquals(setOf(a, b), report.outcomes.keys)
            assertEquals(FLUSH_FAIL_ETAG, db.podcastDao().byId(a)!!.etag)
            assertEquals("e-b", db.podcastDao().byId(b)!!.etag)
        }

    @Test
    fun aCancelledRunStaysCancellationAndAttachesTheFlushFailure() =
        runTest {
            // The run's cancellation is the primary cause: a final flush failure on top of it
            // attaches as suppressed instead of replacing the cancellation.
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            val a =
                seedPodcast(
                    db,
                    feedUrl = "https://a.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                )
            val b =
                seedPodcast(
                    db,
                    feedUrl = "https://b.example.com/f",
                    nextRefreshAt = NOW - 1,
                    subscribedAt = NOW - 30 * DAY,
                )
            val adapter =
                stubAdapter(onFetch = { feed, _ ->
                    if (feed.id == b) gate.await()
                    AdapterResult.NotModified(
                        meta = fetchMeta(etag = if (feed.id == a) FLUSH_FAIL_ETAG else "e-b"),
                    )
                })
            db.installFlushFailure()
            val engine = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings)
            var propagated: Throwable? = null
            val run =
                backgroundScope.async {
                    try {
                        engine.run(request())
                    } catch (t: Throwable) {
                        propagated = t
                        throw t
                    }
                }
            testScheduler.runCurrent()

            run.cancel()

            val awaited =
                try {
                    run.await()
                    null
                } catch (t: Throwable) {
                    t
                }
            // The deferred stays cancelled, and the exception the run() call itself propagates —
            // what a direct caller like RefreshWorker or DesktopRefreshLane catches — carries the
            // flush failure as suppressed.
            assertIs<CancellationException>(assertNotNull(awaited))
            val primary = assertIs<CancellationException>(assertNotNull(propagated))
            assertTrue(
                primary.suppressed.any { it.message.orEmpty().contains(FLUSH_FAIL_ETAG) },
                "the flush failure must ride along as suppressed, not replace the cancellation",
            )
            assertFalse(engine.status.value.running)
        }

    @Test
    fun aFailedFlushLeavesCommittedAndUncommittedOutcomesDistinguishable() =
        runTest {
            // Feed A's outcome commits inside its ingest transaction; feed B's batcher row aborts
            // in the final flush. The run must fail: a returned report would claim both landed.
            val a = due("https://a.example.com/f", nextRefreshAt = NOW - 1)
            val b = due("https://b.example.com/f", nextRefreshAt = NOW - 1)
            db.installFlushFailure()
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to
                            adapterParsed(
                                parsedFeed(
                                    items = listOf(parsedEpisode(0, guid = "a", pubDate = NOW)),
                                ),
                                meta = fetchMeta(etag = "e-committed"),
                            ),
                        "https://b.example.com/f" to
                            AdapterResult.NotModified(meta = fetchMeta(etag = FLUSH_FAIL_ETAG)),
                    ),
                )
            val engine = refresher(adapter)

            assertNotNull(suspendRunCatching { engine.run(request()) }.exceptionOrNull())

            val committed = db.podcastDao().byId(a)!!
            assertEquals("e-committed", committed.etag)
            assertEquals(NOW, committed.lastSuccessAt)
            assertNotNull(db.episodeDao().byIdentityKey(a, "g:a"))
            val lost = db.podcastDao().byId(b)!!
            assertNull(lost.etag)
            assertEquals(NOW - 1, lost.nextRefreshAt)
        }

    // --- Helpers --------------------------------------------------------------------------------------

    /** One statement on the writer connection (DDL for the flush-failure trigger). */
    private suspend fun NeutrodyneDatabase.exec(sql: String) {
        useWriterConnection { conn -> conn.usePrepared(sql) { it.step() } }
    }

    /**
     * A persistent fetch-state write failure: any `podcast` UPDATE carrying the sentinel etag
     * (the `updateFetchStates` batch row, or an ingest's `applyFeedMetadata`) aborts at SQLite.
     */
    private suspend fun NeutrodyneDatabase.installFlushFailure() =
        exec(
            "CREATE TRIGGER fail_fetch_state_flush BEFORE UPDATE OF etag ON podcast" +
                " WHEN NEW.etag = '$FLUSH_FAIL_ETAG'" +
                " BEGIN SELECT RAISE(ABORT, '$FLUSH_FAIL_ETAG'); END",
        )

    private suspend fun NeutrodyneDatabase.clearFlushFailure() = exec("DROP TRIGGER fail_fetch_state_flush")

    private fun assertInWindow(
        value: Long,
        low: Long,
        high: Long,
    ) = assertTrue(value in low..high, "expected $value in $low..$high")

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val DAY = 86_400_000L

        /** The sentinel `etag` value that makes the `fail_fetch_state_flush` trigger abort. */
        const val FLUSH_FAIL_ETAG = "x-flush-fail"
    }
}
