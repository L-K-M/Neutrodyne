// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.data.refresh.DesktopRefreshLane
import ch.lkmc.neutrodyne.core.data.refresh.DesktopRefreshScheduler
import ch.lkmc.neutrodyne.core.data.refresh.NextRefreshRebaser
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 03 Desktop refresh — the `refresh` lane: an offline run is free, queued requests drain in order
 * (forced scopes persisted first), and the automatic selection picks up feeds due inside the
 * 15-minute slack. Runs are driven by the fake scheduler's queue plus the real engine over the
 * scripted adapter.
 */
class DesktopRefreshLaneTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val pokes = mutableListOf<String>()

    @Test
    fun offlineRunDoesNothing() =
        runTest {
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            val adapter = stubAdapter()
            val lane = lane(adapter, queue(this))

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertTrue(adapter.calls.isEmpty())
            assertEquals(0, summaryCount())
        }

    @Test
    fun queuedForcedRequestPersistsDueThenRuns() =
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
            seedPodcast(db, "https://a.example.com/f", nextRefreshAt = NOW + 20 * 60_000L)
            val adapter = stubAdapter()
            val lane = lane(adapter, queue(this))

            lane.run(Instant.fromEpochMilliseconds(NOW))

            assertTrue(adapter.calls.isEmpty())
            assertEquals(0, summaryCount())
        }

    @Test
    fun forceAllInTheQueueMarksEveryHealthyFeedDue() =
        runTest {
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

    private suspend fun summaryCount(): Int = if (settings.get(FeedsSettingKeys.LAST_RUN_SUMMARY).isEmpty()) 0 else 1

    private fun queue(scope: CoroutineScope): DesktopRefreshScheduler =
        DesktopRefreshScheduler(
            db = db,
            rebaser = NextRefreshRebaser(db, settings, clock, scope),
            poker = Provider { JobLanePoker { name -> pokes += name } },
            appScope = scope,
        )

    private fun lane(
        adapter: StubSourceAdapter,
        queue: DesktopRefreshScheduler,
    ): DesktopRefreshLane =
        DesktopRefreshLane(
            refresher = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, settings),
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
