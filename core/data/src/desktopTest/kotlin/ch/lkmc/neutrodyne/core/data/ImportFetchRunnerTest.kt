// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.importing.ImportFetchResult
import ch.lkmc.neutrodyne.core.data.importing.ImportFetchRunner
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.notifications.NewEpisodeNotifier
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.ImportItemEntity
import ch.lkmc.neutrodyne.core.database.ImportSessionEntity
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeImportReportPoster
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 05 §7's `ImportFetchRunner` over the real `FeedRefresher` and a real Room database; the
 * scripted [StubSourceAdapter] and [TestClock] keep fetches, outcomes and deadlines
 * deterministic. Covers the loop contract: no new-episode effects on an initial fetch, the
 * `lastAttemptAt >= attemptMarkerAt` dedupe (across periodic runs and restarts), busy-engine
 * yield, offline/deadline retries, cancellation, attempt exhaustion and the finish guard.
 */
class ImportFetchRunnerTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()
    private val poster = FakeNewEpisodePoster()
    private val reports = FakeImportReportPoster()
    private val snapshots = FakeSnapshotScheduler()
    private val adapter = StubSourceAdapter()
    private val engine = engineFor(adapter)
    private val runner = ImportFetchRunner(db, engine, snapshots, reports, clock)

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // --- Fetch and finish -------------------------------------------------------------------------

    @Test
    fun anInitialImportFetchCreatesNoNewEpisodeEffects() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val podcastId = pendingPodcast(FEED_A)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)
            adapter.results[FEED_A] =
                adapterParsed(parsedFeed(items = listOf(parsedEpisode(0, guid = "one", pubDate = NOW))))

            val result = runner.run(sessionId, attempt = 0)

            assertEquals(ImportFetchResult.COMPLETED, result)
            assertEquals(listOf(podcastId), adapter.calls.map { it.first })
            assertSessionDone(sessionId)
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            // INITIAL ingest marks nothing new; nothing reaches the poster despite the
            // notify-everything settings.
            assertTrue(poster.posts.isEmpty(), "an initial import fetch must not notify")
            val post = reports.posts.single()
            assertEquals(sessionId, post.sessionId)
            assertEquals(mapOf(ImportItemStatus.SUBSCRIBED to 1), post.counts)
            assertEquals(1, snapshots.requestSoonCalls)
        }

    @Test
    fun aBusyEngineYieldsRetryAndTheNextRunRecovers() =
        runTest {
            val podcastId = pendingPodcast(FEED_A)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)
            adapter.results[FEED_A] = adapterParsed(parsedFeed())

            val gate = CompletableDeferred<Unit>()
            val holder = backgroundScope.launch { engine.withRunExclusive { gate.await() } }
            testScheduler.runCurrent()

            // A deadline inside the host margin still reaches the engine, whose tryLock path
            // loses while the exclusive block holds the run mutex.
            val result =
                runner.run(
                    sessionId,
                    attempt = 0,
                    deadlineElapsedMs = clock.elapsedRealtime() + 30_000,
                )

            assertEquals(ImportFetchResult.RETRY, result)
            assertTrue(adapter.calls.isEmpty(), "the busy run must not fetch")
            assertEquals(ImportState.FETCHING, session(sessionId).state)
            assertItem(sessionId, 0, ImportItemStatus.QUEUED)
            assertTrue(reports.posts.isEmpty())

            gate.complete(Unit)
            holder.join()

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 0))
            assertSessionDone(sessionId)
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            assertEquals(listOf(podcastId), adapter.calls.map { it.first })
        }

    @Test
    fun anOfflineOutcomeRetriesAndStampsNothing() =
        runTest {
            val podcastId = pendingPodcast(FEED_A)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)
            adapter.results[FEED_A] =
                AdapterResult.Failed(FeedErrorKind.OFFLINE, http = null, retryAfterMs = null, transient = true)

            assertEquals(ImportFetchResult.RETRY, runner.run(sessionId, attempt = 0))

            assertEquals(ImportState.FETCHING, session(sessionId).state)
            assertItem(sessionId, 0, ImportItemStatus.QUEUED)
            // OFFLINE is "not attempted" — the engine writes no fetch state for it.
            assertNull(db.podcastDao().byId(podcastId)!!.lastAttemptAt)
            assertTrue(reports.posts.isEmpty())
            assertEquals(0, snapshots.requestSoonCalls)

            adapter.results[FEED_A] = adapterParsed(parsedFeed())
            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 1))

            assertSessionDone(sessionId)
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            assertEquals(2, adapter.calls.size)
        }

    // --- Attempt markers and resume ----------------------------------------------------------------

    @Test
    fun podcastsAttemptedAtOrAfterTheMarkerAreNotRefetched() =
        runTest {
            // A periodic refresh (or an earlier, dead host run) already attempted both feeds at
            // the marker: the runner derives their statuses and fetches nothing.
            val done =
                seedPodcast(
                    db,
                    feedUrl = FEED_A,
                    status = PodcastStatus.ACTIVE,
                    subscribedAt = MARKER - 1,
                    lastAttemptAt = MARKER,
                )
            val failed =
                seedPodcast(
                    db,
                    feedUrl = FEED_B,
                    status = PodcastStatus.PENDING_FIRST_FETCH,
                    initialFetch = true,
                    subscribedAt = MARKER - 1,
                    lastAttemptAt = MARKER,
                    failureCount = 1,
                    block = { copy(lastErrorKind = FeedErrorKind.PARSE_ERROR, lastErrorDetail = "xml") },
                )
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = done)
            queueItem(sessionId, ordinal = 1, url = FEED_B, podcastId = failed)

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 0))

            assertTrue(adapter.calls.isEmpty(), "attempted podcasts must not be fetched again")
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            assertItem(sessionId, 1, ImportItemStatus.NOT_A_FEED)
            assertSessionDone(sessionId)
        }

    @Test
    fun aRestartedLaneResumesAFetchingSession() =
        runTest {
            // The process died (or the lane stopped) with the session already FETCHING and the
            // podcast never attempted; the next run picks it up where it left off.
            val podcastId = pendingPodcast(FEED_A)
            val sessionId = newSession(state = ImportState.FETCHING)
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)
            adapter.results[FEED_A] = adapterParsed(parsedFeed())

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 3))

            assertEquals(listOf(podcastId), adapter.calls.map { it.first })
            assertSessionDone(sessionId)
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
        }

    @Test
    fun aNearDeadlineRetriesBeforeTouchingTheEngine() =
        runTest {
            val podcastId = pendingPodcast(FEED_A)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)

            val result =
                runner.run(
                    sessionId,
                    attempt = 0,
                    deadlineElapsedMs = clock.elapsedRealtime() + 10_000,
                )

            assertEquals(ImportFetchResult.RETRY, result)
            assertTrue(adapter.calls.isEmpty())
            assertEquals(ImportState.FETCHING, session(sessionId).state)
            assertItem(sessionId, 0, ImportItemStatus.QUEUED)
        }

    // --- Cancellation ------------------------------------------------------------------------------

    @Test
    fun cancellationKeepsCommittedIngestsAndTheRunResumable() =
        runTest {
            val gated = pendingPodcast(FEED_A)
            val committed = pendingPodcast(FEED_B)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = gated)
            queueItem(sessionId, ordinal = 1, url = FEED_B, podcastId = committed)

            val fetchStarted = CompletableDeferred<Unit>()
            val ingestCommitted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val gatedAdapter =
                stubAdapter(
                    results =
                        mutableMapOf(
                            FEED_A to adapterParsed(parsedFeed()),
                            FEED_B to
                                adapterParsed(
                                    parsedFeed(
                                        items = listOf(parsedEpisode(0, guid = "b", pubDate = NOW)),
                                    ),
                                ),
                        ),
                    onFetch = { feed, _ ->
                        if (feed.id == gated) {
                            fetchStarted.complete(Unit)
                            gate.await()
                        }
                        null
                    },
                    announce = { podcastId, _, ids ->
                        if (podcastId == committed) ingestCommitted.complete(Unit)
                        ids
                    },
                )
            val gatedRunner = runnerFor(gatedAdapter)

            val job = backgroundScope.launch { gatedRunner.run(sessionId, attempt = 0) }
            fetchStarted.await()
            ingestCommitted.await()
            job.cancelAndJoin()

            // The committed ingest survives cancellation; the in-flight fetch rolled back, the
            // session stays FETCHING and neither report nor snapshot was requested.
            assertTrue(job.isCancelled)
            assertEquals(ImportState.FETCHING, session(sessionId).state)
            assertItem(sessionId, 0, ImportItemStatus.QUEUED)
            assertItem(sessionId, 1, ImportItemStatus.QUEUED)
            assertTrue(db.podcastDao().episodeCount(committed) > 0, "a committed ingest is never rolled back")
            assertNull(db.podcastDao().byId(gated)!!.lastAttemptAt, "the in-flight fetch rolled back")
            assertTrue(reports.posts.isEmpty())
            assertEquals(0, snapshots.requestSoonCalls)

            // The resume lands no second fetch for the committed podcast — its attempt marker
            // already passed — and only retries the interrupted one.
            gate.complete(Unit)
            assertEquals(ImportFetchResult.COMPLETED, gatedRunner.run(sessionId, attempt = 1))

            assertSessionDone(sessionId)
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            assertItem(sessionId, 1, ImportItemStatus.SUBSCRIBED)
            assertEquals(2, gatedAdapter.calls.count { it.first == gated })
            assertEquals(1, gatedAdapter.calls.count { it.first == committed })
        }

    @Test
    fun aCancelledSessionStaysCancelledAndPostsNothing() =
        runTest {
            val podcastId = pendingPodcast(FEED_A)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)

            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val gatedAdapter =
                stubAdapter(
                    results = mutableMapOf(FEED_A to adapterParsed(parsedFeed())),
                    onFetch = { feed, _ ->
                        if (feed.id == podcastId) {
                            started.complete(Unit)
                            gate.await()
                        }
                        null
                    },
                )
            val gatedRunner = runnerFor(gatedAdapter)
            val job = backgroundScope.async { gatedRunner.run(sessionId, attempt = 0) }
            started.await()

            // B7's cancel: the row goes CANCELLED while its fetch is still in flight.
            db.importDao().setState(sessionId, ImportState.CANCELLED)
            gate.complete(Unit)

            assertEquals(ImportFetchResult.COMPLETED, job.await())

            assertEquals(ImportState.CANCELLED, session(sessionId).state)
            // The fetch and ingest genuinely committed — the item records what happened…
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            // …but CANCELLED is never overwritten into DONE and no done effects fire.
            assertTrue(reports.posts.isEmpty())
            assertEquals(0, snapshots.requestSoonCalls)
        }

    // --- Exhaustion and the finish guard -------------------------------------------------------------

    @Test
    fun attemptExhaustionMarksRemainingItemsNotAttempted() =
        runTest {
            val first = pendingPodcast(FEED_A)
            val second = pendingPodcast(FEED_B)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = first)
            queueItem(sessionId, ordinal = 1, url = FEED_B, podcastId = second)

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 20))

            assertTrue(adapter.calls.isEmpty(), "an exhausted lane never fetches")
            assertItem(sessionId, 0, ImportItemStatus.FETCH_FAILED, "NOT_ATTEMPTED")
            assertItem(sessionId, 1, ImportItemStatus.FETCH_FAILED, "NOT_ATTEMPTED")
            assertSessionDone(sessionId)
            assertEquals(mapOf(ImportItemStatus.FETCH_FAILED to 2), reports.posts.single().counts)
        }

    @Test
    fun aMidRunRequeueIsNotLostToTheFinish() =
        runTest {
            // Item B failed before this run (status FETCH_FAILED, attempt older than the
            // marker); a §8 retry fix-up requeues it and bumps the marker while A's fetch is
            // in flight — the run must pick it up rather than finish over it.
            val a = pendingPodcast(FEED_A)
            val b =
                seedPodcast(
                    db,
                    feedUrl = FEED_B,
                    status = PodcastStatus.PENDING_FIRST_FETCH,
                    initialFetch = true,
                    subscribedAt = MARKER - 2,
                    lastAttemptAt = MARKER - 1,
                    failureCount = 1,
                )
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = a)
            queueItem(sessionId, ordinal = 1, url = FEED_B, podcastId = b, status = ImportItemStatus.FETCH_FAILED)

            val fixupAdapter =
                stubAdapter(
                    results =
                        mutableMapOf(
                            FEED_A to adapterParsed(parsedFeed()),
                            FEED_B to adapterParsed(parsedFeed()),
                        ),
                    onFetch = { feed, _ ->
                        if (feed.id == a) {
                            db.withWriteTransaction {
                                val dao = db.importDao()
                                dao.requeueItems(sessionId, listOf(1))
                                clock.nowMs += 1
                                dao.setOptionsJson(
                                    sessionId,
                                    ImportOptionsJson.encode(
                                        ImportOptions(
                                            committedAt = MARKER,
                                            attemptMarkerAt = clock.now(),
                                        ),
                                    ),
                                )
                                dao.setState(sessionId, ImportState.FETCHING)
                            }
                        }
                        null
                    },
                )
            val fixupRunner = runnerFor(fixupAdapter)

            assertEquals(ImportFetchResult.COMPLETED, fixupRunner.run(sessionId, attempt = 0))

            assertEquals(listOf(a, b), fixupAdapter.calls.map { it.first })
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            assertItem(sessionId, 1, ImportItemStatus.SUBSCRIBED)
            assertSessionDone(sessionId)
            assertEquals(1, reports.posts.size)
        }

    @Test
    fun aMarkerBumpDuringTheFetchRefetchesTheItem() =
        runTest {
            // A §8 retry fix-up lands after the in-flight fetch's attempt stamp committed
            // but before its outcome reached the runner: the pre-marker outcome must not
            // finalise the item — the loop derives it pending again and refetches.
            val a = pendingPodcast(FEED_A)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = a)

            var bumped = false
            val fixupAdapter =
                stubAdapter(
                    results =
                        mutableMapOf(
                            FEED_A to
                                adapterParsed(
                                    parsedFeed(
                                        items = listOf(parsedEpisode(0, guid = "a", pubDate = NOW)),
                                    ),
                                ),
                        ),
                    announce = { podcastId, _, ids ->
                        if (podcastId == a && !bumped) {
                            bumped = true
                            db.withWriteTransaction {
                                val dao = db.importDao()
                                dao.requeueItems(sessionId, listOf(0))
                                clock.nowMs += 1
                                dao.setOptionsJson(
                                    sessionId,
                                    ImportOptionsJson.encode(
                                        ImportOptions(
                                            committedAt = MARKER,
                                            attemptMarkerAt = clock.now(),
                                        ),
                                    ),
                                )
                                dao.setState(sessionId, ImportState.FETCHING)
                            }
                        }
                        ids
                    },
                )
            val fixupRunner = runnerFor(fixupAdapter)

            assertEquals(ImportFetchResult.COMPLETED, fixupRunner.run(sessionId, attempt = 0))

            assertEquals(
                2,
                fixupAdapter.calls.count { it.first == a },
                "the bumped marker demands a fresh attempt, not the stale outcome",
            )
            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            assertSessionDone(sessionId)
        }

    // --- Unresolved YouTube rows ---------------------------------------------------------------------

    @Test
    fun anUnresolvedYouTubeItemStaysExplicitlyUnsupported() =
        runTest {
            val sessionId = newSession()
            queueItem(
                sessionId,
                ordinal = 0,
                url = "https://www.youtube.com/@handle",
                podcastId = null,
                kind = ImportItemKind.YOUTUBE,
                normalizedUrl = null,
            )

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 0))

            assertTrue(adapter.calls.isEmpty())
            assertItem(sessionId, 0, ImportItemStatus.YOUTUBE_UNSUPPORTED_YET)
            assertSessionDone(sessionId)
        }

    @Test
    fun aLinkedPodcastNoAdapterSelectsEndsNotAttempted() =
        runTest {
            // Restore (and later M8) can link a QUEUED item to a non-RSS podcast: no bound
            // adapter can ever select it, so the run must finalise the item instead of
            // deriving it forever. The bound time is wall-clock — without a termination
            // path the loop spins.
            val channel =
                seedPodcast(
                    db,
                    feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UCx",
                    status = PodcastStatus.PENDING_FIRST_FETCH,
                    initialFetch = true,
                    subscribedAt = MARKER - 1,
                    block = { copy(sourceType = SourceType.YOUTUBE_CHANNEL) },
                )
            val sessionId = newSession()
            queueItem(
                sessionId,
                ordinal = 0,
                url = "https://www.youtube.com/channel/UCx",
                podcastId = channel,
                kind = ImportItemKind.YOUTUBE,
            )

            val result =
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(15_000) { runner.run(sessionId, attempt = 0) }
                }

            assertEquals(ImportFetchResult.COMPLETED, result)
            assertTrue(adapter.calls.isEmpty(), "an unselectable feed is never fetched")
            assertItem(sessionId, 0, ImportItemStatus.FETCH_FAILED, "NOT_ATTEMPTED")
            assertSessionDone(sessionId)
        }

    // --- Outcome table ------------------------------------------------------------------------------

    @Test
    fun aMergedOutcomeMarksTheItemMerged() =
        runTest {
            // The fetched feed's permanent URL is already subscribed: 02's merge keeps the
            // winner, deletes the loser and repoints the item — §7's outcome column reads
            // MERGED.
            val winner =
                seedPodcast(
                    db,
                    feedUrl = FEED_A,
                    status = PodcastStatus.ACTIVE,
                    subscribedAt = MARKER - 1,
                )
            val loser = pendingPodcast(FEED_B)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_B, podcastId = loser)
            adapter.results[FEED_B] =
                adapterParsed(
                    parsedFeed(items = listOf(parsedEpisode(0, guid = "b", pubDate = NOW))),
                    meta = fetchMeta(finalUrl = FEED_B, requestedUrl = FEED_B, permanentUrl = FEED_A),
                )

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 0))

            assertItem(sessionId, 0, ImportItemStatus.MERGED)
            assertSessionDone(sessionId)
            assertNull(db.podcastDao().byId(loser), "the merge deletes the consumed row")
            val item = db.importDao().itemsOf(sessionId).single { it.ordinal == 0 }
            assertEquals(winner, item.podcastId, "02's merge repoints the item at the winner")
            assertTrue(
                db.podcastDao().aliases(winner).any {
                    it.url == UrlNormalizer.forIdentity(FEED_B) && it.reason == AliasReason.MERGE
                },
                "the consumed feed identity stays a MERGE alias of the winner",
            )
        }

    @Test
    fun aDeferredOutcomeFailsAsDeferredWithoutAnAttemptStamp() =
        runTest {
            // 04's "not attempted; retry later" outcome: §7 maps it to the DEFERRED bucket —
            // terminal for this run, and it never stamps `lastAttemptAt`.
            val podcastId = pendingPodcast(FEED_A)
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)
            adapter.results[FEED_A] = AdapterResult.Deferred(NOW + 60_000)

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 0))

            assertItem(sessionId, 0, ImportItemStatus.FETCH_FAILED, "DEFERRED")
            assertNull(db.podcastDao().byId(podcastId)!!.lastAttemptAt)
            assertSessionDone(sessionId)
        }

    @Test
    fun blockedPodcastsFinaliseFromTheRowWithoutAFetch() =
        runTest {
            // The loop's termination for feeds the engine will not select: `gone` and
            // `needsCredentials` rows derive their final status without a fetch.
            val gonePodcast =
                seedPodcast(
                    db,
                    feedUrl = FEED_A,
                    status = PodcastStatus.ACTIVE,
                    subscribedAt = MARKER - 1,
                    gone = true,
                )
            val authPodcast =
                seedPodcast(
                    db,
                    feedUrl = FEED_B,
                    status = PodcastStatus.ACTIVE,
                    subscribedAt = MARKER - 1,
                    needsCredentials = true,
                )
            val sessionId = newSession()
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = gonePodcast)
            queueItem(sessionId, ordinal = 1, url = FEED_B, podcastId = authPodcast)

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 0))

            assertTrue(adapter.calls.isEmpty(), "blocked feeds are never fetched")
            assertItem(sessionId, 0, ImportItemStatus.GONE)
            assertItem(sessionId, 1, ImportItemStatus.AUTH_REQUIRED)
            assertSessionDone(sessionId)
        }

    @Test
    fun treatExistingAsPlayedMarksEveryNonNewEpisodeButTheNewest() =
        runTest {
            // D66: the `SUBSCRIBED` write and the mark-played chain share one transaction —
            // every non-new episode except the newest is played, its position resets and
            // its queue entry leaves Up next.
            val podcastId =
                seedPodcast(
                    db,
                    feedUrl = FEED_A,
                    status = PodcastStatus.ACTIVE,
                    subscribedAt = MARKER - 1,
                )
            val ids =
                db.ingestDao().insertEpisodes(
                    listOf(
                        episodeEntity(podcastId = podcastId, identityKey = "e0", sortDate = NOW - 3_000),
                        episodeEntity(podcastId = podcastId, identityKey = "e1", sortDate = NOW - 2_000),
                        episodeEntity(podcastId = podcastId, identityKey = "e2", sortDate = NOW - 1_000),
                    ),
                )
            db.positionDao().upsert(
                EpisodePositionEntity(
                    episodeId = ids[0],
                    positionMs = 42_000,
                    positionSource = PositionSource.STREAM,
                    updatedAt = NOW,
                ),
            )
            db.queueDao().insert(queueEntryEntity(episodeId = ids[0], orderKey = "q0"))
            val sessionId =
                newSession(
                    options =
                        ImportOptions(
                            committedAt = MARKER,
                            attemptMarkerAt = MARKER,
                            treatExistingAsPlayed = true,
                        ),
                )
            queueItem(sessionId, ordinal = 0, url = FEED_A, podcastId = podcastId)
            adapter.results[FEED_A] = AdapterResult.Unchanged(meta = fetchMeta())

            assertEquals(ImportFetchResult.COMPLETED, runner.run(sessionId, attempt = 0))

            assertItem(sessionId, 0, ImportItemStatus.SUBSCRIBED)
            assertNotNull(db.episodeStateDao().byEpisode(ids[0])?.playedAt)
            assertNotNull(db.episodeStateDao().byEpisode(ids[1])?.playedAt)
            assertNull(db.episodeStateDao().byEpisode(ids[2])?.playedAt, "the newest stays unplayed")
            assertEquals(0L, db.positionDao().byEpisode(ids[0])?.positionMs)
            assertTrue(db.queueDao().entries().isEmpty(), "played episodes leave Up next")
            assertSessionDone(sessionId)
        }

    // --- Fixtures ------------------------------------------------------------------------------------

    private fun engineFor(stub: StubSourceAdapter) =
        newRefresher(
            db,
            mapOf(SourceType.RSS to stub),
            clock,
            settings,
            notifier = NewEpisodeNotifier(db, EffectiveSettingsResolverImpl(db, settings), poster),
        )

    private fun runnerFor(stub: StubSourceAdapter) = ImportFetchRunner(db, engineFor(stub), snapshots, reports, clock)

    private suspend fun pendingPodcast(feedUrl: String): Long =
        seedPodcast(
            db,
            feedUrl = feedUrl,
            status = PodcastStatus.PENDING_FIRST_FETCH,
            initialFetch = true,
            subscribedAt = MARKER - 1,
        )

    private suspend fun newSession(
        state: ImportState = ImportState.COMMITTED,
        options: ImportOptions = ImportOptions(committedAt = MARKER, attemptMarkerAt = MARKER),
    ): Long =
        db
            .importDao()
            .insertSession(
                ImportSessionEntity(
                    createdAt = MARKER,
                    sourceFormat = ImportFormat.OPML,
                    state = state,
                    optionsJson = ImportOptionsJson.encode(options),
                ),
            )

    private suspend fun queueItem(
        sessionId: Long,
        ordinal: Int,
        url: String,
        podcastId: Long?,
        kind: ImportItemKind = ImportItemKind.RSS,
        status: ImportItemStatus = ImportItemStatus.QUEUED,
        normalizedUrl: String? = url,
    ) {
        db
            .importDao()
            .insertItems(
                listOf(
                    ImportItemEntity(
                        sessionId = sessionId,
                        ordinal = ordinal,
                        title = url,
                        originalUrl = url,
                        normalizedUrl = normalizedUrl,
                        kind = kind,
                        groupNamesJson = "[]",
                        selected = true,
                        status = status,
                        podcastId = podcastId,
                    ),
                ),
            )
    }

    private suspend fun session(sessionId: Long) = checkNotNull(db.importDao().sessionById(sessionId))

    private suspend fun assertSessionDone(sessionId: Long) {
        val session = session(sessionId)
        assertEquals(ImportState.DONE, session.state)
        assertNotNull(session.finishedAt)
    }

    private suspend fun assertItem(
        sessionId: Long,
        ordinal: Int,
        status: ImportItemStatus,
        errorDetail: String? = null,
    ) {
        val item = db.importDao().itemsOf(sessionId).single { it.ordinal == ordinal }
        assertEquals(status, item.status)
        assertEquals(errorDetail, item.errorDetail)
    }

    private companion object {
        const val FEED_A = "https://a.example.com/feed.xml"
        const val FEED_B = "https://b.example.com/feed.xml"
        const val NOW = TestClock.DEFAULT_NOW
        const val MARKER = NOW
    }
}
