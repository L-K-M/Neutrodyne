// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
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
}
