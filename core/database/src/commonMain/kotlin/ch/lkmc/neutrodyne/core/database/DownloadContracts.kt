// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Embedded
import androidx.room3.Relation
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import kotlinx.coroutines.flow.Flow

/** W2 implements SQL; this unbound contract changes neither Room entities nor schemas.
 * Engine owns claimMutex across candidate paging/slot admission/conditional claim. Controller
 * pause/cancel/delete share that mutex. SQL claim still checks QUEUED and runnerToken IS NULL.
 * No file/network work inside transactions; journal paths before deleting rows. */
interface DownloadQueries {
    suspend fun candidates(
        lanes: List<DownloadLane>,
        now: Long,
        unmetered: Boolean,
        charging: Boolean,
        youtubeAllowed: Boolean,
        offset: Int = 0,
    ): List<DownloadEntity>

    /** Transactional candidate selection and guarded claim; hostHasSlot must do no IO.
     * Caller holds claimMutex through slot reservation; page size is 20. */
    suspend fun claimNext(
        lanes: List<DownloadLane>,
        now: Long,
        unmetered: Boolean,
        charging: Boolean,
        youtubeAllowed: Boolean,
        token: String,
        offset: Int = 0,
        hostHasSlot: (DownloadEntity) -> Boolean,
    ): DownloadEntity?

    suspend fun claim(
        episodeId: Long,
        token: String,
    ): Int

    /** Atomic token-owned checkpoint; source and current controller policy are preserved.
     * Zero means stale owner. DOWNLOADING retains token,
     * final transitions clear it. Never use for progress ticks. */
    suspend fun transition(
        row: DownloadEntity,
        expectedState: DownloadState,
        token: String,
    ): Int

    /** Controller/reconcile replacement: require expected state AND no live runner token. */
    suspend fun updateUnclaimed(
        row: DownloadEntity,
        expectedState: DownloadState,
    ): Int

    suspend fun removeUnclaimed(episodeIds: List<Long>): Int

    /** Active cancel/delete finishes by guarded removal, without an intermediate state write. */
    suspend fun removeOwned(
        episodeId: Long,
        token: String,
    ): Int

    suspend fun allRows(): List<DownloadEntity>

    suspend fun completedIndex(): List<CompletedDownloadRow>

    fun observeEntries(): Flow<List<DownloadEntryRow>>

    fun observeFailedCount(): Flow<Int>

    suspend fun queuedNeeds(
        lane: DownloadLane,
        now: Long,
    ): QueuedDownloadNeeds

    suspend fun markWait(
        ids: List<Long>,
        reason: WaitReason,
        nextAttemptAt: Long?,
    ): Int

    suspend fun clearStorageWaits(): Int

    suspend fun pathsByRoot(): List<DownloadPathRow>

    suspend fun rootsInUse(): List<String>

    /** Transactional reset only of tokens absent from the process registry; never live owners. */
    suspend fun resetOrphanedRunners(
        liveTokens: Set<String>,
        stopReason: Int,
    ): Int

    suspend fun autoCandidates(
        podcastId: Long,
        eligibleAfter: Long,
        keepLatest: Int,
        includeVideo: Boolean,
        youtubeDownloads: Boolean,
    ): List<AutoDownloadCandidate>

    /** Played completed rows excluding the SQL protected set, AUTO first then oldest played. */
    suspend fun cleanupCandidates(upNextProtected: Int): List<CleanupCandidate>

    /** Protected rows still occupy their slot in the per-podcast rolling window. Chunk IDs at 500. */
    suspend fun unplayedAutoCompleted(
        podcastIds: List<Long>,
        upNextProtected: Int,
    ): List<RollingDownloadCandidate>

    suspend fun rowsOnOtherRoots(
        target: String,
        limit: Int,
    ): List<DownloadEntity>

    /** Consumer owns the 30 s debounce; this observes state transitions, not progress. */
    fun observePlayedCompletedIds(): Flow<List<Long>>

    /** Call before generic reset, only after W6's three-condition rule is satisfied.
     * Pauses stale active uidt: MANUAL owners and the unclaimed MANUAL queue atomically. */
    suspend fun resetTaskManagerStoppedRunners(
        liveTokens: Set<String>,
        stopReason: Int,
    ): Int

    suspend fun requeueChangedEnclosures(now: Long): List<DownloadPathRow>
}

/** EpisodeDao source projection, first audio alternate in stable rank order, batched at 500 IDs. */
interface DownloadSourceQueries {
    suspend fun downloadSources(ids: List<Long>): List<DownloadSourceRow>
}

data class DownloadSourceRow(
    val episodeId: Long,
    val podcastId: Long,
    val title: String,
    val podcastTitle: String,
    val sortDate: Long,
    val durationMs: Long?,
    val isVideo: Boolean,
    val availability: Availability,
    val sourceType: SourceType,
    val enclosureUrl: String?,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val externalMediaId: String?,
    @Relation(parentColumns = ["episodeId"], entityColumns = ["episodeId"])
    val audioAlternates: List<EpisodeAltEnclosureEntity>,
    val youtubeChannelId: String? = null,
)

data class CompletedDownloadRow(
    val episodeId: Long,
    val finalUri: String,
)

data class DownloadPathRow(
    val episodeId: Long,
    val rootId: String,
    val relativePath: String?,
    val tempPath: String?,
)

data class DownloadEntryRow(
    @Embedded val download: DownloadEntity,
    val podcastId: Long,
    val podcastTitle: String,
    val episodeTitle: String,
    val sortDate: Long,
    val artworkKey: String,
    val artworkUrl: String?,
    val artworkVersion: Int,
    val artworkAvgArgb: Int?,
    val playedAt: Long?,
    val isFavorite: Boolean,
)

/** SQL SUM/MIN/MAX stay nullable on an empty lane; consumers must handle queued == 0 first. */
data class QueuedDownloadNeeds(
    val queued: Int,
    val due: Int?,
    val anyMeteredAllowed: Boolean?,
    val allNeedUnmetered: Boolean?,
    val allNeedCharging: Boolean?,
    val earliestRetry: Long?,
    val earliestRetryMetered: Long?,
    val earliestRetryNoCharging: Long?,
    val remainingBytes: Long?,
)

/** State writes are in the controller's request/deletion transaction, never progress writes. */
interface DownloadStateQueries {
    suspend fun clearDismissed(ids: List<Long>)

    suspend fun setDismissed(
        ids: List<Long>,
        at: Long,
    )
}

interface DownloadSessionQueries {
    fun observeCurrentEpisodeId(): Flow<Long?>
}

/** Candidate rows include existing downloads so they count towards keepLatest. */
data class AutoDownloadCandidate(
    val id: Long,
    val sortDate: Long,
    val enclosureLength: Long?,
    val isVideo: Boolean,
    val externalMediaId: String?,
    val downloadState: DownloadState?,
    val lane: DownloadLane?,
)

data class CleanupCandidate(
    val episodeId: Long,
    val podcastId: Long,
    val playedAt: Long,
)

data class RollingDownloadCandidate(
    val episodeId: Long,
    val podcastId: Long,
    val protected: Boolean,
)
