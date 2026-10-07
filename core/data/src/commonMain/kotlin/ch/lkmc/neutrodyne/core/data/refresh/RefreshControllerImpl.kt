// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.model.FeedSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The `RefreshController` of 03 API (`:core:data` internal): user- and system-facing refresh
 * requests map onto `RefreshScheduler` work; `FeedRefresher` does the running.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class RefreshControllerImpl(
    private val scheduler: RefreshScheduler,
    private val refresher: FeedRefresher,
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : RefreshController {
    private val refreshFeedLock = Mutex()
    private val lastRefreshFeedAt = mutableMapOf<FeedSource, Long>()

    override fun refreshNow(scope: RefreshScope) {
        scheduler.enqueueNow(scope, force = true, pagesOnly = false, origin = RefreshOrigin.MANUAL)
    }

    override suspend fun reschedulePeriodic() {
        scheduler.reschedulePeriodic()
    }

    override fun observeStatus(): Flow<RefreshStatus> = refresher.status

    /**
     * Pull-to-refresh (03 Triggers): scope-mapped, forced, `MANUAL`, and the same [FeedSource] is
     * ignored within 20 s. [FeedSource.Ungrouped] resolves to its member set first.
     */
    override fun refreshFeed(source: FeedSource) {
        appScope.launch {
            val scope =
                when (source) {
                    FeedSource.All -> RefreshScope.All
                    is FeedSource.Group -> RefreshScope.Group(source.groupId)
                    is FeedSource.Podcast -> RefreshScope.Podcasts(listOf(source.podcastId))
                    FeedSource.Ungrouped -> RefreshScope.Podcasts(db.podcastDao().ungroupedIds())
                }
            refreshFeedLock.withLock {
                val now = clock.elapsedRealtime()
                val last = lastRefreshFeedAt[source] ?: Long.MIN_VALUE
                if (now - last < REFRESH_FEED_COOLDOWN_MS) return@withLock
                lastRefreshFeedAt[source] = now
            }
            scheduler.enqueueNow(scope, force = true, pagesOnly = false, origin = RefreshOrigin.MANUAL)
        }
    }

    /** "Load older episodes": re-pends the feed (when it has a link) and pages it in a manual run. */
    override fun loadOlderEpisodes(podcastId: Long) {
        appScope.launch {
            db.podcastDao().reopenPaging(podcastId)
            scheduler.enqueueNow(
                RefreshScope.Podcasts(listOf(podcastId)),
                force = false,
                pagesOnly = true,
                origin = RefreshOrigin.MANUAL,
            )
        }
    }

    override fun requestFirstFetch() {
        scheduler.requestFirstFetch()
    }

    private companion object {
        /** The 20 s same-scope cooldown of 03 `refreshFeed`. */
        const val REFRESH_FEED_COOLDOWN_MS = 20_000L
    }
}
