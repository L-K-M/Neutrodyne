// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.ChapterSource

/**
 * `chapter` writes (02 chapter): writers replace all rows of one `(episodeId, source)` pair in one
 * transaction. M1a writes `PSC` rows from ingest; P2.0 JSON, ID3, MP4 and YouTube-description
 * sources reuse [replace] from M5.
 */
@Dao
abstract class ChapterDao(
    private val db: NeutrodyneDatabase,
) {
    suspend fun replace(
        episodeId: Long,
        source: ChapterSource,
        rows: List<ChapterEntity>,
    ) {
        db.withWriteTransaction {
            // The pair identity is the method contract: rows for another episode or source must
            // fail loudly here instead of silently landing under the wrong key.
            require(rows.all { it.episodeId == episodeId && it.source == source }) {
                "chapter rows must match the replaced (episodeId, source) pair"
            }
            deleteOfSource(episodeId, source.name)
            insert(rows)
        }
    }

    @Query("DELETE FROM chapter WHERE episodeId = :episodeId AND source = :source")
    protected abstract suspend fun deleteOfSource(
        episodeId: Long,
        source: String,
    )

    @Insert
    protected abstract suspend fun insert(rows: List<ChapterEntity>)

    @Query("SELECT * FROM chapter WHERE episodeId = :episodeId AND source = :source ORDER BY ordinal")
    abstract suspend fun ofSource(
        episodeId: Long,
        source: ChapterSource,
    ): List<ChapterEntity>
}
