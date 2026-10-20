// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import kotlinx.coroutines.flow.Flow

/**
 * `play_session`: the `id = 0` singleton (02 play_session). [ensure] runs in `Callback.onCreate`
 * and as the first statement of 06's `SessionWriter` and 05's restore transactions — an ignored
 * insert fires no Room trigger. Every other write is an `UPDATE … WHERE id = 0`.
 */
@Dao
interface PlaySessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(row: PlaySessionEntity): Long

    /** Convenience for callers that create the row: `ensure(PlaySessionEntity(updatedAt = now))`. */
    @Query("INSERT OR IGNORE INTO play_session(id, generation, updatedAt) VALUES (0, 0, :now)")
    suspend fun ensureNow(now: Long): Long

    @Query("SELECT * FROM play_session WHERE id = 0")
    suspend fun get(): PlaySessionEntity?

    /** 07's deferral check and the now-playing sources (02 Downloads): low-churn observation. */
    @Query("SELECT currentEpisodeId FROM play_session WHERE id = 0")
    fun observeCurrentEpisodeId(): Flow<Long?>

    /**
     * 05 Replace pre-step (UP_NEXT): the local context is cleared before the backup's session
     * is written. Same shape as the scoped `clear*Context` statements — `generation + 1`, so a
     * session write elsewhere is never clobbered.
     */
    @Query(
        "UPDATE play_session SET contextType = NULL, contextId = NULL, contextAnchorEpisodeId = NULL," +
            " contextAnchorSortDate = NULL, generation = generation + 1, updatedAt = :now WHERE id = 0",
    )
    suspend fun clearContext(now: Long): Int

    /**
     * 05 transaction C: the backup's session lands — current episode, context (already
     * resolved to local ids by the caller) and the context columns — `generation + 1` like
     * every session write. Merge restores only while the local `currentEpisodeId` is free
     * (`:onlyWhenIdle`); Replace takes the backup's unconditionally. `updatedAt` is a
     * device-local column (02 play_session), so it stamps `now`, not a backup timestamp.
     */
    @Query(
        "UPDATE play_session SET currentEpisodeId = :currentEpisodeId, contextType = :contextType," +
            " contextId = :contextId, contextOrder = :contextOrder," +
            " contextFilterFlags = :contextFilterFlags, contextMediaFilter = :contextMediaFilter," +
            " contextMinSortDate = :contextMinSortDate, contextAnchorEpisodeId = :contextAnchorEpisodeId," +
            " contextAnchorSortDate = :contextAnchorSortDate, generation = generation + 1," +
            " updatedAt = :now" +
            " WHERE id = 0 AND (:onlyWhenIdle = 0 OR currentEpisodeId IS NULL)",
    )
    suspend fun restoreSession(
        currentEpisodeId: Long?,
        contextType: ContextType?,
        contextId: Long?,
        contextOrder: FeedOrder,
        contextFilterFlags: Int,
        contextMediaFilter: MediaFilter,
        contextMinSortDate: Long?,
        contextAnchorEpisodeId: Long?,
        contextAnchorSortDate: Long?,
        onlyWhenIdle: Boolean,
        now: Long,
    ): Int

    /** Session fields are one atomic record; writers supply generation + 1 inside a transaction. */
    @Update
    suspend fun write(row: PlaySessionEntity)
}
