// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadStatus
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.FolderRejectReason
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Command recorder: never pretends to transfer, schedule or persist. Set result/flows explicitly. */
class FakeDownloadController : DownloadController {
    val calls = mutableListOf<Call>()
    val statuses = MutableStateFlow<Map<Long, DownloadStatus>>(emptyMap())
    val storage = MutableStateFlow(StorageUsage("dir:default", 0, 0, 0, null, null, false))
    val overview = MutableStateFlow(DownloadsOverview(emptyList(), emptyList(), emptyList(), storage.value, emptySet()))
    val failedCount = MutableStateFlow(0)
    val busy = MutableStateFlow(false)
    var roots: List<StorageRootInfo> = emptyList()
    var requestResult: RequestResult? = null
    var rootResult: RootChangeResult = RootChangeResult.Unavailable
    var orphanResult: OrphanDeleteResult = OrphanDeleteResult.Unavailable
    var sharedFile: ShareableFile? = null
    var failure: Throwable? = null

    data class Call(
        val action: Action,
        val ids: List<Long> = emptyList(),
        val lane: DownloadLane? = null,
        val allowMetered: Boolean? = null,
        val reason: DeleteReason? = null,
        val rootId: String? = null,
        val rootMode: RootChangeMode? = null,
    )

    enum class Action {
        REQUEST,
        PAUSE,
        RESUME,
        CANCEL,
        RETRY,
        PROMOTE,
        DELETE,
        METERED,
        PAUSE_ALL,
        RESUME_ALL,
        MISSING,
        ROOT,
        REGISTER,
        ORPHANS,
        SHARE,
    }

    private fun record(call: Call) {
        calls += call
        failure?.let { throw it }
    }

    private fun unavailable(ids: List<Long>) =
        RequestResult.Queued(
            0,
            0,
            ids.associateWith {
                RejectReason.UNAVAILABLE
            },
            false,
        )

    override suspend fun request(
        episodeIds: List<Long>,
        trigger: DownloadLane,
        allowMetered: Boolean?,
    ): RequestResult {
        record(Call(Action.REQUEST, episodeIds.toList(), trigger, allowMetered))
        return requestResult ?: unavailable(episodeIds)
    }

    override suspend fun promote(episodeId: Long): RequestResult {
        record(Call(Action.PROMOTE, listOf(episodeId)))
        return requestResult ?: unavailable(listOf(episodeId))
    }

    override suspend fun pause(episodeIds: Collection<Long>) = record(Call(Action.PAUSE, episodeIds.toList()))

    override suspend fun resume(episodeIds: Collection<Long>) = record(Call(Action.RESUME, episodeIds.toList()))

    override suspend fun cancel(episodeIds: Collection<Long>) = record(Call(Action.CANCEL, episodeIds.toList()))

    override suspend fun retry(episodeIds: Collection<Long>) = record(Call(Action.RETRY, episodeIds.toList()))

    override suspend fun delete(
        episodeIds: Collection<Long>,
        reason: DeleteReason,
    ) = record(Call(Action.DELETE, episodeIds.toList(), reason = reason))

    override suspend fun setAllowMetered(
        episodeIds: Collection<Long>,
        allowed: Boolean,
    ) = record(Call(Action.METERED, episodeIds.toList(), allowMetered = allowed))

    override suspend fun pauseAll() = record(Call(Action.PAUSE_ALL))

    override suspend fun resumeAll() = record(Call(Action.RESUME_ALL))

    override suspend fun reportFileMissing(episodeId: Long) = record(Call(Action.MISSING, listOf(episodeId)))

    override fun observe(episodeId: Long): Flow<DownloadStatus?> = statuses.map { it[episodeId] }.distinctUntilChanged()

    override fun observeAll(): Flow<DownloadsOverview> = overview

    override fun observeStorage(): Flow<StorageUsage> = storage

    override fun observeFailedCount(): Flow<Int> = failedCount

    override fun observeBusy(): Flow<Boolean> = busy

    override suspend fun storageRoots(): List<StorageRootInfo> = roots.toList()

    override suspend fun registerFolderRoot(absolutePath: String): FolderRootResult {
        record(Call(Action.REGISTER, rootId = absolutePath))
        return FolderRootResult.Rejected(FolderRejectReason.UNSUPPORTED)
    }

    override suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult {
        record(Call(Action.ROOT, rootId = rootId, rootMode = mode))
        return rootResult
    }

    override suspend fun deleteOrphanFiles(): OrphanDeleteResult {
        record(Call(Action.ORPHANS))
        return orphanResult
    }

    override suspend fun shareableFile(episodeId: Long): ShareableFile? {
        record(Call(Action.SHARE, listOf(episodeId)))
        return sharedFile
    }
}
