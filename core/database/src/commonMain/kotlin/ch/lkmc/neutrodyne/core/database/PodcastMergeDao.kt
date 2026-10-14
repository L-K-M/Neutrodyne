// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Query

/** 02 Unsubscribe and merge. The caller owns one write transaction for all these statements. */
@Dao
interface PodcastMergeDao {
    @Query(
        "INSERT OR IGNORE INTO podcast_group_member(groupId, podcastId, orderKey, addedAt, source)" +
            " SELECT groupId, :winner, orderKey, addedAt, source FROM podcast_group_member WHERE podcastId = :loser",
    )
    suspend fun copyMemberships(
        loser: Long,
        winner: Long,
    )

    @Query("UPDATE OR IGNORE podcast_url_alias SET podcastId = :winner WHERE podcastId = :loser")
    suspend fun moveAliases(
        loser: Long,
        winner: Long,
    )

    @Query(
        "INSERT OR IGNORE INTO podcast_url_alias(url, podcastId, reason, addedAt)" +
            " SELECT :url, :winner, 'MERGE', :now WHERE NOT EXISTS" +
            " (SELECT 1 FROM podcast WHERE feedKey = :url AND id <> :loser)",
    )
    suspend fun addMergeAlias(
        loser: Long,
        winner: Long,
        url: String,
        now: Long,
    )

    @Query(
        "DELETE FROM podcast_url_alias WHERE podcastId = :winner" +
            " AND url IN (SELECT feedKey FROM podcast WHERE id <> :loser)",
    )
    suspend fun removeFeedKeyAliases(
        loser: Long,
        winner: Long,
    )

    @Query(
        "INSERT OR IGNORE INTO podcast_settings" +
            " SELECT :winner, playbackSpeed, skipSilence, boostDb, introSkipMs, outroSkipMs," +
            " autoDownload, autoDownloadKeepLatest, autoDownloadNetwork, autoDownloadRequireCharging," +
            " deleteAfterPlayed, includeVideoInAutoDownload, notifyNewEpisodes, refreshIntervalMinutes" +
            " FROM podcast_settings WHERE podcastId = :loser",
    )
    suspend fun copySettings(
        loser: Long,
        winner: Long,
    )

    @Query(
        "UPDATE podcast SET customTitle = (SELECT customTitle FROM podcast WHERE id = :loser)" +
            " WHERE id = :winner AND customTitle IS NULL" +
            " AND (SELECT customTitle FROM podcast WHERE id = :loser) IS NOT NULL",
    )
    suspend fun copyMissingTitle(
        loser: Long,
        winner: Long,
    )

    @Query(
        "INSERT OR IGNORE INTO episode_state" +
            " SELECT :winner, startedAt, playedAt, playCount, lastPlayedAt, isFavorite," +
            " downloadDismissedAt, measuredDurationMs, updatedAt FROM episode_state WHERE episodeId = :loser",
    )
    suspend fun copyState(
        loser: Long,
        winner: Long,
    )

    @Query(
        "UPDATE episode_state SET" +
            " startedAt = COALESCE(MIN(startedAt, (SELECT startedAt FROM episode_state WHERE episodeId = :loser))," +
            " startedAt, (SELECT startedAt FROM episode_state WHERE episodeId = :loser))," +
            " playedAt = COALESCE(MAX(playedAt, (SELECT playedAt FROM episode_state WHERE episodeId = :loser))," +
            " playedAt, (SELECT playedAt FROM episode_state WHERE episodeId = :loser))," +
            " playCount = MAX(playCount, (SELECT playCount FROM episode_state WHERE episodeId = :loser))," +
            " lastPlayedAt = COALESCE(MAX(lastPlayedAt," +
            " (SELECT lastPlayedAt FROM episode_state WHERE episodeId = :loser))," +
            " lastPlayedAt, (SELECT lastPlayedAt FROM episode_state WHERE episodeId = :loser))," +
            " isFavorite = MAX(isFavorite, (SELECT isFavorite FROM episode_state WHERE episodeId = :loser))," +
            " downloadDismissedAt = COALESCE(MAX(downloadDismissedAt," +
            " (SELECT downloadDismissedAt FROM episode_state WHERE episodeId = :loser))," +
            " downloadDismissedAt, (SELECT downloadDismissedAt FROM episode_state WHERE episodeId = :loser))," +
            " measuredDurationMs = COALESCE(measuredDurationMs," +
            " (SELECT measuredDurationMs FROM episode_state WHERE episodeId = :loser))," +
            " updatedAt = MAX(updatedAt, (SELECT updatedAt FROM episode_state WHERE episodeId = :loser))" +
            " WHERE episodeId = :winner AND EXISTS (SELECT 1 FROM episode_state l WHERE l.episodeId = :loser AND (" +
            " (l.startedAt IS NOT NULL" +
            " AND (episode_state.startedAt IS NULL OR l.startedAt < episode_state.startedAt))" +
            " OR (l.playedAt IS NOT NULL AND (episode_state.playedAt IS NULL OR l.playedAt > episode_state.playedAt))" +
            " OR l.playCount > episode_state.playCount" +
            " OR (l.lastPlayedAt IS NOT NULL AND" +
            " (episode_state.lastPlayedAt IS NULL OR l.lastPlayedAt > episode_state.lastPlayedAt))" +
            " OR l.isFavorite > episode_state.isFavorite" +
            " OR (l.downloadDismissedAt IS NOT NULL AND" +
            " (episode_state.downloadDismissedAt IS NULL" +
            " OR l.downloadDismissedAt > episode_state.downloadDismissedAt))" +
            " OR (episode_state.measuredDurationMs IS NULL AND l.measuredDurationMs IS NOT NULL)" +
            " OR l.updatedAt > episode_state.updatedAt))",
    )
    suspend fun unionState(
        loser: Long,
        winner: Long,
    )

