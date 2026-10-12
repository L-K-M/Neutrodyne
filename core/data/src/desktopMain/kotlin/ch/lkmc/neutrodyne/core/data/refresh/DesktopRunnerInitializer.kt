// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * Starts the desktop job runner at band 200 (11 Start-up: "`DesktopJobRunner.start()` in place of
 * WorkManager scheduling"), then rebases `nextRefreshAt` through
 * `RefreshScheduler.reschedulePeriodic()` — 03 assigns the call to the order-200 start-up
 * initializer "on both platforms". The runner itself waits 5 s before its first tick, after the
 * database open at band 100, so the first frame never competes with catch-up I/O.
 */
@ContributesIntoSet(AppScope::class)
internal class DesktopRunnerInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the runner's lanes reach the database (band 100 opens it).
        private val runner: Provider<DesktopJobRunner>,
        // The concrete scheduler: `RefreshScheduler` stays `:core:data`-internal, so only the
        // `@ExposeImplBinding` type crosses the desktop graph's module boundary.
        private val scheduler: Provider<DesktopRefreshScheduler>,
    ) : AppInitializer {
        override val order: Int = 200

        override suspend fun run() {
            runner().start()
            scheduler().reschedulePeriodic()
        }
    }
