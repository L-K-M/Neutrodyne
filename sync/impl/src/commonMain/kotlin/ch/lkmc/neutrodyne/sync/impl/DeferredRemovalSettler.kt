// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 10 Mass-change guard step 4's local settle: the player's own state changes — a stop, or a
 * new loaded episode — apply the DEFERRED removals without waiting for a network round.
 * [SyncStartupDrainInitializer] arms one app-scoped collector once the database is open;
 * each settle is a single writer transaction under `applying = 1` that re-checks every
 * staged clock against the stored state and the pending outbox, so a newer local write
 * still wins. Nothing here pushes, polls, links or logs out — the settled removals surface
 * with the next ordinary round. HELD and STAGED rows never auto-apply.
 */
class DeferredRemovalSettler
    @Inject
    constructor(
        private val db: NeutrodyneDatabase,
        private val playback: PlaybackSyncPort,
        private val clock: Clock,
        private val address: SyncServerAddress,
        private val tokens: SyncTokenStore,
    ) {
        // The initializer and tests call `start` serially, so a plain field guards
        // idempotence: a second arm attempt gets `null` instead of a duplicate collector.
        private var job: Job? = null

        /**
         * Arms the observer once; a later call returns `null`. The returned job belongs to
         * [scope]'s lifetime — the caller cancels and joins it before the database closes.
         * A failed settle keeps the batch and the collector for the next boundary or retry:
         * the write transaction rolls back whole, so nothing reports success while losing
         * removals.
         */
        fun start(scope: CoroutineScope): Job? {
            if (job != null) return null
            return scope
                .launch {
                    // Distinct (episodeId, isPlaying) states only: a repeated identical state
                    // settles nothing new, and a failed settle keeps the batch — and this
                    // collector — for the next boundary or retry.
                    playback.active
                        .map { it.episodeId to it.isPlaying }
                        .distinctUntilChanged()
                        .collect {
                            try {
                                settle()
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Throwable) {
                                // The rolled-back write left the rows DEFERRED: keep collecting.
                            }
                        }
                }.also { job = it }
        }

        /**
         * Applies the DEFERRED rows that still win under the current player state. The link
         * is gated twice: outside the writer, because the URL and token reads are
         * secret/store IO, and inside it, because a copied, re-linked or revoked profile
         * must never run the old profile's staged removals (10 Mass-change guard step 4).
         */
        internal suspend fun settle() {
            val url = address.serverUrl() ?: return
            tokens.load(hostOf(url)) ?: return
            db.withWriteTransaction {
                val state = db.syncStateDao().get() ?: return@withWriteTransaction
                if (!state.enabled ||
                    state.linkedAt == null ||
                    state.accountId == null ||
                    state.serverUrl != url ||
                    state.lastError == SYNC_ERROR_REVOKED
                ) {
                    return@withWriteTransaction
                }
                db.syncStateDao().withApplying {
                    MassChangeGuard.settleDeferred(db, clock.now()) { syncId ->
                        MassChangeGuard.playingRemovalDeferred(db, playback.active.value, syncId)
                    }
                }
            }
        }
    }
