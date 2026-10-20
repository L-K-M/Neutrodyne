// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import kotlinx.coroutines.flow.Flow

/** Persisted transitions only. Claim/finish guards also protect callers outside claimMutex. */
@Dao
abstract class DownloadDao : DownloadQueries {
    @Insert
    abstract suspend fun insert(row: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(row: DownloadEntity): Long

    @Query("SELECT * FROM download WHERE episodeId = :episodeId")
    abstract suspend fun byEpisode(episodeId: Long): DownloadEntity?

    @Query("SELECT d.episodeId FROM download d JOIN episode e ON e.id = d.episodeId WHERE e.podcastId IN (:podcastIds)")
    abstract suspend fun downloadedEpisodeIds(podcastIds: List<Long>): List<Long>

    @Query(
        "SELECT episodeId, state, waitReason, downloadedBytes, totalBytes, estimatedBytes," +
            " nextAttemptAt, lastError FROM download WHERE episodeId IN (:ids)",
    )
    abstract fun observeLiveFor(ids: List<Long>): Flow<List<DownloadLiveRow>>

    @Query(
        "SELECT * FROM download WHERE state = 'QUEUED' AND runnerToken IS NULL AND lane IN (:lanes)" +
            " AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)" +
            " AND (allowMetered = 1 OR :unmetered = 1) AND (requireCharging = 0 OR :charging = 1)" +
            " AND waitReason <> 'YOUTUBE_ENGINE_OFF' AND " + DOWNLOAD_SOURCE_ELIGIBLE +
            " ORDER BY priority DESC, requestedAt, episodeId LIMIT $DOWNLOAD_CANDIDATE_PAGE_SIZE OFFSET :offset",
    )
    abstract override suspend fun candidates(
        lanes: List<DownloadLane>,
        now: Long,
        unmetered: Boolean,
        charging: Boolean,
        youtubeAllowed: Boolean,
        offset: Int,
    ): List<DownloadEntity>

    @Transaction
    override suspend fun claimNext(
        lanes: List<DownloadLane>,
        now: Long,
        unmetered: Boolean,
        charging: Boolean,
        youtubeAllowed: Boolean,
        token: String,
        offset: Int,
        hostHasSlot: (DownloadEntity) -> Boolean,
    ): DownloadEntity? {
        require(token.isNotBlank())
        val pick =
            candidates(lanes, now, unmetered, charging, youtubeAllowed, offset).firstOrNull(hostHasSlot)
                ?: return null
        return if (claim(pick.episodeId, token, youtubeAllowed) == 1) {
            pick.copy(state = DownloadState.RESOLVING, waitReason = WaitReason.NONE, runnerToken = token)
        } else {
            null
        }
    }

    @Query(
        "UPDATE download SET state = 'RESOLVING', waitReason = 'NONE', runnerToken = :token" +
            " WHERE episodeId = :episodeId AND state = 'QUEUED' AND runnerToken IS" +
            " NULL AND length(trim(:token)) > 0" +
            " AND waitReason <> 'YOUTUBE_ENGINE_OFF' AND " + DOWNLOAD_SOURCE_ELIGIBLE,
    )
    abstract override suspend fun claim(
        episodeId: Long,
        token: String,
        youtubeAllowed: Boolean,
    ): Int

    @Transaction
    override suspend fun updateUnclaimed(
        row: DownloadEntity,
        expectedState: DownloadState,
    ): Int {
        val current = byEpisode(row.episodeId) ?: return 0
        if (current.state != expectedState || current.runnerToken != null || row.runnerToken != null ||
            current == row
        ) {
            return 0
        }
        return replaceRow(row)
    }

    // The guard and replacement share Room's serialised write transaction.
    @Update
    protected abstract suspend fun replaceRow(row: DownloadEntity): Int

    /** Only controller-owned columns: an in-flight policy edit must not overwrite byte checkpoints. */
    @Query(
        "UPDATE download SET lane = :lane, priority = :priority, allowMetered = :allowMetered," +
            " requireCharging = :requireCharging WHERE episodeId = :episodeId AND state <> 'COMPLETED'" +
            " AND (lane IS NOT :lane OR priority IS NOT :priority OR allowMetered IS NOT :allowMetered" +
            " OR requireCharging IS NOT :requireCharging)",
    )
    abstract suspend fun updatePolicy(
        episodeId: Long,
        lane: DownloadLane,
        priority: Int,
        allowMetered: Boolean,
        requireCharging: Boolean,
    ): Int

    @Query("DELETE FROM download WHERE episodeId IN (:episodeIds) AND runnerToken IS NULL")
    abstract override suspend fun removeUnclaimed(episodeIds: List<Long>): Int

    @Query("DELETE FROM download WHERE episodeId = :episodeId AND runnerToken = :token")
    abstract override suspend fun removeOwned(
        episodeId: Long,
        token: String,
    ): Int

    @Query("SELECT * FROM download ORDER BY episodeId")
    abstract override suspend fun allRows(): List<DownloadEntity>

    @Query("SELECT episodeId, finalUri FROM download WHERE state = 'COMPLETED' AND finalUri IS NOT NULL")
    abstract override suspend fun completedIndex(): List<CompletedDownloadRow>

    @Query(
        "SELECT d.*, e.podcastId, e.title AS episodeTitle, e.sortDate," +
            " COALESCE(p.customTitle, p.title) AS podcastTitle," +
            " COALESCE(e.artworkKey, p.artworkKey) AS artworkKey," +
            " COALESCE(e.imageUrl, p.artworkUrl) AS artworkUrl, COALESCE(a.version, 0) AS artworkVersion," +
            " s.playedAt, COALESCE(s.isFavorite, 0) AS isFavorite" +
            " FROM download d JOIN episode e ON e.id = d.episodeId JOIN podcast p ON p.id = e.podcastId" +
            " LEFT JOIN episode_state s ON s.episodeId = d.episodeId" +
            " LEFT JOIN artwork a ON a.key = COALESCE(e.artworkKey, p.artworkKey)",
    )
    abstract override fun observeEntries(): Flow<List<DownloadEntryRow>>

    @Query("SELECT COUNT(*) FROM download WHERE state = 'FAILED'")
    abstract override fun observeFailedCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) AS queued, SUM(CASE WHEN nextAttemptAt IS NULL OR" +
            " nextAttemptAt <= :now THEN 1 ELSE 0 END) AS due," +
            " MAX(allowMetered) AS anyMeteredAllowed, MIN(CASE WHEN allowMetered =" +
            " 0 THEN 1 ELSE 0 END) AS allNeedUnmetered," +
            " MIN(requireCharging) AS allNeedCharging," +
            " MIN(CASE WHEN nextAttemptAt > :now THEN nextAttemptAt END) AS earliestRetry," +
            " MIN(CASE WHEN nextAttemptAt > :now AND allowMetered = 1 THEN" +
            " nextAttemptAt END) AS earliestRetryMetered," +
            " MIN(CASE WHEN nextAttemptAt > :now AND requireCharging = 0 THEN" +
            " nextAttemptAt END) AS earliestRetryNoCharging," +
            " SUM(MAX(COALESCE(totalBytes, estimatedBytes, $UNKNOWN_DOWNLOAD_BYTES)" +
            " - downloadedBytes, 0)) AS remainingBytes" +
            " FROM download WHERE state = 'QUEUED' AND runnerToken IS NULL AND lane = :lane" +
            " AND waitReason <> 'YOUTUBE_ENGINE_OFF' AND " + DOWNLOAD_SOURCE_ELIGIBLE,
    )
    abstract override suspend fun queuedNeeds(
        lane: DownloadLane,
        now: Long,
        youtubeAllowed: Boolean,
    ): QueuedDownloadNeeds

