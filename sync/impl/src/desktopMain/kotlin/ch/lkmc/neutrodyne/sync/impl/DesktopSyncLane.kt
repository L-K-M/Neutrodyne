// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import dev.zacsweers.metro.Provider
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Instant

/** The lane name `DesktopJobRunner` orders first and pokes on network regain (11 Runner contract). */
internal const val SYNC_LANE_NAME = "sync"

/**
 * The desktop `sync` lane (10 Scheduling on the desktop). Two ways in:
 *
 * - [poke] — the event-driven path: `SyncScheduler`'s outbox debounce and playback triggers,
 *   the window-focus trigger and "Sync now" land here, record their reason and signal
 *   `JobLanePoker`, so the runner's coalescing and rerun bookkeeping apply;
 * - [run] — the runner's 60-s tick and its resume/regain pokes: no reason is attached, so the
 *   lane infers one from `sync_outbox` — pending push rows round `OUTBOX`, pending
 *   `pos`/`session` rows round `PLAYBACK`, otherwise the 15-min pull decides.
 *
 * Offline costs nothing: a tick or poke returns without touching the outbox, the recorded
 * reason survives to the next run, and the runner re-pokes on regain. Unlinked costs nothing
 * either (R7.1) — and clears the remembered reason so a stale trigger cannot outlive an unlink.
 */
class DesktopSyncLane(
    private val db: NeutrodyneDatabase,
    private val round: suspend (SyncRoundReason) -> SyncReport,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val poker: Provider<JobLanePoker>,
) : JobLane {
    override val name: String = SYNC_LANE_NAME

    /** The cause of the next [run]; first-in wins while the runner coalesces the poke itself. */
    private val pendingReason = AtomicReference<SyncRoundReason?>()

    private var lastRoundMs: Long? = null

    /** An event trigger: remember the reason, then let the runner schedule the run. */
    fun poke(reason: SyncRoundReason) {
        pendingReason.compareAndSet(null, reason)
        poker().poke(name)
    }

    override suspend fun run(now: Instant) {
        if (!network.status.value.isConnected) return
        if (!db.isSyncLinked()) {
            pendingReason.set(null)
            return
        }
        val poked = pendingReason.getAndSet(null)
        val reason =
            poked
                ?: when {
                    db.syncOutboxDao().hasImmediateRows() -> SyncRoundReason.OUTBOX
                    db.syncOutboxDao().hasPlaybackRows() -> SyncRoundReason.PLAYBACK
                    lastRoundOlderThan(PULL_INTERVAL_MS, now) -> SyncRoundReason.PERIODIC
                    else -> return
                }
        round(reason)
        lastRoundMs = clock.now()
    }

    private fun lastRoundOlderThan(
        intervalMs: Long,
        now: Instant,
    ): Boolean {
        val last = lastRoundMs ?: return true
        return now.toEpochMilliseconds() - last >= intervalMs
    }

    private companion object {
        const val PULL_INTERVAL_MS = 15 * 60_000L
    }
}
