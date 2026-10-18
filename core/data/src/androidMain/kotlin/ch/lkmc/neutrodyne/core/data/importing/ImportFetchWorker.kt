// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The host behind unique work `import-{sessionId}` (05 §7): hands the session to
 * [ImportFetchRunner] with WorkManager's `runAttemptCount` and the 8-minute monotonic soft
 * deadline, and maps the lane's answer — `RETRY` reschedules under the request's linear 30 s
 * backoff. Cancellation propagates: a stopped run leaves the durable `COMMITTED`/`FETCHING`
 * session for the next enqueue, never a fake success.
 */
class ImportFetchWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val runner: ImportFetchRunner,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        /** The `MetroWorkerFactory` entry point; `:app` module's generated graph code calls it. */
        @AssistedFactory
        interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): ImportFetchWorker
        }

        override suspend fun doWork(): Result {
            val sessionId = ImportWorkData.sessionId(inputData) ?: return Result.success()
            val result =
                suspendRunCatching {
                    runner.run(
                        sessionId,
                        attempt = runAttemptCount,
                        deadlineElapsedMs = clock.elapsedRealtime() + SOFT_DEADLINE_MS,
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, error) { "import fetch run failed for session $sessionId" }
                    return Result.retry()
                }
            return when (result) {
                ImportFetchResult.COMPLETED -> Result.success()
                ImportFetchResult.RETRY -> Result.retry()
            }
        }

        private companion object {
            const val TAG = "ImportFetchWorker"

            /** 05 §7's host soft deadline, same as `RefreshWorker`'s. */
            const val SOFT_DEADLINE_MS = 8L * 60_000
        }
    }
