// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.WaitReason
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Stable manifest name. Member injection and the mandatory notification never resolve Room. */
@androidx.annotation.RequiresApi(34)
class ManualDownloadJobService : JobService() {
    @Inject internal lateinit var scheduler: Lazy<AndroidDownloadScheduler>

    @Inject internal lateinit var notifications: Lazy<DownloadNotifications>

    @Inject internal lateinit var runtime: Lazy<AndroidDownloadRuntime>

    @Inject internal lateinit var clock: Clock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var current: JobParameters? = null

    @Volatile private var token: RunnerToken? = null
    private var work: Job? = null

    override fun onCreate() {
        super.onCreate()
        ((applicationContext as GraphHolder).graph as DownloadComponentsInjector).inject(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val invocation = RunnerToken("uidt:${clock.now()}:${UUID.randomUUID()}")
        current = params
        token = invocation
        scheduler.value.registry.uidtStarted(invocation)
        notifications.value.ensureChannels()
        setNotification(
            params,
            DownloadNotifications.PROGRESS_ID,
            notifications.value.progress(),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val exit =
            scheduler.value.exit {
                // Called under the scheduler mutex, before another enqueue may schedule job1001.
                jobFinished(params, false)
                if (current === params) {
                    current = null
                    token = null
                }
            }
        work =
            scope.launch {
                try {
                    runtime.value.reconcile()
                    runtime.value.drain(
                        DrainRequest(
                            setOf(DownloadLane.MANUAL),
                            invocation,
                            null,
                            UIDT_HORIZON_MS,
                            reporter(params),
                            exit,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e("Downloads", failure) { "UIDT runner failed" }
                    withContext(NonCancellable) {
                        scheduler.value.finishStopped(invocation) {
                            if (current === params) {
                                jobFinished(params, true)
                                current = null
                                token = null
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) { exit.unregister(invocation) }
                }
            }
        return true
    }

    private fun reporter(params: JobParameters): DrainReporter =
        notifications.value.reporter { notification, downloaded, total, transferred ->
            setNotification(
                params,
                DownloadNotifications.PROGRESS_ID,
                notification,
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
            updateTransferredNetworkBytes(params, transferred, 0L)
            val unknown = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
            val remaining = total?.let { (it - downloaded).coerceAtLeast(0L) } ?: unknown
            updateEstimatedNetworkBytes(params, remaining, 0L)
        }

    override fun onStopJob(params: JobParameters): Boolean {
        if (current !== params) return false
        val invocation = token ?: return false
        val intent = stopIntent(params.stopReason)
        runCatching { runtime.value.stop(invocation, intent) }
            .onFailure { Log.e("Downloads", it) { "Could not deliver UIDT stop" } }
        work?.cancel()
        // Retain the token through child cleanup so reconciliation never steals a live row.
        current = null
        token = null
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onNetworkChanged(params: JobParameters) {
        if (current === params) scheduler.value.wake(DownloadLane.MANUAL)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        const val UIDT_HORIZON_MS = 5 * 60_000L

        fun stopIntent(reason: Int): StopIntent =
            when (reason) {
                JobParameters.STOP_REASON_USER -> StopIntent.RunnerStopped(reason, RunnerStopMode.PAUSE)
                JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> StopIntent.Requeue(WaitReason.NETWORK, reason)
                else -> StopIntent.RunnerStopped(reason)
            }
    }
}

/** Implemented by the application graph for framework-created components. */
interface DownloadComponentsInjector {
    fun inject(service: ManualDownloadJobService)

    fun inject(receiver: DownloadActionReceiver)
}