    @Query(
        "UPDATE download SET waitReason = :reason, nextAttemptAt = :nextAttemptAt" +
            " WHERE episodeId IN (:ids) AND state = 'QUEUED' AND runnerToken IS NULL" +
            " AND (waitReason IS NOT :reason OR nextAttemptAt IS NOT :nextAttemptAt)",
    )
    abstract override suspend fun markWait(
        ids: List<Long>,
        reason: WaitReason,
        nextAttemptAt: Long?,
    ): Int

    @Query(
        "UPDATE download SET waitReason = 'NONE' WHERE state = 'QUEUED' AND runnerToken IS NULL AND waitReason = 'STORAGE'",
    )
    abstract override suspend fun clearStorageWaits(): Int

    @Query(
        "UPDATE download SET waitReason = 'YOUTUBE_ENGINE_OFF' WHERE sourceKind = 'YOUTUBE'" +
            " AND state = 'QUEUED' AND runnerToken IS NULL AND waitReason <> 'YOUTUBE_ENGINE_OFF'",
    )
    abstract suspend fun markYouTubeEngineWaits(): Int

    @Query(
        "UPDATE download SET waitReason = 'NONE' WHERE sourceKind = 'YOUTUBE' AND state = 'QUEUED'" +
            " AND runnerToken IS NULL AND waitReason = 'YOUTUBE_ENGINE_OFF'",
    )
    abstract suspend fun clearYouTubeEngineWaits(): Int

