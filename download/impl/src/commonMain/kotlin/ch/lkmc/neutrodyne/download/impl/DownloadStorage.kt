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
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) : DownloadControllerFiles {
    /** The runtime supplies this same state to the planner; admission owns the flag. */
    val autoBlockedByCap = MutableStateFlow(false)

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
            autoBlockedByCap,
        ) { entries, orphans, blocked ->
            val rows = entries.map { it.download }
            val root = roots.current()
            var partial = 0L
            for (row in rows) {
                val path = row.tempPath ?: continue
                if (roots.resolve(row.rootId) != null) partial += files.stat(row.rootId, path)?.bytes ?: 0
            }
            StorageUsage(
                root,
                rows.filter { it.state == DownloadState.COMPLETED }.sumOf { it.totalBytes ?: 0 },
                partial,
                orphans,
                roots.list().firstOrNull { it.rootId == root }?.freeBytes,
                null,
                blocked,
            )
        }

    override suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult {
        if (mode != RootChangeMode.NEW_DOWNLOADS_ONLY) return RootChangeResult.Unsupported
        if (roots.resolve(rootId) == null) return RootChangeResult.Unavailable
        settings.set(DownloadSettingKeys.ROOT_ID, rootId)
        return RootChangeResult.Applied
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
