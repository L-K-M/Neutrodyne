// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.lifecycle.ProcessLifecycleOwner
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The `refresh-periodic` start-up initializer (01 Application start-up, band 200): enqueues or
 * cancels the periodic tick to match the settings and rebases `nextRefreshAt`. Runs after the
 * database open at band 100; the scheduler's suspend call blocks this initializer's coroutine, not
 * the main thread.
 */
@ContributesIntoSet(AppScope::class)
internal class PeriodicRefreshInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the scheduler reaches the database, which band 100 opens first.
        private val scheduler: Provider<RefreshScheduler>,
    ) : AppInitializer {
        override val order: Int = 200

        override suspend fun run() {
            scheduler().reschedulePeriodic()
        }
    }

/**
 * Registers the app-foreground refresh trigger (03 Triggers; order 220, after the periodic
 * enqueue). `ProcessLifecycleOwner` replays the current state, so a late registration still sees
 * an in-progress `ON_START` — the observer's own 10-min cooldown keeps that replay cheap.
 */
@ContributesIntoSet(AppScope::class)
internal class RefreshForegroundObserverInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the observer's scheduler reaches the database (band 100 opens it).
        private val observer: Provider<RefreshForegroundObserver>,
    ) : AppInitializer {
        override val order: Int = 220

        override suspend fun run() {
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer())
            }
        }
    }
