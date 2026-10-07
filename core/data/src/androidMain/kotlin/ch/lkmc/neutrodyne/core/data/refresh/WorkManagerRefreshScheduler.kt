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
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
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
 * refresh deliberately avoids), `refresh-continuation` with `KEEP` for deadline leftovers,
 * `import-sync` for sync-added libraries, and the `refresh-periodic` tick whose interval and
 * constraint set are stored in `feeds.scheduled_tick_*` so re-enqueues only happen on a change.
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
            if (scope is RefreshScope.Podcasts && scope.ids.size > RefreshWorkData.MAX_SCOPE_IDS) {
                // `Data` caps at 10 KB: persist the due marks, then send a plain All run (03 Work
                // requests). The enqueue rides along in the same coroutine to keep the order.
                appScope.launch {
                    db.podcastDao().forceDue(scopeAll = false, ids = scope.ids)
                    enqueueUnique(
                        WORK_NOW,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        nowRequest(RefreshScope.All, false, pagesOnly, origin),
                    )
                }
                return
            }
            enqueueUnique(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, nowRequest(scope, force, pagesOnly, origin))
        }

        override suspend fun reschedulePeriodic() {
            val intervalMinutes = settings.get(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES).toLong()
            val wifiOnly = settings.get(FeedsSettingKeys.REFRESH_WIFI_ONLY)
            val tick = if (intervalMinutes == 0L) NO_TICK else maxOf(MIN_TICK_MINUTES, intervalMinutes)
            val unmetered = wifiOnly

            val lastTick = settings.get(FeedsSettingKeys.SCHEDULED_TICK_MINUTES)
            val lastUnmetered = settings.get(FeedsSettingKeys.SCHEDULED_TICK_UNMETERED)
            if (tick == NO_TICK) {
                if (lastTick != NO_TICK) {
                    workManager.cancelUniqueWork(WORK_PERIODIC)
                    settings.set(FeedsSettingKeys.SCHEDULED_TICK_MINUTES, NO_TICK)
                    settings.set(FeedsSettingKeys.SCHEDULED_TICK_UNMETERED, unmetered)
                }
            } else if (tick != lastTick || unmetered != lastUnmetered) {
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
                workManager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
                settings.set(FeedsSettingKeys.SCHEDULED_TICK_MINUTES, tick)
                settings.set(FeedsSettingKeys.SCHEDULED_TICK_UNMETERED, unmetered)
            }

            // Interval changes take effect without waiting for the old schedule (03 Periodic tick).
            rebaser.rebase()
        }

        override fun enqueueContinuation() {
            // Non-suspend for the worker's call path; the constraint read rides the app scope.
            appScope.launch {
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
                enqueueUnique(WORK_CONTINUATION, ExistingWorkPolicy.KEEP, request)
            }
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

        internal companion object {
            const val WORK_PERIODIC = "refresh-periodic"
            const val WORK_NOW = "refresh-now"
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
