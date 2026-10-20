// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.artwork.PinReason
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException

internal class DownloadStorage(
    private val db: NeutrodyneDatabase,
    private val files: DownloadFileSystem,
    private val roots: StorageRoots,
    private val settings: SettingStore,
    private val index: LocalMediaIndexStore,
    private val deletes: DeferredDeletes,
    private val reconciler: DownloadReconciler,
    private val claimMutex: Mutex,
    private val media: PlaybackMediaLookup,
    private val artwork: ArtworkStore,
    private val share: (String, String) -> String?,
    private val quota: DownloadQuota,
) : DownloadControllerFiles {
    override suspend fun prepareRemoval(rows: List<DownloadEntity>) {
        rows.forEach { index.remove(it.episodeId) }
        deletes.journal(rows)
    }

    override suspend fun finishRemoval(rows: List<DownloadEntity>) {
        for (row in rows) {
            media.get(row.episodeId)?.let { artwork.unpin(it.artwork.key, PinReason.DOWNLOAD, row.episodeId) }
        }
        deletes.drain(db.playSessionDao().get()?.currentEpisodeId)
    }

    override suspend fun reportFileMissing(episodeId: Long) = reconciler.reportFileMissing(episodeId)

    override fun observeStorage() =
        combine(
            db.downloadDao().observeEntries(),
            reconciler.orphanBytes,
            quota.capMb,
            quota.refusedEstimate,
            roots.observeCurrent(settings),
        ) { entries, orphans, capMb, _, _ ->
            val rows = entries.map { it.download }
            val root = roots.current()
            var partial = 0L
            for (row in rows) {
                val path = row.tempPath ?: continue
                if (!path.endsWith(".part") || roots.resolve(row.rootId) == null) continue
                try {
                    val info = files.stat(row.rootId, path)
                    if (info != null && !info.directory) partial += info.bytes
                } catch (_: IOException) {
                    // A removable root may vanish between resolve and stat; reconcile owns its state.
                }
            }
            val cap = DownloadQuota.capBytes(capMb)
            StorageUsage(
                root,
                rows.filter { it.state == DownloadState.COMPLETED }.sumOf { it.totalBytes ?: 0 },
                partial,
                orphans,
                roots.list().firstOrNull { it.rootId == root }?.freeBytes,
                cap,
                quota.autoBlocked(rows, cap),
            )
        }

    override suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult {
        return claimMutex.withLock {
            if (roots.resolve(rootId) == null) return@withLock RootChangeResult.Unavailable
            // Persist intent first: a crash cannot lose an accepted move before scheduling it.
            settings.set(DownloadSettingKeys.MOVE_TARGET, if (mode == RootChangeMode.MOVE_EXISTING) rootId else "")
            roots.setCurrent(rootId, settings)
            quota.moveStorageLow.value = false
            quota.moveUnavailableSources.value = 0
            RootChangeResult.Applied
        }
    }

    override fun observeNotices() =
        combine(
            settings.observe(DownloadSettingKeys.MOVE_TARGET),
            quota.moveStorageLow,
            quota.moveUnavailableSources,
        ) { target, low, unavailable ->
            buildSet {
                if (target.isNotEmpty()) add(DownloadNotice.MOVE_IN_PROGRESS)
                if (low) add(DownloadNotice.STORAGE_LOW)
                if (unavailable > 0) add(DownloadNotice.ROOT_UNAVAILABLE)
            }
        }

    override suspend fun deleteOrphanFiles(): OrphanDeleteResult =
        claimMutex.withLock {
            if (roots.resolve(roots.current()) == null) return@withLock OrphanDeleteResult.Unavailable
            var deleted = 0L
            for ((root, file) in reconciler.orphanFiles()) {
                files.delete(root, file.path)
                deleted += file.bytes
            }
            reconciler.orphanBytes.value = 0
            OrphanDeleteResult.Deleted(deleted)
        }

    override suspend fun shareableFile(episodeId: Long): ShareableFile? =
        claimMutex.withLock {
            val row = db.downloadDao().byEpisode(episodeId) ?: return@withLock null
            if (row.state != DownloadState.COMPLETED) return@withLock null
            val root = roots.resolve(row.rootId) ?: return@withLock null
            val path = row.relativePath ?: return@withLock null
            val info = files.stat(row.rootId, path) ?: return@withLock null
            if (info.directory || info.bytes != row.totalBytes) return@withLock null
            val uri = share(row.rootId, "${root.absolutePath}/$path") ?: return@withLock null
            ShareableFile(uri, row.mimeType ?: "application/octet-stream")
        }
}
