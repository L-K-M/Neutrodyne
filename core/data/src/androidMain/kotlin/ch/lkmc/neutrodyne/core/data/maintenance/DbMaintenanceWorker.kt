// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.maintenance

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.DbMaintenance
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The WorkManager host of the `db-maintenance` unique periodic work (02's worker): one
 * [DbMaintenance.run] per execution under an eight-minute soft deadline — the shared pass
 * checks it between steps and retention batches and simply stops early; the next daily run
 * continues. A thrown error reschedules through WorkManager's backoff; cancellation
 * propagates.
 */
class DbMaintenanceWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val maintenance: DbMaintenance,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        /** The `MetroWorkerFactory` entry point; `:app` module's generated graph code calls it. */
        @AssistedFactory
        interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): DbMaintenanceWorker
        }

        override suspend fun doWork(): Result {
            suspendRunCatching {
                maintenance.run(
                    now = clock.now(),
                    deadlineElapsedMs = clock.elapsedRealtime() + SOFT_DEADLINE_MS,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, error) { "db-maintenance run failed" }
                return Result.retry()
            }
            return Result.success()
        }

        internal companion object {
            /** 02: the soft deadline the worker hands the shared pass. */
            const val SOFT_DEADLINE_MS = 8L * 60_000
            private const val TAG = "DbMaintenanceWorker"
        }
    }
