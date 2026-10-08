// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlin.time.Instant

/**
 * The `refresh` lane (03 Desktop refresh): drains the queued requests, then runs the automatic
 * due selection when `dueForRefresh(now + 15 min)` finds anything. No deadline, no continuation —
 * a run ends when the selection is done and its 2-min paging phase is over. The 15-min slack makes
 * feeds come due in batches, so a run posts at most one new-episode notification.
 */
@ContributesIntoSet(AppScope::class)
internal class DesktopRefreshLane
    @Inject
    constructor(
        private val refresher: FeedRefresher,
        private val db: NeutrodyneDatabase,
        private val network: NetworkMonitor,
        private val queue: DesktopRefreshScheduler,
        private val clock: Clock,
    ) : JobLane {
        override val name: String = NAME

        override suspend fun run(now: Instant) {
            // Offline costs nothing: 11's runner pokes the lane again when the network returns.
            if (!network.status.value.isConnected) return

            queue.drain { request ->
                // Desktop runs carry no deadline, so the mutex never times out; the re-queue
                // is wired anyway so a request that did report `reenqueued` keeps its intent.
                if (refresher.run(request.withDesktopBudgets()).reenqueued) {
                    queue.enqueueNow(request.scope, request.force, request.pagesOnly, request.origin)
                }
            }

            // One indexed query over at most a few hundred rows — cheap enough for the minute tick.
            val dueBefore = now.toEpochMilliseconds() + DUE_SLACK_MS
            if (db.podcastDao().dueForRefresh(dueBefore, scopeAll = true).isEmpty()) return
            refresher.run(
                RefreshRequest(
                    scope = RefreshScope.All,
                    force = false,
                    pagesOnly = false,
                    origin = RefreshOrigin.PERIODIC,
                ).withDesktopBudgets(),
            )
        }

        private fun RefreshRequest.withDesktopBudgets(): RefreshRequest =
            copy(
                deadlineElapsedMs = RefreshRequest.NO_DEADLINE,
                dueSlackMs = DUE_SLACK_MS,
                pagingBudgetMs = PAGING_BUDGET_MS,
            )

        internal companion object {
            const val NAME = "refresh"
            const val DUE_SLACK_MS = 15 * 60_000L
            const val PAGING_BUDGET_MS = 2 * 60_000L
        }
    }
