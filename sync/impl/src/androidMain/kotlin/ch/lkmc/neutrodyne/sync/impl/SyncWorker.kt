// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject

/**
 * The one worker behind `sync-now`, `sync-push` and `sync-periodic` (10 Scheduling on Android).
 * Nothing is enqueued while sync is not configured; unlinking cancels every sync work name.
 */
class SyncWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val engine: SyncEngine,
    ) : CoroutineWorker(context, params) {
        /** The `MetroWorkerFactory` entry point; the app's generated graph code calls it. */
        @AssistedFactory
        interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): SyncWorker
        }

        override suspend fun doWork(): Result {
            val reason =
                when (inputData.getString(KEY_WORK)) {
                    WORK_NOW -> SyncRoundReason.SYNC_NOW
                    WORK_PUSH -> SyncRoundReason.OUTBOX
                    else -> SyncRoundReason.PERIODIC
                }
            return try {
                engine.round(reason)
                Result.success()
            } catch (failure: Exception) {
                Result.retry()
            }
        }

        companion object {
            const val WORK_NOW = "sync-now"
            const val WORK_PUSH = "sync-push"
            const val WORK_PERIODIC = "sync-periodic"
            const val KEY_WORK = "work"
        }
    }
