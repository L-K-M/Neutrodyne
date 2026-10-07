// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The desktop `RefreshScheduler` (03 Desktop refresh): forced scopes are persisted with 02's
 * `forceDue` first, the request queues in memory, and `JobLanePoker.poke("refresh")` wakes the
 * lane. A quit before the drain loses only pages-only requests, whose paging state stays "pending"
 * and resumes in the next automatic run's paging phase.
 *
 * [poker] is a `Provider` — the runner holds the lane and the lane holds this scheduler, so the
 * poker edge must resolve lazily.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DesktopRefreshScheduler
    @Inject
    constructor(
        private val db: NeutrodyneDatabase,
        private val rebaser: NextRefreshRebaser,
        private val poker: Provider<JobLanePoker>,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) : RefreshScheduler {
        private val mutex = Mutex()
        private val requests = ArrayDeque<RefreshRequest>()

        override fun enqueueNow(
            scope: RefreshScope,
            force: Boolean,
            pagesOnly: Boolean,
            origin: RefreshOrigin,
        ) {
            appScope.launch {
                if (force) {
                    when (scope) {
                        RefreshScope.All -> db.podcastDao().forceDue(scopeAll = true)
                        is RefreshScope.Group -> db.podcastDao().forceDueGroup(scope.groupId)
                        is RefreshScope.Podcasts -> db.podcastDao().forceDue(scopeAll = false, ids = scope.ids)
                    }
                }
                mutex.withLock { requests.addLast(RefreshRequest(scope, force, pagesOnly, origin)) }
                poker().poke(DesktopRefreshLane.NAME)
            }
        }

        /** Interval and membership changes only rebase `nextRefreshAt` — the lane ticks on its own. */
        override suspend fun reschedulePeriodic() {
            rebaser.rebase()
        }

        /** The desktop runs have no deadline, so there is nothing to continue (03 Desktop refresh). */
        override fun enqueueContinuation() = Unit

        /** Sync-added rows are pending (`nextRefreshAt = now`); the next lane run selects them. */
        override fun requestFirstFetch() {
            appScope.launch { poker().poke(DesktopRefreshLane.NAME) }
        }

        /** The lane drains queued requests in order (03 Desktop refresh step 2). */
        internal suspend fun drain(block: suspend (RefreshRequest) -> Unit) {
            while (true) {
                val request = mutex.withLock { requests.removeFirstOrNull() } ?: return
                block(request)
            }
        }
    }
