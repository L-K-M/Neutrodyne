// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DownloadLifecycle
import ch.lkmc.neutrodyne.core.domain.MissingLocalMediaReporter
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadProgressSource
import ch.lkmc.neutrodyne.download.api.LocalMediaChanges
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.download.api.LocalMediaReadiness
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@ContributesTo(AppScope::class)
@BindingContainer
object DesktopDownloadBindings {
    @Provides
    @SingleIn(AppScope::class)
    internal fun roots(dirs: AppDirs): StorageRoots = DesktopStorageRoots(dirs)

    @Provides
    @SingleIn(AppScope::class)
    internal fun scheduler(
        poker: () -> JobLanePoker,
        components: Lazy<DownloadComponents>,
    ): DownloadScheduler = DesktopDownloadScheduler(poker) { components.value.engine.wake(it) }

    @Provides internal fun host(activity: DesktopDownloadActivity): DownloadHost =
        DownloadHost(
            activity::liveTokens,
            { false },
            { _, _ -> null },
            activity.changes,
            activity::hasActiveTransfers,
        )

    @Provides @IntoSet
    internal fun manual(
        components: Lazy<DownloadComponents>,
        activity: DesktopDownloadActivity,
    ): JobLane =
        DesktopDownloadLane(
            { components.value.reconciler.reconcile() },
            { components.value.engine.drain(it) },
            activity,
        )

    @Provides internal fun index(impl: LocalMediaIndexImpl): LocalMediaIndex = impl

    @Provides internal fun changes(impl: LocalMediaIndexImpl): LocalMediaChanges = impl

    @Provides internal fun controller(components: DownloadComponents): DownloadController = components.controller

    @Provides internal fun progress(components: DownloadComponents): DownloadProgressSource = components.progress

    @Provides internal fun readiness(
        components: Lazy<DownloadComponents>,
        @Dispatcher(NeutrodyneDispatchers.IO) io: CoroutineDispatcher,
    ): LocalMediaReadiness = LocalMediaReadiness { withContext(io) { components.value.reconciler.initialLoad() } }

    @Provides internal fun lifecycle(components: DownloadComponents): DownloadLifecycle = components.lifecycle

    @Provides internal fun missing(components: DownloadComponents): MissingLocalMediaReporter =
        MissingLocalMediaReporter { components.controller.reportFileMissing(it) }
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class DesktopDownloadIndexInitializer(
    private val components: Lazy<DownloadComponents>,
) : AppInitializer {
    override val order = 130

    override suspend fun run() = components.value.reconciler.initialLoad()
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class DesktopDeferredDeletesInitializer(
    private val components: Lazy<DownloadComponents>,
    private val db: Lazy<NeutrodyneDatabase>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 310

    override suspend fun run() {
        scope.launch {
            db.value.playSessionDao().observeCurrentEpisodeId().collect {
                val downloads = components.value
                suspendRunCatching {
                    downloads.claimMutex.withLock { downloads.deletes.drain(it) }
                }.onFailure { Log.e("Downloads", it) { "Deferred download deletion failed" } }
            }
        }
    }
}
