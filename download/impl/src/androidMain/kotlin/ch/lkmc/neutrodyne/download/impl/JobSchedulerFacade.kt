// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import ch.lkmc.neutrodyne.core.database.DownloadEntity

internal class JobSchedulerFacade(
    private val context: Context,
) {
    fun scheduleManual(due: List<DownloadEntity>): Boolean {
        if (Build.VERSION.SDK_INT < 34 || due.isEmpty()) return false
        val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)
        return try {
            if (!scheduler.canRunUserInitiatedJobs()) return false
            scheduler.schedule(info(due)) == JobScheduler.RESULT_SUCCESS
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun info(due: List<DownloadEntity>): JobInfo {
        check(Build.VERSION.SDK_INT >= 34)
        val remaining =
            if (due.any { it.totalBytes == null && it.estimatedBytes == null }) {
                JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
            } else {
                due.fold(0L) { sum, row ->
                    sum +
                        ((row.totalBytes ?: row.estimatedBytes ?: 0L) - row.downloadedBytes)
                            .coerceIn(0, Long.MAX_VALUE - sum)
                }
            }
        return JobInfo
            .Builder(JOB_ID, ComponentName(context, ManualDownloadJobService::class.java))
            .setUserInitiated(true)
            .setPersisted(true)
            .setRequiredNetwork(AndroidDownloadWork.network(due.none { it.allowMetered }))
            .setEstimatedNetworkBytes(remaining, 0L)
            .setBackoffCriteria(AndroidDownloadWork.BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
    }

    companion object {
        const val JOB_ID = 1001
        const val NAMESPACE = "downloads"
    }
}
