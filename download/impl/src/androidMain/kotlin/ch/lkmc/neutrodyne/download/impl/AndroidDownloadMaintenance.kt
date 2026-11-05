// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Full passes serialize with each other; lane drains only need the common runner reset barrier. */
internal class AndroidDownloadMaintenance(
    private val reconcile: suspend () -> Unit,
    private val scheduler: DownloadScheduler,
    private val planner: AutoDownloadPlanner,
    private val cleanup: CleanupPlanner,
    private val resolver: EffectiveSettingsResolver,
    private val device: SettingStore,
    private val clock: Clock,
    private val move: suspend (String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun startup() =
        mutex.withLock {
            reconcile()
            for (lane in DownloadLane.entries) scheduler.ensureScheduled(lane)
            planEnabled()
            cleanup.run(CleanupReason.STARTUP)
            resumeMove()
        }

    suspend fun daily() =
        mutex.withLock {
            reconcile()
            cleanup.run(CleanupReason.DAILY)
            planEnabled()
            for (lane in DownloadLane.entries) scheduler.ensureScheduled(lane)
            resumeMove()
            // A failed pass must remain eligible for WorkManager's retry.
            device.set(DownloadSettingKeys.CLEANUP_LAST_AT, clock.now())
        }

    private suspend fun planEnabled() {
        planner.plan(
            resolver
                .observeAutoDownload()
                .first()
                .filterValues { it.enabled }
                .keys,
        )
    }

    private suspend fun resumeMove() {
        val target = device.get(DownloadSettingKeys.MOVE_TARGET)
        if (target.isNotEmpty()) move(target)
    }
}
