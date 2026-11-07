// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 10 Scheduling on Android: the outbox path runs an in-process round this long after the last change. */
internal const val OUTBOX_DEBOUNCE_MS = 2_000L

/** 10 Scheduling: a round at most every 60 s while playing when `pos`/`session` rows are pending. */
internal const val PLAYING_TICK_MS = 60_000L

/** 10 Live updates: a remote `changed` signal runs a round within 500 ms; bursts coalesce. */
internal const val SSE_ROUND_DELAY_MS = 500L

/**
 * The platform half of 10's scheduling tables. `SyncScheduler` (shared) owns the triggers; the
 * platform decides where an outbox push goes and what link/unlink arms.
 */
interface SyncScheduleHooks {
    /**
     * Whether an outbox change may run an in-process round — Android: the UI is at least
     * `STARTED` or playback runs; the desktop is always `false` (its pushes ride
     * `JobLanePoker` through the `sync` lane).
     */
    fun interactive(): Boolean

    /**
     * The deferred push with its would-be reason: `sync-push` work on Android (the reason is
     * informational — the work always maps to a push round); a reasoned `JobLanePoker` poke on
     * the desktop.
     */
    fun schedulePush(reason: SyncRoundReason)

    /** A link completed: arm the platform's periodic mechanism (`sync-periodic`). */
    suspend fun onLinked()

    /** Unlink or a revoked token: cancel every scheduled sync work name (R7.1). */
    suspend fun onUnlinked()
}

/**
 * The shared trigger engine of 10's scheduling tables: watches `sync_outbox` through Room's
 * invalidation tracker, debounces 2 s and runs a round while the platform allows it, defers
 * otherwise; runs playback-boundary and while-playing rounds when `pos`/`session` rows are
 * pending; pokes a round when a held group deletion leaves 05's undo window; and retries the
 * outbox path on network regain. Every path gates on a live link (`enabled`, `linkedAt`, not
 * revoked) and the network; a failed round falls back to [SyncScheduleHooks.schedulePush] so
 * nothing is lost.
 */