    @Query(
        "UPDATE episode_state SET startedAt = NULL" +
            " WHERE episodeId = :winner AND playedAt IS NOT NULL AND startedAt IS NOT NULL",
    )
    suspend fun clearPlayedStart(winner: Long)

    @Query(
        "INSERT OR IGNORE INTO episode_position" +
            " SELECT :winner, positionMs, durationMs, positionSource, updatedAt" +
            " FROM episode_position WHERE episodeId = :loser",
    )
    suspend fun copyPosition(
        loser: Long,
        winner: Long,
    )

    // A newer zero never erases progress; ties keep the winner's position (05 Merge rules).
    @Query(
        "UPDATE episode_position SET" +
            " positionMs = (SELECT positionMs FROM episode_position WHERE episodeId = :loser)," +
            " durationMs = (SELECT durationMs FROM episode_position WHERE episodeId = :loser)," +
            " positionSource = (SELECT positionSource FROM episode_position WHERE episodeId = :loser)," +
            " updatedAt = (SELECT updatedAt FROM episode_position WHERE episodeId = :loser)" +
            " WHERE episodeId = :winner AND EXISTS (SELECT 1 FROM episode_position l" +
            " WHERE l.episodeId = :loser AND l.positionMs > 0 AND l.updatedAt > episode_position.updatedAt)",
    )
    suspend fun mergePosition(
        loser: Long,
        winner: Long,
    )

    @Query(
        "UPDATE episode_position SET positionMs = 0," +
            " updatedAt = (SELECT playedAt FROM episode_state WHERE episodeId = :winner)" +
            " WHERE episodeId = :winner AND positionMs <> 0 AND updatedAt <" +
            " (SELECT playedAt FROM episode_state WHERE episodeId = :winner)",
    )
    suspend fun resetPlayedPosition(winner: Long)

    @Query(
        "INSERT OR IGNORE INTO episode_state(episodeId, startedAt, updatedAt)" +
            " SELECT episodeId, updatedAt, updatedAt FROM episode_position" +
            " WHERE episodeId = :winner AND positionMs > 0",
    )
    suspend fun ensureProgressState(winner: Long)

    @Query(
        "UPDATE episode_state SET startedAt = (SELECT updatedAt FROM episode_position WHERE episodeId = :winner)" +
            " WHERE episodeId = :winner AND playedAt IS NULL AND startedAt IS NULL AND EXISTS" +
            " (SELECT 1 FROM episode_position WHERE episodeId = :winner AND positionMs > 0)",
    )
    suspend fun deriveProgressStart(winner: Long)

    @Query("UPDATE OR IGNORE queue_entry SET episodeId = :winner WHERE episodeId = :loser")
    suspend fun moveQueue(
        loser: Long,
        winner: Long,
    )

    @Query("UPDATE OR IGNORE download SET episodeId = :winner WHERE episodeId = :loser")
    suspend fun moveDownload(
        loser: Long,
        winner: Long,
    )

    @Query("UPDATE play_session SET currentEpisodeId = :winner WHERE currentEpisodeId = :loser")
    suspend fun moveCurrentEpisode(
        loser: Long,
        winner: Long,
    )

    @Query("UPDATE episode SET podcastId = :winner, inFeed = 0 WHERE id = :episodeId")
    suspend fun reparentEpisode(
        episodeId: Long,
        winner: Long,
    )

    @Query("UPDATE play_session SET contextId = :winner WHERE contextType = 'PODCAST' AND contextId = :loser")
    suspend fun moveContext(
        loser: Long,
        winner: Long,
    )

    @Query("UPDATE import_item SET podcastId = :winner WHERE podcastId = :loser")
    suspend fun moveImportItems(
        loser: Long,
        winner: Long,
    )

    // Two statements preserve 02's sticky union on the SQLite 3.18 baseline (UPSERT needs 3.24).
    @Query(
        "INSERT OR IGNORE INTO episode_guid_provenance(podcastId, guid, knowledge)" +
            " SELECT :winner, guid, knowledge FROM episode_guid_provenance WHERE podcastId = :loser",
    )
    suspend fun copyProvenance(
        loser: Long,
        winner: Long,
    )

    @Query(
        "UPDATE episode_guid_provenance SET knowledge = 'KNOWN_AMBIGUOUS'" +
            " WHERE podcastId = :winner AND knowledge <> 'KNOWN_AMBIGUOUS' AND guid IN" +
            " (SELECT guid FROM episode_guid_provenance WHERE podcastId = :loser AND knowledge = 'KNOWN_AMBIGUOUS')",
    )
    suspend fun unionAmbiguity(
        loser: Long,
        winner: Long,
    )
}
