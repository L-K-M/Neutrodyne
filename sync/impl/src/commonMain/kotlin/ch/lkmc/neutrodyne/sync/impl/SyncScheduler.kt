// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** 10 Scheduling on Android: the outbox path runs an in-process round this long after the last change. */
internal const val OUTBOX_DEBOUNCE_MS = 2_000L

/** 10 Scheduling: a round at most every 60 s while playing when `pos`/`session` rows are pending. */
internal const val PLAYING_TICK_MS = 60_000L

/**
 * 10 Live updates: remote `changed` events and reconnects coalesce into one round 500 ms
 * after the last signal — a burst of pushes on the server lands as a single pull here.
 */
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
    /**
     * 10 Live updates: every link, unlink and revocation calls this so the events stream can
     * re-evaluate its gates — it is [SyncEventsClient.reevaluate] once the client is wired.
     * A default no-op keeps bare-scheduler tests working.
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
    private var remoteRoundJob: Job? = null

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
            playback.events.collect { onPlaybackBoundary() }
        }
        scope.launch {
            playback.active.collectLatest { active ->
                if (!active.isPlaying) return@collectLatest
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

    /** A link completed: arm the platform's periodic mechanism and re-check the stream. */
    suspend fun onLinked() {
        hooks.onLinked()
        linkStateChanged()
    }

    /** Unlink or revoke: drop the in-memory undo bookkeeping and cancel the platform's work. */
    suspend fun onUnlinked() {
        bookkeeping.withLock { groupDeletes.clear() }
        // The caller can be the debounce or maturity job itself (a revoked in-process
        // round): cancelling the running job would kill the platform's cancel call.
        val self = coroutineContext.job
        debounceJob?.takeIf { it != self }?.cancel()
        maturityJob?.takeIf { it != self }?.cancel()
        remoteRoundJob?.takeIf { it != self }?.cancel()
        hooks.onUnlinked()
        linkStateChanged()
    }

    /**
     * 10 Live updates: the events stream signals here — a remote `changed` or a (re)connect —
     * and one coalesced round runs 500 ms after the last signal. SSE is signaling only; the
     * round is the authoritative pull (`SyncRoundReason.SSE`).
     */
    internal fun onRemoteChanged() {
        remoteRoundJob?.cancel()
        remoteRoundJob =
            scope.launch {
                delay(SSE_ROUND_DELAY_MS)
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
        } catch (failure: SyncTransportException) {
            // A rejected token revoked the link in the engine: cancel the platform's work
            // names like `SyncWorker` does — a deferred push can never land either.
            if (failure.status == STATUS_REVOKED) onUnlinked() else hooks.schedulePush(reason)
        } catch (failure: Throwable) {
            hooks.schedulePush(reason)
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
        const val STATUS_REVOKED = 401
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
