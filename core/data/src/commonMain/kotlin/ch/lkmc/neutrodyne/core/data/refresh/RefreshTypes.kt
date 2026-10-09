// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.NewEpisodes

/*
 * The refresh-scheduling types of 03 "API" and "Engine run" (`:core:data` commonMain, internal).
 * `RefreshControllerImpl` delegates work requests to the platform `RefreshScheduler`; the engine
 * state machine is `FeedRefresher`.
 */

/** Why a run exists; append-only (03 API). */
internal enum class RefreshOrigin {
    PERIODIC,
    MANUAL,
    FOREGROUND,
    SUBSCRIBE,
    CONTINUATION,
    IMPORT,
    RESTORE,
    SYNC,

    /** User-initiated retry of a blocked feed: user-driven like MANUAL, plus a scoped block clear. */
    RETRY,
}

/**
 * One engine run (03 API). [deadlineElapsedMs] is a monotonic `Clock.elapsedRealtime` deadline:
 * Android's 8-min soft deadline, `NO_DEADLINE` on the desktop.
 */
internal data class RefreshRequest(
    val scope: RefreshScope,
    val force: Boolean,
    val pagesOnly: Boolean,
    val origin: RefreshOrigin,
    val deadlineElapsedMs: Long = NO_DEADLINE,
    val dueSlackMs: Long = 0,
    val pagingBudgetMs: Long = 0,
) {
    internal companion object {
        /** The desktop's (and tests') no-deadline marker (03 API). */
        const val NO_DEADLINE = Long.MAX_VALUE
    }
}

/** The engine's per-feed result (03 API); drives [FeedRunEvent] and 05's import statuses. */
internal sealed interface FeedOutcome {
    data class Ingested(
        val inserted: Int,
        val newCount: Int,
        val firstIngest: Boolean,
        /** A subscribed podcast with the same real `podcastGuid` (03 dedupe note). */
        val sameGuidAs: Long?,
    ) : FeedOutcome

    data object NotModified : FeedOutcome

    data object Unchanged : FeedOutcome

    data class Merged(
        val intoPodcastId: Long,
    ) : FeedOutcome

    data class Failed(
        val kind: FeedErrorKind,
        val httpStatus: Int?,
    ) : FeedOutcome

    /** 04 only: not attempted; only `nextRefreshAt` moves (03 API). */
    data class Deferred(
        val untilMs: Long,
    ) : FeedOutcome
}

/** What one run produced (03 API): per-feed outcomes, committed new episodes, leftovers. */
internal data class RefreshReport(
    val outcomes: Map<Long, FeedOutcome>,
    val newEpisodes: List<NewEpisodes>,
    val remaining: Int,
    val stoppedByDeadline: Boolean,
    /**
     * The run never owned the engine mutex and carries intent a continuation cannot express
     * (r4 F3): the caller re-enqueues the same request through its platform scheduler with
     * scope/force/pagesOnly/origin intact, and must not chain a continuation on top of it.
     */
    val reenqueued: Boolean = false,
)

/** One per-feed outcome emission (03 API); 05's import runner collects these. */
internal data class FeedRunEvent(
    val podcastId: Long,
    val origin: RefreshOrigin,
    val outcome: FeedOutcome,
)

/**
 * The platform work-request port of 03 API. `WorkManagerRefreshScheduler` (Android) and
 * `DesktopRefreshScheduler` (desktop) implement it; `RefreshControllerImpl` only calls it.
 */
internal interface RefreshScheduler {
    /** A `refresh-now` run of [scope] (Android); on the desktop a lane poke with a queued request. */
    fun enqueueNow(
        scope: RefreshScope,
        force: Boolean,
        pagesOnly: Boolean,
        origin: RefreshOrigin,
    )

    /** Android: tick + constraints + rebase; desktop: `NextRefreshRebaser.rebase()` only. */
    suspend fun reschedulePeriodic()

    /**
     * Android: `refresh-continuation` with `KEEP`; a no-op on the desktop (no soft deadline).
     * Suspending so the worker can await the enqueued `Operation` before it reports success
     * (03 Worker): returns false when the enqueue could not be confirmed.
     */
    suspend fun enqueueContinuation(): Boolean

    /** MS2's first fetch of sync-added podcasts: Android `import-sync`, desktop lane poke. */
    fun requestFirstFetch()
}
