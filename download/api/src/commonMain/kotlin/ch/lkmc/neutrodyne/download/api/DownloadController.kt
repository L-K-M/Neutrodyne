// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.api

import ch.lkmc.neutrodyne.core.model.DownloadLane
import kotlinx.coroutines.flow.Flow

/** Main-safe commands. Metered ASK returns before any persistent write or scheduler call.
 * AUTO admission is deferred to M6b; reject it with UNAVAILABLE in M6a.
 * Only successful durable operations may be reported as applied. */
interface DownloadController {
    suspend fun request(
        episodeIds: List<Long>,
        trigger: DownloadLane,
        allowMetered: Boolean? = null,
    ): RequestResult

    suspend fun pause(episodeIds: Collection<Long>)

    suspend fun resume(episodeIds: Collection<Long>)

    suspend fun cancel(episodeIds: Collection<Long>)

    suspend fun retry(episodeIds: Collection<Long>)

    suspend fun promote(episodeId: Long): RequestResult

    suspend fun delete(
        episodeIds: Collection<Long>,
        reason: DeleteReason,
    )

    suspend fun setAllowMetered(
        episodeIds: Collection<Long>,
        allowed: Boolean,
    )

    suspend fun pauseAll()

    suspend fun resumeAll()

    suspend fun reportFileMissing(episodeId: Long)

    fun observe(episodeId: Long): Flow<DownloadStatus?>

    fun observeAll(): Flow<DownloadsOverview>

    fun observeStorage(): Flow<StorageUsage>

    fun observeFailedCount(): Flow<Int>

    /** Active or runnable work; excludes backoff-only and storage-only waits. */
    fun observeBusy(): Flow<Boolean>

    suspend fun storageRoots(): List<StorageRootInfo>

    /** M6b; M6a returns Rejected(UNSUPPORTED) on both platforms. */
    suspend fun registerFolderRoot(absolutePath: String): FolderRootResult

    /** M6a accepts new-download roots only; completed rows retain their recorded roots. */
    suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult

    suspend fun deleteOrphanFiles(): OrphanDeleteResult

    suspend fun shareableFile(episodeId: Long): ShareableFile?
}
