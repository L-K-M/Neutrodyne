// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult

/** W2 owner. Single flight on a planner mutex, IO; always watermark the FULL library first.
 * Batch reads/writes at 500 binds and skip unchanged watermarks. Take claimMutex only for
 * queue mutations/stop coordination, never while doing transfer IO. Desktop admission writes
 * allowMetered=true, requireCharging=false regardless of resolved attribution. */
interface AutoDownloadPlanner {
    suspend fun plan(podcastIds: Collection<Long>)
}

/** W3 owner. Single flight on a cleanup mutex. Recheck protections in the removal transaction;
 * disk work uses DeferredDeletes after commit. M6a's journal drain also holds claimMutex so
 * a new claim cannot reuse a path between the ownership check and its filesystem delete. */
interface CleanupPlanner {
    suspend fun run(reason: CleanupReason)
}

enum class CleanupReason { STARTUP, DAILY, PLAYED, POLICY_CHANGED, STORAGE }

/** W4 owner. Single flight on a move mutex, separate from planner/cleanup and claimMutex.
 * deadline is monotonic Clock.elapsedRealtime milliseconds; null means no deadline (desktop).
 * Copy/verify/sync precedes the guarded completed-row commit, then journal source deletion.
 * Bulk copy IO stays outside claimMutex; publication/rollback and journal drains share it.
 * No filesystem IO inside a DB transaction. */
interface DownloadMover {
    suspend fun run(
        target: String,
        deadline: Long? = null,
    ): MoveResult
}

enum class MoveResult { COMPLETE, PENDING, DEADLINE, STORAGE_FULL }

/** W4 owner validates and persists desktop registrations and root/move intent before scheduling.
 * Android registration may reject UNSUPPORTED. Never report success before durable registration. */
interface DownloadRootRegistration {
    suspend fun registerFolderRoot(absolutePath: String): FolderRootResult

    suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult
}

/** W6 Android adapter: latest historical process exit, null below API 34 or with no history.
 * USER_REQUESTED alone is insufficient: reconcile also checks token time and pendingManualJob.
 * Recents behavior remains a device evidence gap if an OEM also removes the persisted job. */
interface ExitReasonProbe {
    fun latest(): ProcessExit?
}

data class ProcessExit(
    val reason: ProcessExitReason,
    val timestampMs: Long,
)

enum class ProcessExitReason { USER_REQUESTED, OTHER }
