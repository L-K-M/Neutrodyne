// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.app.Application
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

/**
 * The WorkManager half of 10 Scheduling on Android — the [SyncScheduleHooks] `SyncScheduler`
 * calls and the work-name owner for `SyncForegroundObserver` and the start-up initializer:
 *
 * - `sync-now` — `KEEP`, `CONNECTED`, expedited on API ≥ 31 (foreground and "Sync now" run the
 *   same shape; below 31 expedited work would demand a foreground-service notification);
 * - `sync-push` — `KEEP`, `CONNECTED`, 10-s initial delay (backgrounded outbox pushes; `KEEP`
 *   collapses repeats into the one pending request);
 * - `sync-periodic` — 60-min period in an 8-min window, `CONNECTED`, armed while linked.
 *
 * Nothing is enqueued while the link is absent or revoked (R7.1): the foreground observer and
 * `SyncScheduler` gate on [isSyncLinked], `reschedulePeriodic` cancels stale work, and
 * `onUnlinked` cancels every sync work name.
 *
 * `SyncForegroundObserver` and the initializer need the concrete type, not just the hooks.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@ExposeImplBinding
class WorkManagerSyncScheduler
    @Inject
    constructor(
        private val application: Application,
        private val db: NeutrodyneDatabase,
        private val playback: PlaybackSyncPort,
    ) : SyncScheduleHooks {
        private val workManager get() = WorkManager.getInstance(application)

        /** `ProcessLifecycleOwner` at least `STARTED`; `SyncForegroundObserver` flips it. */
        internal val uiVisible = MutableStateFlow(false)

        override fun interactive(): Boolean = uiVisible.value || playback.active.value.isPlaying

        // The reason is informational here: `sync-push` always maps to a push round, whichever
        // trigger deferred to it.
        override fun schedulePush(reason: SyncRoundReason) {
            workManager.enqueueUniqueWork(SyncWorker.WORK_PUSH, ExistingWorkPolicy.KEEP, pushRequest())
        }

        override suspend fun onLinked() = reschedulePeriodic()

        override suspend fun onUnlinked() = cancelAll()

        /** The foreground trigger: a `sync-now` one-time; the caller already checked the link. */
        internal suspend fun enqueueSyncNow() {
            workManager
                .enqueueUniqueWork(SyncWorker.WORK_NOW, ExistingWorkPolicy.KEEP, nowRequest())
                .await()
        }

        /**
         * Startup and link-completion: `sync-periodic` armed while linked, stale sync work
         * cleared otherwise (a crashed unlink may leave names behind).
         */
        internal suspend fun reschedulePeriodic() {
            if (!db.isSyncLinked()) {
                cancelAll()
                return
            }
            workManager
                .enqueueUniquePeriodicWork(
                    SyncWorker.WORK_PERIODIC,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest(),
                ).await()
        }

        /** Cancels every sync work name (unlink, revoked token, unlinked startup). */
        internal suspend fun cancelAll() {
            // Submit all three before awaiting: a running worker cancelling its own name dies
            // mid-await, but the submitted Operations still complete in WorkManager.
            val ops =
                listOf(SyncWorker.WORK_NOW, SyncWorker.WORK_PUSH, SyncWorker.WORK_PERIODIC)
                    .map { workManager.cancelUniqueWork(it) }
            ops.forEach { it.await() }
        }

        /** A `sync-now`-shape one-time: network-only constraints, expedited on API ≥ 31. */
        private fun nowRequest() =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(CONNECTED)
                .setInputData(workDataOf(SyncWorker.KEY_WORK to SyncWorker.WORK_NOW))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }.build()

        /** The `sync-push` one-time: `CONNECTED` plus the 10-s coalescing delay. */
        private fun pushRequest() =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(CONNECTED)
                .setInitialDelay(PUSH_INITIAL_DELAY_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(SyncWorker.KEY_WORK to SyncWorker.WORK_PUSH))
                .build()

        /** `sync-periodic`: one round per hour inside the 8-min window, `CONNECTED`. */
        private fun periodicRequest() =
            PeriodicWorkRequestBuilder<SyncWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
                PERIODIC_FLEX_MINUTES,
                TimeUnit.MINUTES,
            ).setConstraints(CONNECTED)
                .setInputData(workDataOf(SyncWorker.KEY_WORK to SyncWorker.WORK_PERIODIC))
                .build()

        private companion object {
            /** 10 Scheduling on Android: the `sync-push` coalescing delay. */
            const val PUSH_INITIAL_DELAY_MS = 10_000L

            /** 10 Scheduling on Android: the hourly pull and its soft-deadline window. */
            const val PERIODIC_INTERVAL_MINUTES = 60L
            const val PERIODIC_FLEX_MINUTES = 8L

            val CONNECTED: Constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        }
    }
