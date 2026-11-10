// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AllDevicesReachEffect
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.LinkedRestoreCoordinator

/**
 * A [LinkedRestoreCoordinator] fixture: records every call, scripts the answers. The
 * default answers succeed so a plain linked-restore test need not configure anything.
 */
class FakeLinkedRestoreCoordinator : LinkedRestoreCoordinator {
    val unlinks = mutableListOf<Unit>()
    val completions = mutableListOf<Unit>()
    val previews = mutableListOf<Pair<Set<String>, Set<String>>>()

    var unlinkResult: Outcome<Unit, BackupError> = Outcome.Success(Unit)
    var completeResult: Outcome<Unit, BackupError> = Outcome.Success(Unit)
    var previewResult: Outcome<AllDevicesReachEffect, BackupError> =
        Outcome.Success(AllDevicesReachEffect(podcastsToRemove = 0, groupsToRemove = 0))

    /** The eligibility answer; null is the fail-closed default (no live link). */
    var linkedHost: String? = null

    var linkReadError: BackupError? = null

    override suspend fun actualLinkedHost(): Outcome<String?, BackupError> =
        linkReadError?.let { Outcome.Failure(it) } ?: Outcome.Success(linkedHost)

    override suspend fun unlinkForRestore(): Outcome<Unit, BackupError> {
        unlinks += Unit
        return unlinkResult
    }

    override suspend fun completeAllDevicesRestore(): Outcome<Unit, BackupError> {
        completions += Unit
        return completeResult
    }

    override suspend fun previewAllDevicesReach(
        backupPodcastKeys: Set<String>,
        backupGroupNames: Set<String>,
    ): Outcome<AllDevicesReachEffect, BackupError> {
        previews += backupPodcastKeys to backupGroupNames
        return previewResult
    }
}
