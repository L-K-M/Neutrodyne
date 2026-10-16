// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.DesktopRefreshLane
import ch.lkmc.neutrodyne.core.data.refresh.DesktopRefreshScheduler
import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.data.refresh.NextRefreshRebaser
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshPolicy
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupSettingsEntity
import ch.lkmc.neutrodyne.core.database.PodcastSettingsEntity
import ch.lkmc.neutrodyne.core.database.ScopeOverrides
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 03 Desktop refresh — the `refresh` lane: an offline run is free, queued requests drain in order
 * (forced scopes persisted first), and the automatic selection picks up feeds due inside the
 * 15-minute slack or holding a stranded paging cursor. Runs are driven by the fake scheduler's
 * queue plus the real engine over the scripted adapter. The MS2-tagged rows of 03's Testing
 * table stay out here.
 */
class DesktopRefreshLaneTest {
    private val clock = TestClock()
    private lateinit var db: NeutrodyneDatabase
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val pokes = mutableListOf<String>()

    @Test
    fun offlineRunDoesNothing() =
        laneTest {
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            val adapter = stubAdapter()
            val lane = lane(adapter, queue(this))

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertTrue(adapter.calls.isEmpty())
            assertEquals(0, summaryCount())
        }

    @Test
    fun queuedForcedRequestPersistsDueThenRuns() =
        laneTest {
            val id =
                seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            val adapter = stubAdapter()
            val q = queue(this)
            val lane = lane(adapter, q)

            q.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(id)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.MANUAL,
            )
            testScheduler.advanceUntilIdle()

