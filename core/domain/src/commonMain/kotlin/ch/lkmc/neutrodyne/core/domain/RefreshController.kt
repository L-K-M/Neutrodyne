// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.FeedSource
import kotlinx.coroutines.flow.Flow

/**
 * The user- and system-facing refresh API (03 Refresh scheduling). Implemented by
 * `RefreshControllerImpl` (`:core:data`), which delegates work requests to the platform
 * `RefreshScheduler` and exposes `FeedRefresher.status`.
 */
interface RefreshController {
    /** Always forced and user-initiated: enqueues a `refresh-now` run of [scope]. */
    fun refreshNow(scope: RefreshScope)

    /** Re-evaluates the periodic tick and rebases `nextRefreshAt` (interval or membership change). */
    suspend fun reschedulePeriodic()

    /** The running run's progress for progress UI and tests. */
    fun observeStatus(): Flow<RefreshStatus>

    /**
     * Pull-to-refresh of a feed source: `All → All`, `Group → Group`, `Podcast → Podcasts([id])`,
     * `Ungrouped → Podcasts(ids of podcasts in no group)`; the same scope is ignored within 20 s.
     */
    fun refreshFeed(source: FeedSource)

    /** Starts an older-pages session for [podcastId] (`pagingComplete = 0` needs `pagingNextUrl`). */
    fun loadOlderEpisodes(podcastId: Long)

    /**
     * MS2: 10's `SyncScheduler` after a pulled page added podcasts (pending, `initialFetch = 1`) —
     * Android's `import-sync` work, a lane poke on the desktop.
     */
    fun requestFirstFetch()
}

/** The scope a refresh run covers (03 API). */
sealed interface RefreshScope {
    data object All : RefreshScope

    data class Group(
        val groupId: Long,
    ) : RefreshScope

    data class Podcasts(
        val ids: List<Long>,
    ) : RefreshScope
}

/** The engine's progress snapshot (03 API). */
data class RefreshStatus(
    val running: Boolean,
    val scope: RefreshScope?,
    val done: Int,
    val total: Int,
    val lastRunFinishedAt: Long?,
)
