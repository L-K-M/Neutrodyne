// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.await
import java.util.concurrent.TimeUnit

internal object AndroidDownloadWork {
    const val KEY_LANE = "lane"
    const val MANUAL = "MANUAL"
    const val LANE = "download-lane-MANUAL"
    const val WAKE = "download-wake-MANUAL"
    const val RECONCILE = "download-reconcile"
    const val TAG = "download"
    const val BACKOFF_MS = 30_000L

    fun network(unmetered: Boolean): NetworkRequest =
        NetworkRequest
            .Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .apply { if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            .build()

    fun constraints(unmetered: Boolean = false): Constraints {
        val fallback = if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
        val builder = Constraints.Builder()
        // WorkManager falls back below 28; never call the platform NetworkRequest path there.
        if (Build.VERSION.SDK_INT >= 28) {
            builder.setRequiredNetworkRequest(network(unmetered), fallback)
        } else {
            builder.setRequiredNetworkType(fallback)
        }
        return builder.build()
    }

    fun lane(visible: Boolean): OneTimeWorkRequest {
        val builder =
            OneTimeWorkRequestBuilder<DownloadLaneWorker>()
                .setInputData(androidx.work.workDataOf(KEY_LANE to MANUAL))
                .setConstraints(constraints())
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MS, TimeUnit.MILLISECONDS)
        if (Build.VERSION.SDK_INT >= 31 || (Build.VERSION.SDK_INT < 31 && visible)) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }

    fun wake(
        delayMs: Long,
        unmetered: Boolean,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DownloadWakeWorker>()
            .setInputData(androidx.work.workDataOf(KEY_LANE to MANUAL))
            .setConstraints(constraints(unmetered))
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
        manager.enqueueUniqueWork(AndroidDownloadWork.LANE, policy, request).await()
    }

    suspend fun wake(request: OneTimeWorkRequest) {
        manager.enqueueUniqueWork(AndroidDownloadWork.WAKE, ExistingWorkPolicy.REPLACE, request).await()
    }

    suspend fun cancelWake() {
        manager.cancelUniqueWork(AndroidDownloadWork.WAKE).await()
    }

    suspend fun reconcile() {
        manager
            .enqueueUniqueWork(
                AndroidDownloadWork.RECONCILE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DownloadReconcileWorker>().addTag(AndroidDownloadWork.TAG).build(),
            ).await()
    }
}