    @Query(
        "UPDATE download SET state = 'FAILED', waitReason = 'NONE', lastError = 'UNSUPPORTED_STREAM'," +
            " nextAttemptAt = NULL WHERE sourceKind = 'YOUTUBE' AND state <> 'COMPLETED' AND runnerToken IS NULL" +
            " AND (state <> 'FAILED' OR lastError IS NOT 'UNSUPPORTED_STREAM')",
    )
    abstract suspend fun failYouTubeRowsWithoutEngine(): Int

    @Query(
        "UPDATE download SET waitReason = 'BACKOFF', nextAttemptAt = :untilMs WHERE sourceKind = 'YOUTUBE'" +
            " AND state = 'QUEUED' AND runnerToken IS NULL AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)" +
            " AND waitReason <> 'YOUTUBE_ENGINE_OFF'",
    )
    abstract suspend fun markYouTubeGateWaits(
        now: Long,
        untilMs: Long,
    ): Int

    @Query(
        "SELECT episodeId, rootId, relativePath, tempPath FROM download WHERE relativePath IS NOT NULL OR tempPath IS NOT NULL",
    )
    abstract override suspend fun pathsByRoot(): List<DownloadPathRow>

    @Query("SELECT DISTINCT rootId FROM download ORDER BY rootId")
    abstract override suspend fun rootsInUse(): List<String>

    @Transaction
    override suspend fun resetOrphanedRunners(
        liveTokens: Set<String>,
        stopReason: Int,
    ): Int = resetOrphaned(liveTokens.toList(), stopReason)

    @Query(
        "UPDATE download SET state = 'QUEUED', waitReason = 'SYSTEM', runnerToken = NULL," +
            " lastError = 'CANCELLED_BY_SYSTEM', lastStopReason = :stopReason" +
            " WHERE state IN ('RESOLVING','DOWNLOADING','VERIFYING')" +
            " AND (runnerToken IS NULL OR runnerToken NOT IN (:liveTokens))",
    )
    protected abstract suspend fun resetOrphaned(
        liveTokens: List<String>,
        stopReason: Int,
    ): Int

    @Query(
        "SELECT e.id, e.sortDate, e.enclosureLength, e.isVideo, e.externalMediaId," +
            " d.state AS downloadState, d.lane FROM episode e JOIN podcast p ON p.id = e.podcastId" +
            " LEFT JOIN episode_state s ON s.episodeId = e.id LEFT JOIN download d ON d.episodeId = e.id" +
            " WHERE e.podcastId = :podcastId AND e.isNew = 1 AND e.firstSeenAt > :eligibleAfter" +
            " AND s.playedAt IS NULL AND s.downloadDismissedAt IS NULL AND " + FeedQueryBuilder.VISIBLE +
            " AND e.availability = 'AVAILABLE' AND (:includeVideo = 1 OR e.isVideo = 0)" +
            " AND (e.enclosureUrl IS NOT NULL OR (e.externalMediaId IS NOT NULL AND :youtubeDownloads = 1))" +
            " ORDER BY e.sortDate DESC, e.id DESC LIMIT :keepLatest",
    )
    abstract override suspend fun autoCandidates(
        podcastId: Long,
        eligibleAfter: Long,
        keepLatest: Int,
        includeVideo: Boolean,
        youtubeDownloads: Boolean,
    ): List<AutoDownloadCandidate>

