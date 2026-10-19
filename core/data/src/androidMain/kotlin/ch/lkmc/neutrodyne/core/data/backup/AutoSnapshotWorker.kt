// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The host behind `backup-auto-snapshot` and `backup-auto-snapshot-now` (05 Snapshot
 * production): one [AutoSnapshotProducer.produce] call per run. Every [SnapshotResult] —
 * written, guarded, disabled or failed — is terminal for the run: the producer already
 * persisted the status (`last_snapshot_error` keeps a failure, the previous file stays), so
 * the daily tick or the next library-change poke is the retry, never a backoff loop on a
 * persistent failure. Only a thrown error reschedules; cancellation propagates.
 */
class AutoSnapshotWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val producer: AutoSnapshotProducer,
    ) : CoroutineWorker(context, params) {
        /** The `MetroWorkerFactory` entry point; `:app` module's generated graph code calls it. */
        @AssistedFactory
        interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): AutoSnapshotWorker
        }

        override suspend fun doWork(): Result {
            suspendRunCatching { producer.produce() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, error) { "auto-snapshot produce failed" }
                    return Result.retry()
                }
            return Result.success()
        }

        private companion object {
            const val TAG = "AutoSnapshotWorker"
        }
    }
