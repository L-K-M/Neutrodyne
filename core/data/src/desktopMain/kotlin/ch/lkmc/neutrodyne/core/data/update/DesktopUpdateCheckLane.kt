// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlin.time.Instant

@ContributesIntoSet(AppScope::class)
@Inject
internal class DesktopUpdateCheckLane(
    private val executor: Provider<UpdateCheckExecutor>,
    private val network: NetworkMonitor,
) : JobLane {
    override val name: String = UPDATE_PERIODIC_WORK

    override suspend fun run(now: Instant) {
        if (!network.status.value.isConnected) return

        // The coordinator owns persisted due times and expected-error backoff. A wake poke
        // never bypasses those gates, and does not alter other lanes' generic backoff.
        executor().execute(UpdateInvocation.SCHEDULED)
    }
}
