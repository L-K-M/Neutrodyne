// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.domain.DownloadLifecycle
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.download.api.DeleteReason
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex

internal class DownloadHost(
    val liveTokens: () -> Set<String>,
    val askNotificationPermission: suspend () -> Boolean,
    val share: (String, String) -> String?,
    val activity: Flow<Long> = flowOf(0L),
    val hasActiveTransfers: () -> Boolean = { false },
    val charging: StateFlow<Boolean> = MutableStateFlow(true),
    val beforeRunnerReset: suspend (Set<String>) -> Unit = {},
)

/** Lazily resolved after DB open by the platform hosts, never by the index's constructor. */
@SingleIn(AppScope::class)
@Inject
internal class DownloadComponents(
    db: NeutrodyneDatabase,
    settings: SettingsRepository,
    device: DeviceSettingsStore,
    private val network: NetworkMonitor,
    clock: Clock,
    roots: StorageRoots,
    scheduler: DownloadScheduler,
    index: LocalMediaIndexImpl,
    credentials: CredentialLookup,
    clients: NeutrodyneHttpClients,
    private val platform: PlatformInfo,
    media: PlaybackMediaLookup,
    artwork: ArtworkStore,
    private val host: DownloadHost,
    resolver: EffectiveSettingsResolver,
    @param:ApplicationScope scope: CoroutineScope,
) {
    val claimMutex = Mutex()
    private val fileSystem = OkioDownloadFileSystem(roots)
    private val uris = PlatformLocalUris()
    private val verification = DownloadFiles(fileSystem, roots, PlatformSriHasher(roots), uris)
    val deletes = DeferredDeletes(device, fileSystem, roots, db.downloadDao()::allRows)
    val reconciler =
        DownloadReconciler(
            db,
            fileSystem,
            roots,
            uris,
            index,
            deletes,
            claimMutex,
            host.liveTokens,
            clock,
            host.beforeRunnerReset,
        )
    val quota = DownloadQuota(settings)
    val mover = DownloadMoverImpl(db, fileSystem, roots, device, index, deletes, claimMutex, clock, quota)
    private val storage =
        DownloadStorage(
            db,
            fileSystem,
            roots,
            device,
            index,
            deletes,
            reconciler,
            claimMutex,
            media,
            artwork,
            host.share,
            quota,
        )
    val cleanup = CleanupPlannerImpl(db, resolver, clock, storage, deletes, index, reconciler, claimMutex)
    val progress = DownloadProgressHub(scope, clock)

    private fun conditions() =
        network.status.value.let {
            val desktop = platform.kind == PlatformKind.DESKTOP
            Conditions(
                it.isConnected,
                desktop || !it.isMetered,
                desktop || host.charging.value,
                false,
                desktop || it.isValidated,
            )
        }

    private val conditions =
        combine(
            network.status,
            host.charging,
        ) { _, _ -> conditions() }.stateIn(scope, SharingStarted.Eagerly, conditions())
    val engine =
        DownloadEngine(
            db,
            clock,
            credentials,
            conditions,
            setOf(
                RssTransferSource(
                    clients,
                    credentials,
                    db.episodeDao(),
                    roots,
                    fileSystem,
                    verification,
                    network,
                    clock,
                    platform.kind,
                ),
            ),
            fileSystem,
            roots,
            verification,
            storage,
            index,
            scheduler,
            progress,
            claimMutex,
            reconciler::resetOrphanedRunners,
            { row ->
                deletes.completed(row)
                media.get(row.episodeId)?.let { artwork.pin(it.artwork, PinReason.DOWNLOAD, row.episodeId) }
            },
            cleanup,
        )
    val planner =
        AutoDownloadPlannerImpl(
            db,
            resolver,
            quota,
            device,
            clock,
            roots,
            scheduler,
            claimMutex,
            engine::stopTransfer,
            storage,
            media,
            platform.kind,
            Dispatchers.IO,
        )
    val controller =
        DownloadControllerImpl(
            db,
            settings,
            network,
            clock,
            roots,
            scheduler,
            claimMutex,
            engine::stopTransfer,
            storage,
            media,
            host.askNotificationPermission,
            host.activity,
            host.hasActiveTransfers,
        )
    val lifecycle: DownloadLifecycle =
        object : DownloadLifecycle {
            override suspend fun beforeEpisodeRemoval(episodeIds: List<Long>) =
                controller.delete(episodeIds, DeleteReason.LIFECYCLE)

            override suspend fun beforeEpisodeMerge(
                loserEpisodeId: Long,
                winnerEpisodeId: Long,
            ) = beforeEpisodeRemoval(listOf(loserEpisodeId))

            override suspend fun beforeLibraryReplace() =
                beforeEpisodeRemoval(db.downloadDao().allRows().map { it.episodeId })
        }
}