            // `force = true` persisted `nextRefreshAt = 0` before the request queued (03).
            assertEquals(0L, db.podcastDao().byId(id)!!.nextRefreshAt)
            assertEquals(listOf(DesktopRefreshLane.NAME), pokes)

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertEquals(listOf(id), adapter.calls.map { it.first })
        }

    @Test
    fun queuedRequestsDrainInOrder() =
        laneTest {
            val a = seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            val b = seedPodcast(db, "https://b.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            val adapter = stubAdapter()
            val q = queue(this)
            val lane = lane(adapter, q)

            q.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(a)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.MANUAL,
            )
            q.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(b)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.FOREGROUND,
            )
            testScheduler.advanceUntilIdle()

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertEquals(listOf(a, b), adapter.calls.map { it.first })
        }

    @Test
    fun feedsDueInsideTheSlackTriggerAnAutomaticRun() =
        laneTest {
            val soon =
                seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW + 10 * 60_000L)
            seedPodcast(db, "https://b.example.com/f", nextRefreshAt = NOW + 20 * 60_000L)
            val adapter = stubAdapter()
            val lane = lane(adapter, queue(this))

            lane.run(Instant.fromEpochMilliseconds(NOW))

            // Slack is 15 min: `soon` qualifies, `later` does not.
            assertEquals(listOf(soon), adapter.calls.map { it.first })
        }

    @Test
    fun nothingDueMeansNoRun() =
        laneTest {
            seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW + 20 * 60_000L)
            val adapter = stubAdapter()
            val lane = lane(adapter, queue(this))

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertTrue(adapter.calls.isEmpty())
            assertEquals(0, summaryCount())
        }

    @Test
    fun forceAllInTheQueueMarksEveryHealthyFeedDue() =
        laneTest {
            val a = seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW + 60 * DAY)
            val b =
                seedPodcast(db, "https://b.example.com/f", nextRefreshAt = NOW + 60 * DAY) {
                    copy(gone = true)
                }
            val adapter = stubAdapter()
            val q = queue(this)
            val lane = lane(adapter, q)

            q.enqueueNow(
                scope = RefreshScope.All,
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.MANUAL,
            )
            testScheduler.advanceUntilIdle()
            lane.run(Instant.fromEpochMilliseconds(NOW))

            // The `forceDue` write excluded `gone`; the run fetched only the healthy feed.
            assertEquals(listOf(a), adapter.calls.map { it.first })
            assertTrue(db.podcastDao().byId(a)!!.lastAttemptAt != null)
            assertNull(db.podcastDao().byId(b)!!.lastAttemptAt)
        }

    @Test
    fun aQueuedRetrySurvivesAStaleOutcomeCommittedBeforeTheDrain() =
        laneTest {
            // r4 F3 desktop leg: the queued request itself carries the retry's scope, force
            // and origin — a stale outcome landing between enqueue and drain cannot consume
            // it, because the RETRY origin re-applies the clear and the force mark under the
            // engine mutex when the drain runs it.
            val id =
                seedPodcast(
                    db,
                    "https://a.example.com/f",
                    nextRefreshAt = NOW + 60 * DAY,
                    failureCount = 3,
                )
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to AdapterResult.NotModified(meta = null),
                    ),
                )
            val q = queue(this)
            val lane = lane(adapter, q)

            // `PodcastRepository.retry`'s legs: the immediate clear, then the queued request.
            db.podcastDao().clearRefreshBlock(id)
            q.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(id)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.RETRY,
            )
            testScheduler.advanceUntilIdle()

            // The older run's outcome lands before the lane drains: the block is re-set and
            // the due mark pushed out — erasing the marks alone.
            db.podcastDao().updateFetchStates(
                listOf(fetchState(id, nextRefreshAt = NOW + 60 * DAY).copy(failureCount = 3, gone = true)),
            )

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertEquals(listOf(id), adapter.calls.map { it.first })
            assertFalse(db.podcastDao().byId(id)!!.gone)
            assertEquals(0, db.podcastDao().byId(id)!!.failureCount)
        }

    @Test
    fun pendingPagingResumesEvenWhenNoHeadIsDue() =
        laneTest {
            // "Manual only" (interval 0): the head never falls due, but a stranded "Load older
            // episodes"/subscribe-backfill cursor must still resume (03 Desktop refresh step 2 —
            // `pagingPending` does not consult `nextRefreshAt`).
            val id =
                seedPodcast(
                    db,
                    "https://a.example.com/f",
                    nextRefreshAt = NOW + 365 * DAY,
                    pagingNextUrl = "https://a.example.com/page2",
                    pagingComplete = false,
                )
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/page2" to
                            adapterParsed(parsedFeed(items = listOf(parsedEpisode(1)))),
                    ),
                )
            val lane = lane(adapter, queue(this))

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertEquals(listOf(id to FetchMode.OLDER_PAGE), adapter.calls)
        }

    @Test
    fun aPagesOnlyRequestDrainsInOrderWithANormalOne() =
        laneTest {
            // 03 Testing: a pages-only request drains in order beside a normal one and the
            // pages-only run never touches the feed's head.
            val a = seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW)
            val b =
                seedPodcast(
                    db,
                    "https://b.example.com/f",
                    nextRefreshAt = NOW + 365 * DAY,
                    pagingNextUrl = "https://b.example.com/page2",
                    pagingComplete = false,
                )
            val adapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to AdapterResult.NotModified(meta = null),
                        "https://b.example.com/page2" to
                            adapterParsed(parsedFeed(items = listOf(parsedEpisode(1)))),
                    ),
                )
            val q = queue(this)
            val lane = lane(adapter, q)

            q.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(a)),
                force = false,
                pagesOnly = false,
                origin = RefreshOrigin.MANUAL,
            )
            q.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(b)),
                force = false,
                pagesOnly = true,
                origin = RefreshOrigin.MANUAL,
            )
            testScheduler.advanceUntilIdle()

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertEquals(
                listOf(a to FetchMode.REFRESH, b to FetchMode.OLDER_PAGE),
                adapter.calls,
            )
        }

    @Test
    fun aForcedScopeSurvivesASimulatedQuit() =
        laneTest {
            // 03 Testing: `force` is persisted before the request queues, so a quit mid-drain
            // loses the in-memory request but not the mark — the next start's automatic
            // selection picks the feed up.
            val id =
                seedPodcast(
                    db,
                    "https://a.example.com/f",
                    nextRefreshAt = NOW + 365 * DAY,
                )
            val gate = Channel<Unit>()
            val adapter =
                stubAdapter(
                    onFetch = { _, _ ->
                        gate.receive()
                        AdapterResult.NotModified(meta = null)
                    },
                )
            val q = queue(this)
            val lane = lane(adapter, q)

            q.enqueueNow(
                scope = RefreshScope.Podcasts(listOf(id)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.MANUAL,
            )
            testScheduler.advanceUntilIdle()

            // The drain reaches the fetch and sticks on it — the "quit".
            val runJob = launch { lane.run(Instant.fromEpochMilliseconds(NOW)) }
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(id to FetchMode.REFRESH), adapter.calls)

            runJob.cancel()
            testScheduler.advanceUntilIdle()

            // The persisted mark survived the dropped request.
            assertEquals(0L, db.podcastDao().byId(id)!!.nextRefreshAt)

            // A fresh lane (empty queue, like a restart) finds the feed in the automatic
            // selection without any enqueue.
            val restartAdapter =
                stubAdapter(
                    mutableMapOf(
                        "https://a.example.com/f" to AdapterResult.NotModified(meta = null),
                    ),
                )
            val restartLane = lane(restartAdapter, queue(this))
            restartLane.run(Instant.fromEpochMilliseconds(NOW))

            assertEquals(listOf(id to FetchMode.REFRESH), restartAdapter.calls)
        }

    @Test
    fun requestFirstFetchPokesTheLane() =
        laneTest {
            // 03 Testing: a sync-added podcast's first fetch asks the runner for the lane.
            val q = queue(this)

            q.requestFirstFetch()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(DesktopRefreshLane.NAME), pokes)
        }

    @Test
    fun effectiveIntervalsDriveAutomaticSelectionAndManualOnlyStillAllowsForce() =
        laneTest {
            settings.set(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES, 720)
            val timed =
                seedPodcast(db, "https://timed.example.com/f", nextRefreshAt = null, subscribedAt = NOW - 60 * 60_000L)
            val manual = seedPodcast(db, "https://manual.example.com/f", nextRefreshAt = null, subscribedAt = NOW)
            val global = seedPodcast(db, "https://global.example.com/f", nextRefreshAt = null, subscribedAt = NOW)
            val group = db.groupDao().insert(groupEntity(orderKey = "a"))
            val manualGroup = db.groupDao().insert(groupEntity(name = "manual", orderKey = "b"))
            db.groupDao().insertMember(memberEntity(group, timed, "a"))
            db.groupDao().insertMember(memberEntity(manualGroup, timed, "a"))
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(group, ScopeOverrides(refreshIntervalMinutes = 60)),
            )
            db.scopeSettingsDao().upsertGroup(
                PodcastGroupSettingsEntity(manualGroup, ScopeOverrides(refreshIntervalMinutes = 0)),
            )
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(manual, ScopeOverrides(refreshIntervalMinutes = 0)),
            )
            val adapter = stubAdapter(onFetch = { _, _ -> AdapterResult.NotModified(null) })
            val q = queue(this)
            val lane = lane(adapter, q)

            q.reschedulePeriodic()
            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertEquals(listOf(timed), adapter.calls.map { it.first })
            assertEquals(NOW + 60 * 60_000L, db.podcastDao().byId(timed)!!.nextRefreshAt)
            assertEquals(RefreshPolicy.NEVER, db.podcastDao().byId(manual)!!.nextRefreshAt)
            assertEquals(NOW + 720 * 60_000L, db.podcastDao().byId(global)!!.nextRefreshAt)

            // Once the remaining timed podcasts switch to Manual only, the minute poll is idle.
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(timed, ScopeOverrides(refreshIntervalMinutes = 0)),
            )
            db.scopeSettingsDao().upsertPodcast(
                PodcastSettingsEntity(global, ScopeOverrides(refreshIntervalMinutes = 0)),
            )
            q.reschedulePeriodic()
            lane.run(Instant.fromEpochMilliseconds(NOW))
            assertEquals(1, adapter.calls.size)

            q.enqueueNow(
                RefreshScope.Podcasts(listOf(manual)),
                force = true,
                pagesOnly = false,
                origin = RefreshOrigin.MANUAL,
            )
            testScheduler.advanceUntilIdle()
            lane.run(Instant.fromEpochMilliseconds(NOW))
            assertEquals(listOf(timed, manual), adapter.calls.map { it.first })
            assertEquals(RefreshPolicy.NEVER, db.podcastDao().byId(manual)!!.nextRefreshAt)
        }

    private suspend fun summaryCount(): Int = if (settings.get(FeedsSettingKeys.LAST_RUN_SUMMARY).isEmpty()) 0 else 1

    /**
     * `enqueueNow` persists forced scopes on Room before it pokes the lane, from its own launched
     * coroutine. With the queries on the test scheduler, `advanceUntilIdle()` covers that write;
     * on a real thread the assertions raced it.
     */
    private fun laneTest(body: suspend TestScope.() -> Unit) =
        runTest {
            db = TestDb.inMemory(clock = clock, queryContext = StandardTestDispatcher(testScheduler))
            try {
                body()
            } finally {
                db.close()
            }
        }

    private fun queue(scope: CoroutineScope): DesktopRefreshScheduler =
        DesktopRefreshScheduler(
            db = db,
            rebaser = NextRefreshRebaser(db, EffectiveSettingsResolverImpl(db, settings)),
            poker = Provider { JobLanePoker { name -> pokes += name } },
            appScope = scope,
        )

    private fun TestScope.lane(
        adapter: StubSourceAdapter,
        queue: DesktopRefreshScheduler,
    ): DesktopRefreshLane =
        DesktopRefreshLane(
            refresher =
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
                            // Ingest prep joins the virtual clock: the paging phase's
                            // cancellation bound would otherwise fire while a commit is parked
                            // on a real worker thread, rolling the page back (engine test's note).
                            defaultDispatcher = StandardTestDispatcher(testScheduler),
                        ),
                ),
            db = db,
            network = network,
            queue = queue,
            clock = clock,
        )

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
        const val DAY = 86_400_000L
    }
}
