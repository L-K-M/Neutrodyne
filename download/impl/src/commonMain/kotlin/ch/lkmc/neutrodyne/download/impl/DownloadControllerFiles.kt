// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageUsage
import kotlinx.coroutines.flow.Flow

/** W5 owns disk/index operations. Required at construction; no successful placeholder actions.
 * Controller holds claimMutex during removal. prepareRemoval removes index entries and durably
 * journals paths BEFORE the DB transaction. finishRemoval unpins/drains AFTER commit, retaining
 * journal entries for playing files/unavailable roots. Neither callback acquires claimMutex. */
internal interface DownloadControllerFiles {
    suspend fun prepareRemoval(rows: List<DownloadEntity>)

    suspend fun finishRemoval(rows: List<DownloadEntity>)

    suspend fun reportFileMissing(episodeId: Long)

    fun observeStorage(): Flow<StorageUsage>

    suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult

    suspend fun deleteOrphanFiles(): OrphanDeleteResult

    suspend fun shareableFile(episodeId: Long): ShareableFile?
}
