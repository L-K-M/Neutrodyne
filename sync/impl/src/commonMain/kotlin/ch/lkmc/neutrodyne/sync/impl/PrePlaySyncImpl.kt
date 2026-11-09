// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PrePlaySync
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 10 Episode-state rules rule 4 ("pull before play"): a visible-UI start first gives the engine
 * at most [BUDGET_MS] to land newer remote state, so `StartPositionRule` does not plan from a
 * stale local position and overwrite it.
 *
 * Budget ownership: [BUDGET_MS] covers the whole call — the gate's storage read, the coalescing
 * mutex and the join on the round — so a stalled read or a held slot can never stretch the UI
 * wait. The offline check runs before any storage access: it is the cheapest gate and costs no
 * database touch. Unlinked or revoked state and a round that finished less than [RECENT_MS] ago
 * all return inside the same budget.
 *
 * Round ownership: the app scope owns the spawned round — concurrent UI waits coalesce onto the
 * one in-flight slot, a timeout returns while that round keeps running, and the caller's own
 * cancellation propagates without reaching the spawn (a cancelled start must not play). [gate]
 * guards only the slot reference; it is never held across the round's network, Room or player
 * work. A failed round is swallowed — a play must never block on sync — while a revocation the
 * engine itself persists stays persisted.
 */
class PrePlaySyncImpl(
    private val openDb: suspend () -> NeutrodyneDatabase,
    private val round: suspend (SyncRoundReason) -> Unit,
    private val network: NetworkMonitor,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PrePlaySync {
    private val gate = Mutex()
    private var inflight: Deferred<Unit>? = null

    override suspend fun beforeStart() {
        // Offline is the cheapest gate: it never touches the database.
        if (!network.status.value.isConnected) return
        try {
            withTimeoutOrNull(BUDGET_MS) {
                if (!needsPull()) return@withTimeoutOrNull
                inflight().join()
            }
        } catch (cancelled: CancellationException) {
            // External cancellation cancels this wait only; the app-scoped round keeps running.
            throw cancelled
        }
    }

    /**
     * The storage gates, read inside the caller's budget. The resolver suspends (never blocks a
     * thread) so a still-opening database stays inside the budget, and an unavailable or failing
     * read counts as "not linked" — a play must not wait on a database that cannot answer.
     */
    private suspend fun needsPull(): Boolean {
        val state =
            try {
                openDb().syncStateDao().get()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return false
        if (!state.enabled || state.linkedAt == null || state.lastError == SYNC_ERROR_REVOKED) return false
        val last = state.lastSyncAt ?: return true
        val age = clock.now() - last
        // A future lastSyncAt is not "a round finished recently" — a clock rollback pulls anyway.
        return age < 0 || age >= RECENT_MS
    }

    /** One app-scoped round at a time: a second UI wait joins the same in-flight round. */
    private suspend fun inflight(): Deferred<Unit> =
        gate.withLock {
            inflight
                ?.takeIf { it.isActive }
                ?: scope
                    .async {
                        try {
                            round(SyncRoundReason.PRE_PLAY)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                        }
                    }.also { inflight = it }
        }

    private companion object {
        /** 10's documented pull-before-play budget. */
        const val BUDGET_MS = 1_500L

        /** A completed round younger than this counts as fresh enough. */
        const val RECENT_MS = 15_000L
    }
}
