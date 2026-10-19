// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.api

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason

sealed interface RequestResult {
    data class Queued(
        val queued: Int,
        val alreadyPresent: Int,
        val rejected: Map<Long, RejectReason>,
        val askNotificationPermission: Boolean,
    ) : RequestResult

    data class NeedsMeteredDecision(
        val count: Int,
        val knownBytes: Long,
        val unknownSizeCount: Int,
    ) : RequestResult
}

enum class RejectReason { EPISODE_GONE, NOT_DOWNLOADABLE, UNSUPPORTED_STREAM, YOUTUBE_NOT_SUPPORTED, UNAVAILABLE }

data class LiveProgress(
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
)

data class DownloadStatus(
    val episodeId: Long,
    val lane: DownloadLane,
    val state: DownloadState,
    val waitReason: WaitReason,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val estimatedBytes: Long?,
    val bytesPerSecond: Long?,
    val nextAttemptAt: Long?,
    val lastError: DownloadError?,
    val lastHttpStatus: Int?,
    val allowMetered: Boolean,
    val requireCharging: Boolean,
    val sourceKind: SourceKind,
    val completedAt: Long?,
)

data class DownloadEntry(
    val status: DownloadStatus,
    val podcastId: Long,
    val podcastTitle: String,
    val episodeTitle: String,
    val artwork: ArtworkRef,
    val sortDate: Long,
    val played: Boolean,
    val favorite: Boolean,
    val artworkAvgArgb: Int? = null,
)

data class DownloadsOverview(
    val inProgress: List<DownloadEntry>,
    val completed: List<DownloadEntry>,
    val failed: List<DownloadEntry>,
    val storage: StorageUsage,
    val notices: Set<DownloadNotice>,
)

data class StorageUsage(
    val rootId: String,
    val usedBytes: Long,
    val partialBytes: Long,
    val orphanBytes: Long,
    val freeBytes: Long?,
    val capBytes: Long?,
    val autoBlockedByCap: Boolean,
)

data class StorageRootInfo(
    val rootId: String,
    val kind: RootKind,
    val label: String?,
    val freeBytes: Long?,
    val available: Boolean,
    val path: String? = null,
)

enum class RootKind { PRIMARY_EXTERNAL, REMOVABLE, INTERNAL, DESKTOP_DEFAULT, DESKTOP_FOLDER }

sealed interface FolderRootResult {
    data class Ok(
        val root: StorageRootInfo,
    ) : FolderRootResult

    data class Rejected(
        val reason: FolderRejectReason,
    ) : FolderRootResult
}

enum class FolderRejectReason { NOT_A_DIRECTORY, NOT_WRITABLE, INSIDE_APP_DIRECTORIES, UNSUPPORTED }

data class ShareableFile(
    val contentUri: String,
    val mimeType: String,
)

enum class DownloadNotice {
    DATA_SAVER,
    BACKGROUND_RESTRICTED,
    NOTIFICATIONS_OFF,
    CAP_REACHED,
    STORAGE_LOW,
    ROOT_UNAVAILABLE,
    YOUTUBE_PAUSED,
    MOVE_IN_PROGRESS,
    ORPHAN_FILES,
}

/** Lifecycle cleanup never dismisses an episode from future manual requests. */
enum class DeleteReason { USER, LIFECYCLE }

enum class RootChangeMode { NEW_DOWNLOADS_ONLY, MOVE_EXISTING }

sealed interface RootChangeResult {
    data object Applied : RootChangeResult

    data object Unavailable : RootChangeResult
}

sealed interface OrphanDeleteResult {
    data class Deleted(
        val bytes: Long,
    ) : OrphanDeleteResult

    data object Unavailable : OrphanDeleteResult
}
