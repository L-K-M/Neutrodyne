// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DownloadLifecycle
import ch.lkmc.neutrodyne.core.domain.PlaybackBeforeUnsubscribe
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.DownloadController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Journal before cascades; derive surviving ownership after either commit or rollback. */
internal class DownloadLifecycleImpl(
    private val db: NeutrodyneDatabase,
    private val controller: DownloadController,
    private val files: DownloadControllerFiles,
    private val index: LocalMediaIndexStore,
    private val playback: PlaybackBeforeUnsubscribe,
    private val claimMutex: Mutex,
    private val io: CoroutineDispatcher,
    private val completedMedia: suspend (DownloadEntity) -> Unit,
) : DownloadLifecycle {
    override suspend fun beforeEpisodeRemoval(episodeIds: List<Long>) =
        controller.delete(episodeIds, DeleteReason.LIFECYCLE)

    override suspend fun beforeEpisodeMerge(
        loserEpisodeId: Long,
        winnerEpisodeId: Long,
    ) = withContext(io) {
        if (db.playSessionDao().get()?.currentEpisodeId == loserEpisodeId) {
            db.episodeDao().byId(loserEpisodeId)?.let { playback.pause(listOf(it.podcastId)) }
        }
        if (db.downloadDao().byEpisode(winnerEpisodeId) != null) {
            beforeEpisodeRemoval(listOf(loserEpisodeId))
            return@withContext
        }
        // Preserve the only download. Its recorded paths remain valid when moveDownload changes ID.
        controller.pause(listOf(loserEpisodeId))
        claimMutex.withLock {
            val row = db.downloadDao().byEpisode(loserEpisodeId) ?: return@withLock
            check(row.runnerToken == null) { "Download restarted during merge preparation; retry merge" }
            files.prepareRemoval(listOf(row))
        }
    }

    override suspend fun afterEpisodeMerges(episodeIds: List<Long>) =
        withContext(io) {
            claimMutex.withLock {
                for (id in episodeIds.distinct()) {
                    val row = db.downloadDao().byEpisode(id)
                    val uri = row?.finalUri
                    if (row == null || row.state != DownloadState.COMPLETED || uri == null) {
                        index.remove(id)
                    } else {
                        index.put(id, uri)
                        completedMedia(row)
                    }
                }
                // The journal drops paths owned by the moved row, or deletes unowned conflict files.
                files.finishRemoval(emptyList())
            }
        }

    override suspend fun beforeLibraryReplace() =
        withContext(io) {
            playback.pause(db.podcastDao().allForExport().map { it.id })
            beforeEpisodeRemoval(db.downloadDao().allRows().map { it.episodeId })
        }
}
