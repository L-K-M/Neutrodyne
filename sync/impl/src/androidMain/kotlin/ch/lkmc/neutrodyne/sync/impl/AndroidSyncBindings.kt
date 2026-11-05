// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android sync bindings (10 Scheduling on Android, 01 Dependency injection). */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidSyncBindings {
    /** `SyncWorker`'s entry in the map `MetroWorkerFactory` reads. */
    @Provides
    @IntoMap
    @WorkerKey(SyncWorker::class)
    fun syncWorker(factory: SyncWorker.Factory): WorkerCreator =
        { context: Context, params: WorkerParameters -> factory.create(context, params) }
}

/**
 * The sync-scheduling start-up initializer (01 Application start-up, band 240 — after the
 * database open at 100 and the refresh schedulers at 200–220): reconciles `sync-periodic` with
 * the link state, starts `SyncScheduler`'s collectors, then registers the lifecycle observer.
 * The observer goes last so `ProcessLifecycleOwner`'s replayed `ON_START` sees an armed
 * scheduler; a late registration still replays the current state.
 */
@ContributesIntoSet(AppScope::class)
class SyncSchedulingInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): every dependency reaches the database, which band 100 opens first.
        private val workScheduler: Provider<WorkManagerSyncScheduler>,
        private val scheduler: Provider<SyncScheduler>,
        private val observer: Provider<SyncForegroundObserver>,
    ) : AppInitializer {
        override val order: Int = 240

        override suspend fun run() {
            workScheduler().reschedulePeriodic()
            scheduler().start()
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer())
            }
        }
    }