    @Query(
        "SELECT d.episodeId, e.podcastId, s.playedAt FROM download d" +
            " JOIN episode e ON e.id = d.episodeId JOIN episode_state s ON s.episodeId = d.episodeId" +
            " WHERE d.state = 'COMPLETED' AND s.playedAt IS NOT NULL AND s.isFavorite = 0" +
            " AND d.episodeId NOT IN (SELECT currentEpisodeId FROM play_session WHERE currentEpisodeId IS NOT NULL)" +
            " AND d.episodeId NOT IN (SELECT q.episodeId FROM queue_entry q" +
            " ORDER BY q.orderKey, q.id LIMIT :upNextProtected)" +
            " ORDER BY (d.lane = 'AUTO') DESC, s.playedAt, d.episodeId",
    )
    abstract override suspend fun cleanupCandidates(upNextProtected: Int): List<CleanupCandidate>

    override suspend fun unplayedAutoCompleted(
        podcastIds: List<Long>,
        upNextProtected: Int,
    ): List<RollingDownloadCandidate> =
        podcastIds.distinct().chunked(DOWNLOAD_BIND_CHUNK).flatMap { unplayedAutoChunk(it, upNextProtected) }

    @Query(
        "SELECT d.episodeId, e.podcastId, (s.startedAt IS NOT NULL OR COALESCE(s.isFavorite, 0) = 1" +
            " OR d.episodeId IN (SELECT currentEpisodeId FROM play_session WHERE currentEpisodeId IS NOT NULL)" +
            " OR d.episodeId IN (SELECT q.episodeId FROM queue_entry q" +
            " ORDER BY q.orderKey, q.id LIMIT :upNextProtected)) AS protected" +
            " FROM download d JOIN episode e ON e.id = d.episodeId" +
            " LEFT JOIN episode_state s ON s.episodeId = d.episodeId" +
            " WHERE d.state = 'COMPLETED' AND d.lane = 'AUTO' AND e.podcastId IN (:podcastIds) AND s.playedAt IS NULL" +
            " ORDER BY e.podcastId, e.sortDate DESC, e.id DESC",
    )
    protected abstract suspend fun unplayedAutoChunk(
        podcastIds: List<Long>,
        upNextProtected: Int,
    ): List<RollingDownloadCandidate>

    @Query("SELECT * FROM download WHERE state = 'COMPLETED' AND rootId <> :target ORDER BY episodeId LIMIT :limit")
    abstract override suspend fun rowsOnOtherRoots(
        target: String,
        limit: Int,
    ): List<DownloadEntity>

    /** Keyset continuation prevents a full skipped batch from hiding available later rows. */
    @Query(
        "SELECT * FROM download WHERE state = 'COMPLETED' AND rootId <> :target" +
            " AND episodeId > :afterId ORDER BY episodeId LIMIT :limit",
    )
    abstract suspend fun rowsOnOtherRootsAfter(
        target: String,
        afterId: Long,
        limit: Int,
    ): List<DownloadEntity>

    @Query(
        "SELECT d.episodeId FROM download d JOIN episode_state s ON s.episodeId = d.episodeId" +
            " WHERE d.state = 'COMPLETED' AND s.playedAt IS NOT NULL ORDER BY d.episodeId",
    )
    abstract override fun observePlayedCompletedIds(): Flow<List<Long>>

    @Transaction
    override suspend fun resetTaskManagerStoppedRunners(
        liveTokens: Set<String>,
        stopReason: Int,
    ): Int = pauseStoppedUidt(liveTokens.toList(), stopReason) + pauseManualQueue(stopReason)

    @Query(
        "UPDATE download SET state = 'PAUSED', waitReason = 'NONE', runnerToken = NULL," +
            " lastError = 'CANCELLED_BY_SYSTEM', lastStopReason = :stopReason" +
            " WHERE lane = 'MANUAL' AND state IN ('RESOLVING','DOWNLOADING','VERIFYING')" +
            " AND runnerToken LIKE 'uidt:%' AND runnerToken NOT IN (:liveTokens)",
    )
    protected abstract suspend fun pauseStoppedUidt(
        liveTokens: List<String>,
        stopReason: Int,
    ): Int

