// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Left joins retain ungrouped podcasts and scopes that have no override row. */
private const val PODCAST_SCOPES = """
    SELECT p.id AS podcastId,
        ps.refreshIntervalMinutes AS podcastRefresh, ps.notifyNewEpisodes AS podcastNotify,
        g.uuid AS groupUuid, g.name AS groupName,
        gs.refreshIntervalMinutes AS groupRefresh, gs.notifyNewEpisodes AS groupNotify
    FROM podcast p
    LEFT JOIN podcast_settings ps ON ps.podcastId = p.id
    LEFT JOIN podcast_group_member m ON m.podcastId = p.id
    LEFT JOIN podcast_group g ON g.id = m.groupId
    LEFT JOIN podcast_group_settings gs ON gs.groupId = g.id
"""
private const val SCOPE_ORDER = " ORDER BY p.id, g.orderKey, g.id"

/** Resolution is one statement per podcast or batch, with no relation-triggered N+1 reads. */
@Dao
interface ScopeSettingsDao {
    @Upsert suspend fun upsertPodcast(row: PodcastSettingsEntity)

    @Upsert suspend fun upsertGroup(row: PodcastGroupSettingsEntity)

    @Query("SELECT * FROM podcast_settings WHERE podcastId = :podcastId")
    suspend fun forPodcast(podcastId: Long): PodcastSettingsEntity?

    @Query("SELECT * FROM podcast_group_settings WHERE groupId = :groupId")
    suspend fun forGroup(groupId: Long): PodcastGroupSettingsEntity?

    @Query("DELETE FROM podcast_settings WHERE podcastId = :podcastId")
    suspend fun deletePodcast(podcastId: Long)

    @Query("DELETE FROM podcast_group_settings WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: Long)

    @Query(PODCAST_SCOPES + " WHERE p.id IN (:podcastIds)" + SCOPE_ORDER)
    suspend fun podcastScopes(podcastIds: Collection<Long>): List<PodcastScopeRow>

    @Query(PODCAST_SCOPES + SCOPE_ORDER)
    suspend fun allPodcastScopes(): List<PodcastScopeRow>

    @Query(PODCAST_SCOPES + " WHERE p.id = :podcastId" + SCOPE_ORDER)
    fun observePodcastScope(podcastId: Long): Flow<List<PodcastScopeRow>>

    @Query(PODCAST_SCOPES + " WHERE p.id IN (:podcastIds)" + SCOPE_ORDER)
    fun observePodcastScopes(podcastIds: Collection<Long>): Flow<List<PodcastScopeRow>>

    @Query(PODCAST_SCOPES + SCOPE_ORDER)
    fun observeAllPodcastScopes(): Flow<List<PodcastScopeRow>>

    @Query(
        """
        SELECT g.uuid AS uuid, g.name AS name,
            gs.refreshIntervalMinutes AS refreshIntervalMinutes, gs.notifyNewEpisodes AS notifyNewEpisodes
        FROM podcast_group g LEFT JOIN podcast_group_settings gs ON gs.groupId = g.id
        WHERE g.id = :groupId
    """,
    )
    fun observeGroupScope(groupId: Long): Flow<GroupScopeRow?>
}

data class PodcastScopeRow(
    val podcastId: Long,
    val podcastRefresh: Int?,
    val podcastNotify: Boolean?,
    val groupUuid: String?,
    val groupName: String?,
    val groupRefresh: Int?,
    val groupNotify: Boolean?,
)

data class GroupScopeRow(
    val uuid: String,
    val name: String,
    val refreshIntervalMinutes: Int?,
    val notifyNewEpisodes: Boolean?,
)
