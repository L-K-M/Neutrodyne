// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class LaneUpdateCheckRunner(
    private val executor: Provider<UpdateCheckExecutor>,
    private val poker: Provider<JobLanePoker>,
) : UpdateCheckRunner {
    override suspend fun setAutomaticChecks(mode: UpdateScheduling) {
        if (mode == UpdateScheduling.ENABLED) poker().poke(UPDATE_PERIODIC_WORK)
    }

    // The coordinator launches manual work in the application scope, independent of lane ticks.
    override suspend fun runNow(): UpdateCheckState = executor().execute(UpdateInvocation.MANUAL)

    // No separate platform request exists; the coordinator cancels its own in-flight coroutine.
    override suspend fun cancelManual() = Unit
}
