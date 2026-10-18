// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem

/**
 * The first-launch Android-Backup restore (05 First-launch restore, order 110: after the
 * database open at band 100, before the auto-backup lane at 200). `OpenResult.created` is
 * the only fresh-install signal, and a recovered database counts too: a quarantine leaves
 * the just-created replacement plus whatever `files/backup/auto-snapshot.zip` survived.
 *
 * On a created database an existing snapshot is adopted through
 * [BackupRepository.restoreAndroidBackup] into a real `NEUTRODYNE_BACKUP` session and
 * [BackupRepository.restore] claims the durable intent: MERGE with HISTORY and UP_NEXT,
 * never SETTINGS or REPLACE. Nothing here awaits the restore itself; `RestoreWorker`
 * executes it and band 200's durable-work reconciliation replays an enqueue the claim
 * could not complete, so cancellation or process death cannot strand it. A failed adopt
 * or claim leaves the file for the next launch's existing-database branch to offer.
 *
 * On an existing database the foreign snapshot is only announced: `foreignPending`
 * already drives the status/banner through `observeSnapshotStatus`, and this posts the
 * user-facing offer on notification 3004. Nothing is ever restored silently.
 *
 * Every dependency arrives through `Provider`: the graph builds before band 100 opens the
 * database, so constructors must not reach it; only `run()` dereferences, on the band-110
 * coroutine.
 */
@ContributesIntoSet(AppScope::class)
internal class FirstLaunchRestoreInitializer
    @Inject
    constructor(
        private val opener: Provider<DatabaseOpener>,
        private val backups: Provider<BackupRepository>,
        private val producer: Provider<AutoSnapshotProducer>,
        private val reports: Provider<ImportReportPoster>,
        private val fileSystem: FileSystem,
        @param:Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
    ) : AppInitializer {
        override val order: Int = ORDER

        override suspend fun run() {
            if (!producer().supported) return
            // Band 100 parks this sequence on an Opened state, so the stored result is
            // always readable here; anything else means the open never resolved.
            val opened = opener().openState.value as? DatabaseOpenState.Opened ?: return
            if (opened.result.created) {
                adoptPendingSnapshot()
            } else {
                offerForeignSnapshot()
            }
        }

        private suspend fun adoptPendingSnapshot() {
            val present =
                suspendRunCatching { withContext(io) { fileSystem.exists(producer().snapshotFile) } }
                    .onFailure { Log.w(TAG, it) { "auto-snapshot existence check failed" } }
                    .getOrDefault(false)
            if (!present) return

            val sessionId =
                when (val adopted = backups().restoreAndroidBackup()) {
                    is Outcome.Failure -> {
                        // Nothing was claimed; the file stays and the next launch's
                        // existing-database branch offers it.
                        Log.w(TAG) { "auto-snapshot adoption failed: ${adopted.error}" }
                        return
                    }

                    is Outcome.Success -> {
                        adopted.value
                    }
                }
            when (
                val requested =
                    backups().restore(sessionId, RESTORE_REQUEST)
            ) {
                is Outcome.Success -> Log.i(TAG) { "auto-snapshot restore requested (session $sessionId)" }
                is Outcome.Failure -> Log.w(TAG) { "auto-snapshot restore request failed: ${requested.error}" }
            }
        }

        private suspend fun offerForeignSnapshot() {
            val pending =
                suspendRunCatching { producer().status().foreignPending }
                    .onFailure { Log.w(TAG, it) { "auto-snapshot status probe failed" } }
                    .getOrDefault(false)
            if (!pending) return
            suspendRunCatching { reports().postForeignSnapshotPending() }
                .onFailure { Log.w(TAG, it) { "foreign-snapshot notification failed" } }
        }

        private companion object {
            const val TAG = "FirstLaunchRestore"
            const val ORDER = 110

            /** 05: MERGE, never SETTINGS or REPLACE; auto/fromAndroidBackup come from the session. */
            val RESTORE_REQUEST =
                RestoreRequest(
                    mode = RestoreMode.MERGE,
                    categories = setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT),
                )
        }
    }
