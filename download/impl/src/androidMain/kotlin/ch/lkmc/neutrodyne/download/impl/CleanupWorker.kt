// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.Log
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException

class CleanupWorker
    @AssistedInject
    internal constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val runtime: Lazy<AndroidDownloadRuntime>,
    ) : CoroutineWorker(context, params) {
        @AssistedFactory interface Factory {
            fun create(
                context: Context,
                params: WorkerParameters,
            ): CleanupWorker
        }

        override suspend fun doWork(): Result {
            try {
                runtime.value.daily()
                return Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("Downloads", failure) { "Download cleanup failed" }
                return Result.retry()
            }
        }
    }
