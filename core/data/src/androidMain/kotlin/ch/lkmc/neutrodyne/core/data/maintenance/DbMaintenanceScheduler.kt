// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.maintenance

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.TimeUnit

/**
 * Arms the `db-maintenance` unique periodic work (02's work table): 24 h, constraints device
 * idle and battery not low, `UPDATE` so a re-arm never resets the schedule, tag `maintenance`.
 */
@SingleIn(AppScope::class)
internal class DbMaintenanceScheduler
    @Inject
    constructor(
        private val application: Application,
    ) {
        private val workManager get() = WorkManager.getInstance(application)

        internal suspend fun armPeriodic() {
            workManager
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<DbMaintenanceWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiresDeviceIdle(true)
                                .setRequiresBatteryNotLow(true)
                                .build(),
                        ).addTag(TAG_MAINTENANCE)
                        .build(),
                ).await()
        }

        internal companion object {
            const val WORK_NAME = "db-maintenance"
            const val TAG_MAINTENANCE = "maintenance"
            const val PERIOD_HOURS = 24L
        }
    }

/** The order-200 initializer arming [DbMaintenanceScheduler] next to the backup one. */
@ContributesIntoSet(AppScope::class)
internal class DbMaintenanceInitializer
    @Inject
    constructor(
        private val scheduler: Provider<DbMaintenanceScheduler>,
    ) : AppInitializer {
        override val order: Int = 200

        override suspend fun run() {
            suspendRunCatching { scheduler().armPeriodic() }
                .onFailure { Log.e(TAG, it) { "db-maintenance arm failed" } }
        }

        private companion object {
            const val TAG = "DbMaintenanceInit"
        }
    }

/** `DbMaintenanceWorker`'s entry in the map `MetroWorkerFactory` reads (01 Dependency injection). */
@BindingContainer
@ContributesTo(AppScope::class)
object DbMaintenanceWorkerBindings {
    @Provides
    @IntoMap
    @WorkerKey(DbMaintenanceWorker::class)
    fun worker(factory: DbMaintenanceWorker.Factory): WorkerCreator =
        { context, params -> factory.create(context, params) }
}
