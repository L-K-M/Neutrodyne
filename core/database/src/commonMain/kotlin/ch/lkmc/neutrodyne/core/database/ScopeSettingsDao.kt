// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Left joins retain ungrouped podcasts and scopes that have no override row. */
private const val PODCAST_SCOPES = """
    SELECT p.id AS podcastId,
        ps.refreshIntervalMinutes AS podcastRefresh, ps.notifyNewEpisodes AS podcastNotify,
        ps.playbackSpeed AS podcastSpeed, ps.skipSilence AS podcastSkipSilence,
        g.id AS groupId, g.uuid AS groupUuid, g.name AS groupName,
        gs.refreshIntervalMinutes AS groupRefresh, gs.notifyNewEpisodes AS groupNotify,
        gs.playbackSpeed AS groupSpeed, gs.skipSilence AS groupSkipSilence
    FROM podcast p
    LEFT JOIN podcast_settings ps ON ps.podcastId = p.id
    LEFT JOIN podcast_group_member m ON m.podcastId = p.id
    LEFT JOIN podcast_group g ON g.id = m.groupId
    LEFT JOIN podcast_group_settings gs ON gs.groupId = g.id
"""
private const val SCOPE_ORDER = " ORDER BY p.id, g.orderKey, g.id"

/** Resolution is one statement per podcast or batch, with no relation-triggered N+1 reads. */
@Dao
abstract class ScopeSettingsDao {
    @Upsert abstract suspend fun upsertPodcast(row: PodcastSettingsEntity)

    @Upsert abstract suspend fun upsertGroup(row: PodcastGroupSettingsEntity)

    @Query("SELECT * FROM podcast_settings WHERE podcastId = :podcastId")
    abstract suspend fun forPodcast(podcastId: Long): PodcastSettingsEntity?

    @Query("SELECT * FROM podcast_group_settings WHERE groupId = :groupId")
    abstract suspend fun forGroup(groupId: Long): PodcastGroupSettingsEntity?

    @Query("DELETE FROM podcast_settings WHERE podcastId = :podcastId")
    abstract suspend fun deletePodcast(podcastId: Long)

    @Query("DELETE FROM podcast_group_settings WHERE groupId = :groupId")
    abstract suspend fun deleteGroup(groupId: Long)

    /**
     * 02 Transactions and threading: `IN (:ids)` lists are chunked at [BIND_CHUNK]. IDs are
     * distinct, so all of one podcast's rows (in group order) stay inside one chunk.
     */
    suspend fun podcastScopes(podcastIds: Collection<Long>): List<PodcastScopeRow> =
        podcastIds.distinct().chunked(BIND_CHUNK).flatMap { podcastScopesChunk(it) }

    @Query(PODCAST_SCOPES + " WHERE p.id IN (:podcastIds)" + SCOPE_ORDER)
    protected abstract suspend fun podcastScopesChunk(podcastIds: List<Long>): List<PodcastScopeRow>

    @Query(PODCAST_SCOPES + SCOPE_ORDER)
    abstract suspend fun allPodcastScopes(): List<PodcastScopeRow>

    @Query(PODCAST_SCOPES + " WHERE p.id = :podcastId" + SCOPE_ORDER)
    abstract fun observePodcastScope(podcastId: Long): Flow<List<PodcastScopeRow>>

    /** The observed variant of [podcastScopes]: one Room flow per chunk, re-emitting as one list. */
    fun observePodcastScopes(podcastIds: Collection<Long>): Flow<List<PodcastScopeRow>> {
        val chunks = podcastIds.distinct().chunked(BIND_CHUNK).map { observePodcastScopesChunk(it) }
        if (chunks.isEmpty()) return flowOf(emptyList())

        return combine(chunks) { rows -> rows.flatMap { it } }
    }

    @Query(PODCAST_SCOPES + " WHERE p.id IN (:podcastIds)" + SCOPE_ORDER)
    protected abstract fun observePodcastScopesChunk(podcastIds: List<Long>): Flow<List<PodcastScopeRow>>

    @Query(PODCAST_SCOPES + SCOPE_ORDER)
    abstract fun observeAllPodcastScopes(): Flow<List<PodcastScopeRow>>

    @Query(
        """
        SELECT g.id AS groupId, g.uuid AS uuid, g.name AS name,
            gs.playbackSpeed AS playbackSpeed, gs.skipSilence AS skipSilence,
            gs.refreshIntervalMinutes AS refreshIntervalMinutes, gs.notifyNewEpisodes AS notifyNewEpisodes
        FROM podcast_group g LEFT JOIN podcast_group_settings gs ON gs.groupId = g.id
        WHERE g.id = :groupId
    """,
    )
    abstract fun observeGroupScope(groupId: Long): Flow<GroupScopeRow?>

    /** Channel reconciliation includes empty groups and groups without an override row. */
    @Query(
        "SELECT g.id AS groupId, g.uuid, g.name, gs.refreshIntervalMinutes, gs.notifyNewEpisodes," +
            " gs.playbackSpeed, gs.skipSilence" +
            " FROM podcast_group g LEFT JOIN podcast_group_settings gs ON gs.groupId = g.id",
    )
    abstract suspend fun groupScopes(): List<GroupScopeRow>

    private companion object {
        /** 02 Transactions and threading: `IN (:ids)` lists are chunked at 500 bound variables. */
        const val BIND_CHUNK = 500
    }
}

data class PodcastScopeRow(
    val podcastId: Long,
    val podcastRefresh: Int?,
    val podcastNotify: Boolean?,
    val groupUuid: String?,
    val groupName: String?,
    val groupRefresh: Int?,
    val groupNotify: Boolean?,
    val groupId: Long?,
    val podcastSpeed: Float?,
    val podcastSkipSilence: Boolean?,
    val groupSpeed: Float?,
    val groupSkipSilence: Boolean?,
)

data class GroupScopeRow(
    val uuid: String,
    val name: String,
    val refreshIntervalMinutes: Int?,
    val notifyNewEpisodes: Boolean?,
    val playbackSpeed: Float? = null,
    val skipSilence: Boolean? = null,
    val groupId: Long = 0,
)
