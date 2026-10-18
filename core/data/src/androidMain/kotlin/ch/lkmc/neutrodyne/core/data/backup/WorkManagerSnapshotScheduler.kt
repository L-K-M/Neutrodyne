// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import com.google.common.util.concurrent.ListenableFuture
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit

/**
 * The Android `SnapshotScheduler` (05 Snapshot production): `backup-auto-snapshot-now`, the
 * 10-minute debounced one-shot under `REPLACE` (only the latest poke survives), battery-not-low
 * constrained; and `backup-auto-snapshot`, the daily periodic the order-200 initializer arms
 * under `UPDATE` with the idle+charging constraint set. [cancelScheduled] is one-way and best
 * effort — the persisted `backup.auto_snapshot_enabled` flag and the worker's enabled gate
 * carry the durable half.
 */
@SingleIn(AppScope::class)
internal class WorkManagerSnapshotScheduler
    @Inject
    constructor(
        private val application: Application,
    ) : SnapshotScheduler {
        private val workManager get() = WorkManager.getInstance(application)

        override suspend fun requestSoon() {
            suspendRunCatching {
                workManager
                    .enqueueUniqueWork(
                        RestoreWorkData.WORK_SNAPSHOT_NOW,
                        ExistingWorkPolicy.REPLACE,
                        soonRequest(),
                    ).await()
            }.onFailure { Log.w(TAG, it) { "snapshot poke enqueue failed" } }
        }

        override suspend fun cancelScheduled() {
            suspendRunCatching {
                workManager.cancelUniqueWork(RestoreWorkData.WORK_SNAPSHOT).await()
            }.onFailure { Log.w(TAG, it) { "snapshot periodic cancel failed" } }
            suspendRunCatching {
                workManager.cancelUniqueWork(RestoreWorkData.WORK_SNAPSHOT_NOW).await()
            }.onFailure { Log.w(TAG, it) { "snapshot debounce cancel failed" } }
        }

        /**
         * `AutoBackupInitializer`'s periodic arm (05's work table): `UPDATE` — never `REPLACE`,
         * so an armed entry's schedule survives the enqueue.
         */
        internal suspend fun armPeriodic() {
            workManager
                .enqueueUniquePeriodicWork(
                    RestoreWorkData.WORK_SNAPSHOT,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest(),
                ).await()
        }

        /** The initializer's enabled reconciliation: drop a debounced poke that cannot produce. */
        internal suspend fun cancelSoon() {
            workManager.cancelUniqueWork(RestoreWorkData.WORK_SNAPSHOT_NOW).await()
        }

        /** Any unfinished `backup-auto-snapshot-now` — the test seam for the debounce. */
        internal suspend fun soonWorkLive(): Boolean =
            workManager
                .getWorkInfosForUniqueWork(RestoreWorkData.WORK_SNAPSHOT_NOW)
                .awaitResult()
                .any { !it.state.isFinished }

        private fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<AutoSnapshotWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresDeviceIdle(true)
                        .setRequiresCharging(true)
                        .build(),
                ).addTag(RestoreWorkData.TAG_BACKUP)
                .build()

        private fun soonRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AutoSnapshotWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(SOON_DELAY_MS, TimeUnit.MILLISECONDS)
                .addTag(RestoreWorkData.TAG_BACKUP)
                .build()

        private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
            suspendCancellableCoroutine { continuation ->
                addListener({ continuation.resumeWith(runCatching { get() }) }, { it.run() })
                continuation.invokeOnCancellation { cancel(true) }
            }

        internal companion object {
            const val PERIODIC_HOURS = 24L
            const val SOON_DELAY_MS = 10L * 60_000
            private const val TAG = "SnapshotScheduler"
        }
    }
