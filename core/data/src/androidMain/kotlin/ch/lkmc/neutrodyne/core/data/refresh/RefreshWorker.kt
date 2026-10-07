// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The one worker behind every `refresh-*` unique work (03 Work requests): decodes the scope from
 * [RefreshWorkData], runs the engine under the 8-min soft deadline, and hands leftovers to the
 * `refresh-continuation` chain — `KEEP` cannot enqueue a successor from a running continuation, so
 * a deadline-bound continuation returns `Result.retry()` (linear 60 s) instead, at most 10 attempts.
 */
class RefreshWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val refresher: FeedRefresher,
        private val scheduler: RefreshScheduler,
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        /** The `MetroWorkerFactory` entry point; `:app` module's generated graph code calls it. */
        @AssistedFactory
        interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): RefreshWorker
        }

        override suspend fun doWork(): Result {
            // −1 (none enqueued) behaves as the 60-min floor for slack purposes.
            val tickMs = settings.get(FeedsSettingKeys.SCHEDULED_TICK_MINUTES).coerceAtLeast(MIN_TICK_MINUTES) * 60_000L
            val request =
                RefreshWorkData.request(
                    inputData,
                    deadlineElapsedMs = clock.elapsedRealtime() + SOFT_DEADLINE_MS,
                    dueSlackMs = tickMs / 4,
                    pagingBudgetMs = PAGING_BUDGET_MS,
                )
            try {
                val report = refresher.run(request)
                val more = report.remaining > 0 || report.stoppedByDeadline
                return when {
                    // Per-feed failures live on the podcast rows, not in WorkManager retries.
                    !more -> {
                        Result.success()
                    }

                    request.origin != RefreshOrigin.CONTINUATION -> {
                        // The continuation enqueue is awaited before success is reported: an
                        // unconfirmed enqueue would strand the leftovers (03 Worker).
                        if (scheduler.enqueueContinuation()) Result.success() else Result.retry()
                    }

                    // Attempts are zero-based: nine retries reach the tenth and last execution.
                    runAttemptCount < MAX_CONTINUATION_ATTEMPTS - 1 -> {
                        Result.retry()
                    }

                    else -> {
                        Result.success()
                    }
                }
            } finally {
                // 03 Diagnostics: Android reports the WorkManager stop reason (−1 when none);
                // the write survives the worker's own stop.
                withContext(NonCancellable) {
                    suspendRunCatching { settings.set(FeedsSettingKeys.LAST_RUN_STOP_REASON, stopReason) }
                }
            }
        }

        private companion object {
            const val MIN_TICK_MINUTES = 60L
            const val SOFT_DEADLINE_MS = 8 * 60_000L
            const val PAGING_BUDGET_MS = 6 * 60_000L
            const val MAX_CONTINUATION_ATTEMPTS = 10
        }
    }