    @Query(
        "UPDATE download SET state = 'PAUSED', waitReason = 'NONE', lastStopReason = :stopReason" +
            " WHERE lane = 'MANUAL' AND state = 'QUEUED' AND runnerToken IS NULL",
    )
    protected abstract suspend fun pauseManualQueue(stopReason: Int): Int

    @Transaction
    override suspend fun requeueChangedEnclosures(now: Long): List<DownloadPathRow> {
        val rows = changedEnclosures()
        rows.chunked(DOWNLOAD_BIND_CHUNK).forEach { requeueChanged(it.map(DownloadPathRow::episodeId)) }
        return rows
    }

    @Query(
        "SELECT d.episodeId, d.rootId, d.relativePath, d.tempPath FROM" +
            " download d JOIN episode e ON e.id = d.episodeId" +
            " WHERE d.state = 'FAILED' AND d.runnerToken IS NULL AND d.sourceKind = 'RSS_ENCLOSURE'" +
            " AND d.lastError IN ('HTTP_NOT_FOUND','HTTP_GONE','HTTP_CLIENT','NOT_MEDIA')" +
            " AND e.enclosureUrl IS NOT NULL AND d.sourceRef <> e.enclosureUrl",
    )
    protected abstract suspend fun changedEnclosures(): List<DownloadPathRow>

    @Query(
        "UPDATE download SET state = 'QUEUED', waitReason = 'NONE', attempt = 0, nextAttemptAt = NULL," +
            " lastError = NULL, lastHttpStatus = NULL, downloadedBytes = 0, etag = NULL, lastModified = NULL," +
            " tempPath = NULL, sourceRef = (SELECT e.enclosureUrl FROM episode e" +
            " WHERE e.id = download.episodeId)" +
            " WHERE episodeId IN (:ids) AND state = 'FAILED' AND runnerToken IS NULL",
    )
    protected abstract suspend fun requeueChanged(ids: List<Long>): Int

    /** The ownership read and replacement share one serialised write transaction. */
    @Transaction
    override suspend fun transition(
        row: DownloadEntity,
        expectedState: DownloadState,
        token: String,
    ): Int {
        val current = byEpisode(row.episodeId) ?: return 0
        if (current.state != expectedState || current.runnerToken != token) return 0

        // A runner snapshot must not undo controller consent or persist a resolved CDN URL.
        val checkpoint =
            row.copy(
                lane = current.lane,
                priority = current.priority,
                requestedAt = current.requestedAt,
                sourceKind = current.sourceKind,
                sourceRef = current.sourceRef,
                allowMetered = current.allowMetered,
                requireCharging = current.requireCharging,
                runnerToken = if (row.state in OWNED_STATES) token else null,
            )
        return replaceRow(checkpoint)
    }
}

internal const val DOWNLOAD_BIND_CHUNK = 500

private val OWNED_STATES = setOf(DownloadState.RESOLVING, DownloadState.DOWNLOADING, DownloadState.VERIFYING)

private const val DOWNLOAD_CANDIDATE_PAGE_SIZE = 20
private const val UNKNOWN_DOWNLOAD_BYTES = 150L * 1024 * 1024

// Keep candidate, direct claim and runnable summaries identical, including restored/forged rows.
private const val DOWNLOAD_SOURCE_ELIGIBLE =
    "EXISTS (SELECT 1 FROM episode e JOIN podcast p ON p.id = e.podcastId" +
        " WHERE e.id = download.episodeId AND ((download.sourceKind = 'RSS_ENCLOSURE'" +
        " AND p.sourceType = 'RSS' AND p.youtubeChannelId IS NULL) OR" +
        " (:youtubeAllowed = 1 AND download.sourceKind = 'YOUTUBE' AND p.sourceType = 'YOUTUBE_CHANNEL'" +
        " AND length(p.youtubeChannelId) = 24 AND substr(p.youtubeChannelId, 1, 2) = 'UC'" +
        " AND p.youtubeChannelId NOT GLOB '*[^A-Za-z0-9_-]*'" +
        " AND substr(p.youtubeChannelId, 24, 1) IN ('A','Q','g','w')" +
        " AND length(e.externalMediaId) = 11 AND e.externalMediaId NOT GLOB '*[^A-Za-z0-9_-]*'" +
        " AND download.sourceRef = e.externalMediaId)))"
