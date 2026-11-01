// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class AndroidDownloadApiGateTest {
    @Test fun persistedUidtAcceptsNetworkAndBackoff() {
        val context = RuntimeEnvironment.getApplication()
        val network =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
        val info =
            JobInfo
                .Builder(1001, ComponentName(context, "test.DownloadService"))
                .setUserInitiated(true)
                .setRequiredNetwork(network)
                .setPersisted(true)
                .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        assertTrue(info.isUserInitiated)
        assertTrue(info.isPersisted)
        assertEquals(network, info.requiredNetwork)
        val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace("downloads")
        assertEquals("downloads", scheduler.namespace)
        Constraints.Builder().setRequiredNetworkRequest(network, NetworkType.CONNECTED).build()
    }
}

/** Compile the service signatures too; device tests exercise their framework implementation. */
private abstract class ServiceApiProbe : android.app.job.JobService() {
    fun probe(
        params: android.app.job.JobParameters,
        notification: android.app.Notification,
    ) {
        setNotification(params, 2001, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
        updateEstimatedNetworkBytes(params, 100L, 0L)
        updateTransferredNetworkBytes(params, 50L, 0L)
        params.network
    }

    override fun onNetworkChanged(params: android.app.job.JobParameters) = Unit
}
