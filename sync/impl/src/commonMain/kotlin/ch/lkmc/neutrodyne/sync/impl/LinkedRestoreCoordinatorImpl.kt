// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.AllDevicesReachEffect
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.LinkedRestoreCoordinator
import ch.lkmc.neutrodyne.sync.api.SyncController
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException

/**
 * The [LinkedRestoreCoordinator] implementation (05 Restore while linked): the restore
 * pipeline in `:core:data` reaches the sync layer through the port, and this binding maps
 * each cooperation onto the existing engine pieces — THIS_DEVICE unlinks through the
 * controller's own unlink (one best-effort push round, logout, token deleted, tables
 * cleared, offline-safe), ALL_DEVICES runs [FirstLinkMerger.restoreEverywhere] and the
 * preview counts come from its staged pull.
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class LinkedRestoreCoordinatorImpl(
    private val db: NeutrodyneDatabase,
    private val controller: SyncController,
    private val merger: FirstLinkMerger,
) : LinkedRestoreCoordinator {
    override suspend fun unlinkForRestore(): Outcome<Unit, BackupError> =
        try {
            when (controller.unlink()) {
                is Outcome.Success -> Outcome.Success(Unit)
                is Outcome.Failure -> Outcome.Failure(BackupError.Io)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // The local unlink below still runs: a restore must never hang on an offline
            // server, and THIS_DEVICE semantics only need the link gone.
            db.syncStateDao().unlink()
            Outcome.Success(Unit)
        }

    override suspend fun completeAllDevicesRestore(): Outcome<Unit, BackupError> =
        when (val result = merger.restoreEverywhere()) {
            is FirstLinkResult.Completed -> Outcome.Success(Unit)
            is FirstLinkResult.Failed -> Outcome.Failure(BackupError.Io)
        }

    override suspend fun previewAllDevicesReach(
        backupPodcastKeys: Set<String>,
        backupGroupNames: Set<String>,
    ): Outcome<AllDevicesReachEffect, BackupError> =
        try {
            val (podcasts, groups) = merger.previewAllDevicesReach(backupPodcastKeys, backupGroupNames)
            Outcome.Success(AllDevicesReachEffect(podcastsToRemove = podcasts, groupsToRemove = groups))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Outcome.Failure(BackupError.Io)
        }
}
