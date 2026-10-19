// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.work.WorkerParameters
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupPlanner
import ch.lkmc.neutrodyne.core.domain.DownloadLifecycle
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.MissingLocalMediaReporter
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadProgressSource
import ch.lkmc.neutrodyne.download.api.LocalMediaChanges
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import java.io.File

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

    @Provides
    @SingleIn(AppScope::class)
    internal fun runtime(
        components: Lazy<DownloadComponents>,
        scheduler: Lazy<AndroidDownloadScheduler>,
        maintenance: Lazy<AndroidDownloadMaintenance>,
    ): AndroidDownloadRuntime =
        AndroidDownloadRuntime(
            drain = { components.value.engine.drain(it) },
            reconcile = {
                components.value.reconciler.reconcile()
                for (lane in DownloadLane.entries) scheduler.value.ensureScheduled(lane)
            },
            // Framework callbacks can arrive before DB readiness; no drain exists to signal then.
            stop = { token, intent ->
                if (components.isInitialized()) components.value.engine.stopRunner(token, intent)
            },
            wake = { lane ->
                if (components.isInitialized()) components.value.engine.wake(lane)
            },
            controller = { components.value.controller },
            startup = { maintenance.value.startup() },
            daily = { maintenance.value.daily() },
        )

    @Provides
    @SingleIn(AppScope::class)
    internal fun roots(
        application: Application,
        device: DeviceSettingsStore,
    ): StorageRoots = AndroidStorageRoots(application, device)

    @Provides
    internal fun host(
        application: Application,
        scheduler: Lazy<AndroidDownloadScheduler>,
        charging: DownloadChargingMonitor,
        device: DeviceSettingsStore,
    ): DownloadHost =
        DownloadHost(
            { scheduler.value.liveTokens() },
            {
                val denied =
                    Build.VERSION.SDK_INT >= 33 &&
                        application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                val key = DownloadSettingKeys.NOTIFICATION_PROMPTED
                if (denied && !device.get(key)) {
                    device.set(key, true)
                    true
                } else {
                    false
                }
            },
            { root, path ->
                if (root.startsWith("ext:") && root != "ext:primary") {
                    null
                } else {
                    FileProvider
                        .getUriForFile(
                            application,
                            "${application.packageName}.fileprovider",
                            File(path),
                        ).toString()
                }
            },
            charging = charging.charging,
        )

    @Provides internal fun index(impl: LocalMediaIndexImpl): LocalMediaIndex = impl

    @Provides internal fun changes(impl: LocalMediaIndexImpl): LocalMediaChanges = impl

    @Provides internal fun controller(components: DownloadComponents): DownloadController = components.controller

    @Provides internal fun progress(components: DownloadComponents): DownloadProgressSource = components.progress

    @Provides internal fun lifecycle(components: DownloadComponents): DownloadLifecycle = components.lifecycle

    @Provides internal fun missing(components: DownloadComponents): MissingLocalMediaReporter =
        MissingLocalMediaReporter { components.controller.reportFileMissing(it) }

    @Provides
    @SingleIn(AppScope::class)
    internal fun scheduler(
        application: Application,
        db: Lazy<NeutrodyneDatabase>,
        opener: DatabaseOpener,
        network: NetworkMonitor,
        charging: DownloadChargingMonitor,
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
                Conditions(status.isConnected, !status.isMetered, charging.charging.value, false, status.isValidated)
            },
            visible = visibility::isVisible,
            backgroundLimited = { backgroundLimited(application) },
            scheduleUidt = JobSchedulerFacade(application)::scheduleManual,
            enqueue = queue::lane,
            armWake = { lane, request -> if (request == null) queue.cancelWake(lane) else queue.wake(lane, request) },
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

    @Provides internal fun planner(components: DownloadComponents): AutoDownloadPlanner = components.planner

    @Provides internal fun cleanupPlanner(components: DownloadComponents): CleanupPlanner = components.cleanup

    @Provides internal fun mover(components: DownloadComponents): DownloadMover = components.mover

    @Provides
    @SingleIn(AppScope::class)
    internal fun maintenance(
        components: Lazy<DownloadComponents>,
        scheduler: AndroidDownloadScheduler,
        resolver: EffectiveSettingsResolver,
        device: DeviceSettingsStore,
        clock: Clock,
        queue: DownloadWorkQueue,
    ): AndroidDownloadMaintenance =
        AndroidDownloadMaintenance(
            { components.value.reconciler.reconcile() },
            scheduler,
            components.value.planner,
            components.value.cleanup,
            resolver,
            device,
            clock,
            queue::move,
        )

    @Provides @IntoMap
    @WorkerKey(CleanupWorker::class)
    internal fun cleanupWorker(factory: CleanupWorker.Factory): WorkerCreator =
        { context, params -> factory.create(context, params) }

    @Provides @IntoMap
    @WorkerKey(DownloadMoveWorker::class)
    internal fun moveWorker(
        components: Lazy<DownloadComponents>,
        device: DeviceSettingsStore,
        clock: Clock,
    ): WorkerCreator =
        { context, params ->
            DownloadMoveWorker(
                context,
                params,
                object : DownloadMover {
                    override suspend fun run(
                        target: String,
                        deadline: Long?,
                    ) = components.value.mover.run(target, deadline)
                },
                device,
                clock,
            )
        }

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
