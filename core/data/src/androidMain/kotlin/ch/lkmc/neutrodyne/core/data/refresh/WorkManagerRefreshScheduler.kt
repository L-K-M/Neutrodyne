// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import android.app.Application
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The Android `RefreshScheduler` (03 Work requests): `refresh-now` one-times for user-driven runs
 * (expedited on API ≥ 31 — below, expedited work would demand a foreground-service notification the
 * refresh deliberately avoids), `refresh-auto` for automatic one-shots on their own constrained
 * chain, `refresh-continuation` with `KEEP` for deadline leftovers, `import-sync` for sync-added
 * libraries, and the `refresh-periodic` tick whose interval and constraint set are stored in
 * `feeds.scheduled_tick_*` so re-enqueues only happen on a change.
 *
 * M1a's tick is `max(60, feeds.refresh_interval_minutes)` minutes; `0` ("Manual only") cancels the
 * periodic work. 05's effective-settings resolver replaces the global read (03 Periodic tick).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class WorkManagerRefreshScheduler
    @Inject
    constructor(
        private val application: Application,
        private val db: NeutrodyneDatabase,
        private val settings: SettingsRepository,
        private val rebaser: NextRefreshRebaser,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) : RefreshScheduler {
        private val workManager get() = WorkManager.getInstance(application)

        override fun enqueueNow(
            scope: RefreshScope,
            force: Boolean,
            pagesOnly: Boolean,
            origin: RefreshOrigin,
        ) {
            // Manual and automatic one-shots ride separate unique chains: a constrained
            // automatic request (battery low, wifi only) must never sit in front of a manual
            // pull-to-refresh, and a queued manual request is not replaced by automatic work.
            val manual = origin == RefreshOrigin.MANUAL
            val uniqueName = if (manual) WORK_NOW else WORK_AUTO
            if (scope is RefreshScope.Podcasts && scope.ids.size > RefreshWorkData.MAX_SCOPE_IDS) {
                // `Data` caps at 10 KB: persist the due marks, then send a plain All run
                // (03 Work requests). The enqueue rides this coroutine to keep the order.
                appScope.launch {
                    db.podcastDao().forceDue(scopeAll = false, ids = scope.ids)
                    enqueueUnique(
                        uniqueName,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        requestFor(RefreshScope.All, force = false, pagesOnly, origin),
                    )
                }
                return
            }
            if (manual) {
                enqueueUnique(
                    WORK_NOW,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    nowRequest(scope, force, pagesOnly, origin),
                )
            } else {
                appScope.launch {
                    enqueueUnique(
                        WORK_AUTO,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        automaticRequest(scope, force, pagesOnly, origin),
                    )
                }
            }
        }

        override suspend fun reschedulePeriodic() {
            val intervalMinutes = settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES).toLong()
            val wifiOnly = settings.get(FeedsSettingKeys.REFRESH_WIFI_ONLY)
            val tick = if (intervalMinutes == 0L) NO_TICK else maxOf(MIN_TICK_MINUTES, intervalMinutes)
            val unmetered = wifiOnly

            val lastTick = settings.get(FeedsSettingKeys.SCHEDULED_TICK_MINUTES)
            val lastUnmetered = settings.get(FeedsSettingKeys.SCHEDULED_TICK_UNMETERED)
            // The persisted markers and WorkManager's actual unique work are reconciled here:
            // a marker without live work (a failed enqueue, a wiped store) re-enqueues, and the
            // markers are only written after the Operation reports success.
            val live = periodicWorkLive()
            if (tick == NO_TICK) {
                if (lastTick != NO_TICK || live) {
                    if (workManager.cancelUniqueWork(WORK_PERIODIC).awaitSuccess()) {
                        settings.set(FeedsSettingKeys.SCHEDULED_TICK_MINUTES, NO_TICK)
                        settings.set(FeedsSettingKeys.SCHEDULED_TICK_UNMETERED, unmetered)
                    }
                }
            } else if (tick != lastTick || unmetered != lastUnmetered || !live) {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                val request =
                    PeriodicWorkRequestBuilder<RefreshWorker>(
                        tick,
                        TimeUnit.MINUTES,
                        tick / FLEX_DIVISOR,
                        TimeUnit.MINUTES,
                    ).setConstraints(constraints)
                        .setInputData(
                            RefreshWorkData.of(
                                RefreshScope.All,
                                force = false,
                                pagesOnly = false,
                                RefreshOrigin.PERIODIC,
                            ),
                        ).addTag(TAG_REFRESH)
                        .build()
                if (
                    workManager
                        .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
                        .awaitSuccess()
                ) {
                    settings.set(FeedsSettingKeys.SCHEDULED_TICK_MINUTES, tick)
                    settings.set(FeedsSettingKeys.SCHEDULED_TICK_UNMETERED, unmetered)
                }
            }

            // Interval changes take effect without waiting for the old schedule (03 Periodic tick).
            rebaser.rebase()
        }

        override suspend fun enqueueContinuation(): Boolean {
            val unmetered = settings.get(FeedsSettingKeys.REFRESH_WIFI_ONLY)
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(CONTINUATION_INITIAL_DELAY_MS, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, CONTINUATION_BACKOFF_MS, TimeUnit.MILLISECONDS)
                    .setInputData(
                        RefreshWorkData.of(
                            RefreshScope.All,
                            force = false,
                            pagesOnly = false,
                            RefreshOrigin.CONTINUATION,
                        ),
                    ).addTag(TAG_REFRESH)
                    .build()
            // The worker awaits the Operation before reporting success: an unconfirmed enqueue
            // would strand the run's leftovers with nothing scheduled (03 Worker).
            return workManager
                .enqueueUniqueWork(WORK_CONTINUATION, ExistingWorkPolicy.KEEP, request)
                .awaitSuccess()
        }

        override fun requestFirstFetch() {
            enqueueUnique(
                WORK_IMPORT_SYNC,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                nowRequest(RefreshScope.All, false, false, RefreshOrigin.SYNC),
            )
        }

        private fun enqueueUnique(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            workManager.enqueueUniqueWork(name, policy, request)
        }

        /**
         * The one-shot request shape by origin (03 Work requests): `MANUAL` keeps the expedited,
         * connected-only user-driven request; automatic triggers ride the periodic constraint
         * set — the `feeds.refresh_wifi_only` network type plus battery-not-low, never expedited.
         */
        private suspend fun requestFor(
            scope: RefreshScope,
            force: Boolean,
            pagesOnly: Boolean,
            origin: RefreshOrigin,
        ): OneTimeWorkRequest =
            if (origin == RefreshOrigin.MANUAL) {
                nowRequest(scope, force, pagesOnly, origin)
            } else {
                automaticRequest(scope, force, pagesOnly, origin)
            }

        /** A `refresh-now`-shape one-time: network-only constraints, expedited on API ≥ 31. */
        private fun nowRequest(
            scope: RefreshScope,
            force: Boolean,
            pagesOnly: Boolean,
            origin: RefreshOrigin,
        ): OneTimeWorkRequest {
            val builder =
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(RefreshWorkData.of(scope, force, pagesOnly, origin))
                    .addTag(TAG_REFRESH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            return builder.build()
        }

        /** The `refresh-auto` one-shot: the wifi-only-aware + battery-not-low set, non-expedited. */
        private suspend fun automaticRequest(
            scope: RefreshScope,
            force: Boolean,
            pagesOnly: Boolean,
            origin: RefreshOrigin,
        ): OneTimeWorkRequest {
            val unmetered = settings.get(FeedsSettingKeys.REFRESH_WIFI_ONLY)
            return OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).setInputData(RefreshWorkData.of(scope, force, pagesOnly, origin))
                .addTag(TAG_REFRESH)
                .build()
        }

        /** Any unfinished `refresh-periodic` — ENQUEUED or RUNNING — exists in WorkManager. */
        private suspend fun periodicWorkLive(): Boolean =
            suspendRunCatching {
                workManager
                    .getWorkInfosForUniqueWork(WORK_PERIODIC)
                    .get()
                    .any { !it.state.isFinished }
            }.getOrDefault(false)

        /** The `Operation` awaited; `await()` returns `SUCCESS` or throws — a failed op must not set markers. */
        private suspend fun Operation.awaitSuccess(): Boolean = suspendRunCatching { await() }.isSuccess

        internal companion object {
            const val WORK_PERIODIC = "refresh-periodic"
            const val WORK_NOW = "refresh-now"
            const val WORK_AUTO = "refresh-auto"
            const val WORK_CONTINUATION = "refresh-continuation"
            const val WORK_IMPORT_SYNC = "import-sync"
            const val TAG_REFRESH = "refresh"

            /** `feeds.scheduled_tick_minutes` value for "no periodic work enqueued". */
            const val NO_TICK = -1L
            const val MIN_TICK_MINUTES = 60L
            const val FLEX_DIVISOR = 3L
            const val CONTINUATION_INITIAL_DELAY_MS = 60_000L
            const val CONTINUATION_BACKOFF_MS = 60_000L
        }
    }
