// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CleanupPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No runner or observer is started here. Platform hosts own their lifetime and scheduling. */
internal class CleanupPlannerImpl(
    private val db: NeutrodyneDatabase,
    private val resolver: EffectiveSettingsResolver,
    private val clock: Clock,
    private val files: DownloadControllerFiles,
    private val deletes: DeferredDeletes,
    private val index: LocalMediaIndexStore,
    private val reconciler: DownloadReconciler,
    private val claimMutex: Mutex,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CleanupPlanner {
    private val mutex = Mutex()
    private val dao = db.downloadDao()

    override suspend fun run(reason: CleanupReason) =
        withContext(io) {
            mutex.withLock {
                val policies = resolver.observeAutoDownload().first()
                val now = clock.now()
                claimMutex.withLock {
                    // Also drains journal entries left by an outgoing playing episode or a crash.
                    files.finishRemoval(removeEligible(policies, now))
                }
                // Daily/startup hosts already reconcile completed rows. This is only steps 4–5,
                // and must not reset live runners or classify an unavailable root as a deletion.
                if (reason in SWEEP_REASONS) reconciler.sweepFiles()
            }
        }

    /** W5/W7 launch this in their application scope; IMMEDIATELY still coalesces played writes. */
    @OptIn(FlowPreview::class)
    suspend fun observePlayed() {
        dao.observePlayedCompletedIds().distinctUntilChanged().debounce(PLAYED_DEBOUNCE_MS).collect {
            run(CleanupReason.PLAYED)
        }
    }

    private suspend fun eligibleIds(
        policies: Map<Long, EffectiveAutoDownload>,
        now: Long,
    ): List<Long> {
        val played =
            dao
                .cleanupCandidates(UP_NEXT_PROTECTED)
                .filter { row ->
                    when (policies[row.podcastId]?.deleteAfterPlayed) {
                        DeleteAfter.IMMEDIATELY -> row.playedAt <= now
                        DeleteAfter.AFTER_24H -> row.playedAt <= now - PLAYED_GRACE_MS
                        DeleteAfter.NEVER, null -> false
                    }
                }.map { it.episodeId }
        val enabled = policies.filterValues { it.enabled }
        val rolling =
            dao
                .unplayedAutoCompleted(enabled.keys.toList(), UP_NEXT_PROTECTED)
                .groupBy { it.podcastId }
                .flatMap { (podcastId, rows) ->
                    // Drop before filtering: protected occupants count in their window slot.
                    rows.drop(checkNotNull(enabled[podcastId]).keepLatest).filterNot { it.protected }
                }.map { it.episodeId }
        return played + rolling
    }

    /** Journal before the transaction; recheck protections in the same transaction as deletion.
     * claimMutex prevents claims/moves from reusing these paths until the journal is drained. */
    private suspend fun removeEligible(
        policies: Map<Long, EffectiveAutoDownload>,
        now: Long,
    ): List<DownloadEntity> {
        val rows =
            eligibleIds(policies, now)
                .mapNotNull { dao.byEpisode(it) }
                .filter { it.state == DownloadState.COMPLETED && it.runnerToken == null }
        if (rows.isEmpty()) return emptyList()

        deletes.journal(rows)
        val removed = mutableListOf<DownloadEntity>()
        try {
            for (row in rows) index.remove(row.episodeId)
            db.withWriteTransaction {
                val eligible = eligibleIds(policies, now).toSet()
                for (row in rows) {
                    if (row.episodeId !in eligible || dao.byEpisode(row.episodeId) != row) continue
                    if (dao.removeUnclaimed(listOf(row.episodeId)) == 1) removed += row
                    // Cleanup intentionally leaves downloadDismissedAt untouched.
                }
            }
        } finally {
            // Restore rejected rows and rolled-back deletes. Index events may suspend, so keep
            // them outside the DB transaction where a consumer might need the writer connection.
            withContext(NonCancellable) {
                for (row in rows) {
                    val live = dao.byEpisode(row.episodeId) ?: continue
                    if (live.state == DownloadState.COMPLETED) live.finalUri?.let { index.put(live.episodeId, it) }
                }
            }
        }
        return removed
    }

    private companion object {
        const val UP_NEXT_PROTECTED = 3
        const val PLAYED_GRACE_MS = 24L * 60 * 60 * 1000
        const val PLAYED_DEBOUNCE_MS = 30_000L
        val SWEEP_REASONS = setOf(CleanupReason.STARTUP, CleanupReason.DAILY, CleanupReason.STORAGE)
    }
}
