// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.os.Build
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first

@ContributesTo(AppScope::class)
@BindingContainer
object AndroidDownloadBindings {
    @Provides
    @SingleIn(AppScope::class)
    internal fun registry(): LaneRegistry = LaneRegistry()

    @Provides
    @SingleIn(AppScope::class)
    internal fun queue(application: Application): DownloadWorkQueue = DownloadWorkQueue(application)

    @Provides
    @SingleIn(AppScope::class)
    internal fun notifications(
        application: Application,
        clock: Clock,
    ): DownloadNotifications = DownloadNotifications(application, clock)

    /** W4/W5 replace this provider when integrating their common engine/reconciler/controller.
     * Fail explicitly until then: persisted requests must not report successful no-op downloads. */
    @Provides
    @SingleIn(AppScope::class)
    internal fun runtime(): AndroidDownloadRuntime =
        AndroidDownloadRuntime(
            drain = { error("W4 DownloadRunner is not integrated") },
            reconcile = { error("W5 DownloadReconciler is not integrated") },
            stop = { _, _ -> error("W4 runner stops are not integrated") },
            wake = { error("W4 runner wake is not integrated") },
            controller = { error("W4/W5 DownloadController composition is not integrated") },
        )

    @Provides
    @SingleIn(AppScope::class)
    internal fun scheduler(
        application: Application,
        db: Lazy<NeutrodyneDatabase>,
        opener: DatabaseOpener,
        network: NetworkMonitor,
        visibility: DownloadAppVisibility,
        queue: DownloadWorkQueue,
        runtime: Lazy<AndroidDownloadRuntime>,
        notifications: DownloadNotifications,
        clock: Clock,
        registry: LaneRegistry,
    ): AndroidDownloadScheduler =
        AndroidDownloadScheduler(
            rows = {
                awaitDownloadDatabase(opener)
                db.value.downloadDao().allRows()
            },
            markForeground = { ids, reason ->
                awaitDownloadDatabase(opener)
                for (id in ids) {
                    val row = db.value.downloadDao().byEpisode(id) ?: continue
                    db.value.downloadDao().markWait(listOf(id), reason, row.nextAttemptAt)
                }
            },
            conditions = {
                val status = network.status.value
                Conditions(status.isConnected, !status.isMetered, true, false, status.isValidated)
            },
            visible = visibility::isVisible,
            backgroundLimited = { backgroundLimited(application) },
            scheduleUidt = JobSchedulerFacade(application)::scheduleManual,
            enqueue = queue::lane,
            armWake = { request -> if (request == null) queue.cancelWake() else queue.wake(request) },
            runtimeWake = { runtime.value.wake(it) },
            waitingNotice = notifications::needsForeground,
            clock = clock,
            registry = registry,
            sdk = Build.VERSION.SDK_INT,
        )

    @Provides internal fun schedulerPort(impl: AndroidDownloadScheduler): DownloadScheduler = impl

    @Provides @IntoMap
    @WorkerKey(DownloadLaneWorker::class)
    internal fun laneWorker(factory: DownloadLaneWorker.Factory): WorkerCreator =
        { context, params -> factory.create(context, params) }

    @Provides @IntoMap
    @WorkerKey(DownloadWakeWorker::class)
    internal fun wakeWorker(factory: DownloadWakeWorker.Factory): WorkerCreator =
        { context, params -> factory.create(context, params) }

    @Provides @IntoMap
    @WorkerKey(DownloadReconcileWorker::class)
    internal fun reconcileWorker(factory: DownloadReconcileWorker.Factory): WorkerCreator =
        { context, params -> factory.create(context, params) }

    private fun backgroundLimited(application: Application): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return application.getSystemService(ActivityManager::class.java).isBackgroundRestricted ||
            application.getSystemService(UsageStatsManager::class.java).appStandbyBucket ==
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED
    }
}

/** Suspend on startup instead of entering DatabaseOpener's blocking lazy accessor. */
internal suspend fun awaitDownloadDatabase(opener: DatabaseOpener) {
    val state = opener.openState.first { it !is DatabaseOpenState.Pending }
    if (state is DatabaseOpenState.Failed) throw state.exception
}
