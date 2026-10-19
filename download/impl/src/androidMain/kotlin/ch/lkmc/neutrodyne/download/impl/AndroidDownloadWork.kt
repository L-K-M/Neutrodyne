// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import ch.lkmc.neutrodyne.core.model.DownloadLane
import java.util.concurrent.TimeUnit

internal object AndroidDownloadWork {
    const val KEY_LANE = "lane"
    const val MANUAL = "MANUAL"
    const val LANE = "download-lane-MANUAL"
    const val WAKE = "download-wake-MANUAL"
    const val CLEANUP = "download-cleanup"
    const val RECONCILE = "download-reconcile"
    const val TAG = "download"
    const val BACKOFF_MS = 30_000L

    fun laneName(lane: DownloadLane): String = "download-lane-${lane.name}"

    fun wakeName(lane: DownloadLane): String = "download-wake-${lane.name}"

    fun inputLane(data: androidx.work.Data): DownloadLane? =
        DownloadLane.entries.firstOrNull { it.name == data.getString(KEY_LANE) }

    fun cleanup(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(TAG)
            .build()

    fun network(unmetered: Boolean): NetworkRequest =
        NetworkRequest
            .Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .apply { if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()

    private fun constraints(
        unmetered: Boolean = false,
        lane: DownloadLane = DownloadLane.MANUAL,
        charging: Boolean = false,
    ): Constraints.Builder {
        val fallback = if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
        val builder = Constraints.Builder()
        // WorkManager falls back below 28; never call the platform NetworkRequest path there.
        if (Build.VERSION.SDK_INT >= 28) {
            builder.setRequiredNetworkRequest(network(unmetered), fallback)
        } else {
            builder.setRequiredNetworkType(fallback)
        }
        if (lane == DownloadLane.AUTO) {
            builder.setRequiresBatteryNotLow(true).setRequiresCharging(charging)
        }
        return builder
    }

    fun lane(
        visible: Boolean,
        lane: DownloadLane = DownloadLane.MANUAL,
    ): OneTimeWorkRequest {
        val builder =
            OneTimeWorkRequestBuilder<DownloadLaneWorker>()
                .setInputData(androidx.work.workDataOf(KEY_LANE to lane.name))
                .setConstraints(constraints(lane = lane).setRequiresStorageNotLow(lane == DownloadLane.AUTO).build())
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MS, TimeUnit.MILLISECONDS)
        if (lane == DownloadLane.MANUAL && (Build.VERSION.SDK_INT >= 31 || visible)) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }

    fun wake(
        delayMs: Long,
        unmetered: Boolean,
        lane: DownloadLane = DownloadLane.MANUAL,
        charging: Boolean = false,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DownloadWakeWorker>()
            .setInputData(androidx.work.workDataOf(KEY_LANE to lane.name))
            .setConstraints(constraints(unmetered, lane, charging).build())
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
}

/** WorkManager is resolved on use, after Application's Configuration.Provider is installed. */
internal class DownloadWorkQueue(
    private val context: Context,
) {
    private val manager get() = WorkManager.getInstance(context)

    suspend fun lane(
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        manager
            .enqueueUniqueWork(
                AndroidDownloadWork.laneName(checkNotNull(AndroidDownloadWork.inputLane(request.workSpec.input))),
                policy,
                request,
            ).await()
    }

    suspend fun wake(
        lane: DownloadLane,
        request: OneTimeWorkRequest,
    ) {
        manager.enqueueUniqueWork(AndroidDownloadWork.wakeName(lane), ExistingWorkPolicy.REPLACE, request).await()
    }

    suspend fun cancelWake(lane: DownloadLane) {
        manager.cancelUniqueWork(AndroidDownloadWork.wakeName(lane)).await()
    }

    suspend fun cleanup() {
        manager
            .enqueueUniquePeriodicWork(
                AndroidDownloadWork.CLEANUP,
                ExistingPeriodicWorkPolicy.UPDATE,
                AndroidDownloadWork.cleanup(),
            ).await()
    }

    suspend fun move(target: String) = DownloadMoveWorker.enqueue(context, target)

    suspend fun reconcile() {
        manager
            .enqueueUniqueWork(
                AndroidDownloadWork.RECONCILE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DownloadReconcileWorker>().addTag(AndroidDownloadWork.TAG).build(),
            ).await()
    }
}
