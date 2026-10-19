// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import kotlinx.coroutines.CancellationException

/** W5 supplies the worker factory and root/session triggers. No foreground or expedited work. */
class DownloadMoveWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val mover: DownloadMover,
    private val settings: SettingStore,
    private val clock: Clock,
    private val continuation: suspend (String) -> Unit = { enqueue(context, it) },
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val target = inputData.getString(KEY_TARGET) ?: return Result.failure()
        if (target.isEmpty()) return Result.failure()
        try {
            if (settings.get(DownloadSettingKeys.MOVE_TARGET) != target) return Result.success()
            if (mover.run(target, clock.elapsedRealtime() + SOFT_DEADLINE_MS) == MoveResult.DEADLINE &&
                settings.get(DownloadSettingKeys.MOVE_TARGET) == target
            ) {
                continuation(target)
            }
            // Pending playback, unmounted roots and low space need an event, not a hot work chain.
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("Downloads", failure) { "Download move failed" }
            return Result.retry()
        }
    }

    internal companion object {
        const val NAME = "download-move"
        const val KEY_TARGET = "targetRootId"
        const val SOFT_DEADLINE_MS = 8 * 60_000L

        fun request(target: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DownloadMoveWorker>()
                .setInputData(workDataOf(KEY_TARGET to target))
                .addTag(AndroidDownloadWork.TAG)
                .build()

        suspend fun enqueue(
            context: Context,
            target: String,
        ) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(target))
                .await()
        }
    }
}