class SyncScheduler(
    private val db: NeutrodyneDatabase,
    private val round: suspend (SyncRoundReason) -> Unit,
    private val playback: PlaybackSyncPort,
    private val network: NetworkMonitor,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val hooks: SyncScheduleHooks,
    /** The engine's deferred-removal release (10 Mass-change guard rule 4). */
    private val releaseDeferred: suspend () -> Unit = {},
    /**
     * The events client's link re-check (10 Live updates): link, unlink and revoke all reach
     * the SSE stream's gate through here, so the stream opens and closes promptly instead of
     * reading `sync_state` on a stream cadence.
     */
    private val linkStateChanged: suspend () -> Unit = {},
) {
    /** Serializes this scheduler's rounds; the engine's own mutex covers rounds from other callers. */
    private val roundMutex = Mutex()

    @Volatile
    private var started = false

    /** Serializes the undo-window bookkeeping; the collector and the maturity job can overlap. */
    private val bookkeeping = Mutex()

    /** Group `deleted = true` rows seen: rid → capture wall-ms (`hlc >> 16`), for the undo window. */
    private val groupDeletes = mutableMapOf<String, Long>()
    private var maturityJob: Job? = null
    private var debounceJob: Job? = null

    /**
     * Launches the collectors once (the platform's start-up initializer calls it): the outbox
     * invalidation flow, `PlaybackSyncPort.events`, the while-playing tick and the
     * network-regain re-check. They gate per trigger, so an early start while unlinked costs
     * only the collectors.
     */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            db.invalidationTracker.createFlow(OUTBOX_TABLE).collect { onOutboxChanged() }
        }
        scope.launch {
            playback.events.collect { event ->
                // 10 Mass-change guard rule 4: a removal deferred while playing applies when
                // the player stops or the item changes (a pause keeps the item current).
                when (event) {
                    PlaybackSyncEvent.Stopped, is PlaybackSyncEvent.Transition -> {
                        if (isLinked()) releaseDeferred()
                    }

                    PlaybackSyncEvent.Paused -> {}
                }
                onPlaybackBoundary()
            }
        }
        scope.launch {
            playback.active.collectLatest { active ->
                if (active?.isPlaying != true) return@collectLatest
                while (true) {
                    delay(PLAYING_TICK_MS)
                    onPlaybackTick()
                }
            }
        }
        scope.launch {
            var wasConnected = network.status.value.isConnected
            network.status.collect { status ->
                val connected = status.isConnected
                if (connected && !wasConnected) onOutboxDue()
                wasConnected = connected
            }
        }
    }

    /** A link completed: arm the platform's periodic mechanism and the events stream. */
    suspend fun onLinked() {
        hooks.onLinked()
        linkStateChanged()
    }

    /** Unlink or revoke: drop the in-memory undo bookkeeping and cancel the platform's work. */
    suspend fun onUnlinked() {
        // The stream closes first: it should not read another event while work still cancels.
        linkStateChanged()
        groupDeletes.clear()
        maturityJob?.cancel()
        debounceJob?.cancel()
        remoteRoundJob?.cancel()
        hooks.onUnlinked()
    }

    private var remoteRoundJob: Job? = null

    /**
     * 10 Live updates: a remote `changed` signal runs one `SSE` round inside the 500-ms
     * budget; bursts coalesce onto the pending job, and the round itself pulls whatever
     * changed — the event's cursor is never used.
     */
    internal fun onRemoteChanged() {
        if (remoteRoundJob?.isActive == true) return
        remoteRoundJob =
            scope.launch {
                delay(SSE_ROUND_DELAY_MS)
                remoteRoundJob = null
                roundOrFallback(SyncRoundReason.SSE)
            }
    }

    /** `true` while a captured change may legitimately reach the server (R7.1). */
    internal suspend fun isLinked(): Boolean = db.isSyncLinked()

    /**
     * One outbox-table invalidation: note new group deletions for the undo window, then re-arm
     * the 2-s debounce job. `internal` so tests can drive the trigger without waiting on Room's
     * asynchronous tracker delivery.
     */
    internal suspend fun onOutboxChanged() {
        noteGroupDeletions()
        debounceJob?.cancel()
        debounceJob =
            scope.launch {
                delay(OUTBOX_DEBOUNCE_MS)
                onOutboxDue()
            }
    }

    /** A playback boundary or the network regain path reached due: push the eligible rows. */
    private suspend fun onOutboxDue() {
        if (!db.syncOutboxDao().hasImmediateRows()) return
        roundOrFallback(SyncRoundReason.OUTBOX)
    }

    /**
     * Pause, stop or item transition (`PlaybackSyncPort.events`): interesting only while
     * `pos`/`session` rows wait — any other pending change rides the outbox path anyway (a
     * round without pending rows is a wasted pull, not just a no-op).
     */
    private suspend fun onPlaybackBoundary() {
        if (!db.syncOutboxDao().hasPlaybackRows()) return
        roundOrFallback(SyncRoundReason.PLAYBACK)
    }

    private suspend fun onPlaybackTick() {
        if (!db.syncOutboxDao().hasPlaybackRows()) return
        roundOrFallback(SyncRoundReason.PLAYBACK)
    }

    /**
     * The shared round gate: never while unlinked (R7.1); offline costs no attempt and defers to
     * the platform's push (WorkManager's constraint or the lane's regain poke covers the
     * retry); interactive platforms run the round here, else the deferred push schedules. A
     * failed in-process round defers the same way so the change still reaches the server.
     */
    private suspend fun roundOrFallback(reason: SyncRoundReason) {
        if (!isLinked()) return
        if (!hooks.interactive() || !network.status.value.isConnected) {
            hooks.schedulePush(reason)
            return
        }
        try {
            roundMutex.withLock { round(reason) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            // A 401 already marked the link revoked inside the round: stop every sync trigger
            // the way the worker does (10 Errors) rather than scheduling dead credentials.
            if ((failure as? SyncTransportException)?.status == STATUS_UNAUTHORIZED) {
                onUnlinked()
            } else {
                hooks.schedulePush(reason)
            }
        }
    }

    /**
     * 10 Outbox and coalescing's undo window: the scheduler notes each new group-deletion row's
     * capture time and pokes a round when the oldest skipped row comes of age; after a restart
     * the rows are eligible at once. The `OutboxReader` does the hold-back — this only re-arms
     * the trigger so the delete is pushed when it matures.
     */
    private suspend fun noteGroupDeletions() {
        val rows = db.syncOutboxDao().groupDeletions()
        bookkeeping.withLock {
            val rids = rows.mapTo(HashSet(rows.size)) { it.rid }
            groupDeletes.keys.retainAll(rids)
            rows.forEach { row -> groupDeletes.putIfAbsent(row.rid, row.hlc ushr 16) }
            armMaturity()
        }
    }

    private fun armMaturity() {
        val now = clock.now()
        val next =
            groupDeletes.values
                .map { it + SYNC_UNDO_WINDOW_MS }
                .filter { it > now }
                .minOrNull()
        maturityJob?.cancel()
        maturityJob =
            next?.let {
                scope.launch {
                    delay(it - now)
                    onGroupDeleteMatured()
                }
            }
    }

    private suspend fun onGroupDeleteMatured() {
        bookkeeping.withLock {
            // This coroutine IS the maturity job — clear the slot before re-arming so
            // `armMaturity`'s cancel does not kill the call itself mid-run.
            maturityJob = null
            groupDeletes.entries.removeIf { it.value + SYNC_UNDO_WINDOW_MS <= clock.now() }
            armMaturity()
        }
        onOutboxDue()
    }

    private companion object {
        const val OUTBOX_TABLE = "sync_outbox"
        const val STATUS_UNAUTHORIZED = 401
    }
}

/**
 * R7.1's "never sync while unlinked" predicate: the sync switch is on, the link committed and
 * the token has not been revoked. Every trigger checks this before it schedules or rounds.
 */
internal suspend fun NeutrodyneDatabase.isSyncLinked(): Boolean {
    val state = syncStateDao().get() ?: return false
    return state.enabled && state.linkedAt != null && state.lastError != SYNC_ERROR_REVOKED
}
