// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * Starts the desktop job runner at band 200 (11 Start-up: "`DesktopJobRunner.start()` in place of
 * WorkManager scheduling"). The runner itself waits 5 s before its first tick, after the database
 * open at band 100, so the first frame never competes with catch-up I/O. The periodic rebase runs
 * through `RefreshController.reschedulePeriodic()` when settings change, not at start-up — the
 * persisted `nextRefreshAt` schedule needs no rescheduling on launch.
 */
@ContributesIntoSet(AppScope::class)
internal class DesktopRunnerInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the runner's lanes reach the database (band 100 opens it).
        private val runner: Provider<DesktopJobRunner>,
    ) : AppInitializer {
        override val order: Int = 200

        override suspend fun run() {
            runner().start()
        }
    }
