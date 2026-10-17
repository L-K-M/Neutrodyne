// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import kotlinx.coroutines.flow.Flow

/**
 * `import_session`/`import_item` (02; the 05 import pipeline, M3). An `abstract class` (like
 * `GroupDao`) so `IN (:ids)` lists chunk at [BIND_CHUNK] (02 Transactions and threading); the
 * caller owns the transaction of every multi-statement sequence. Status `IN` lists take the
 * enum and stay unchunked — bounded by its size.
 *
 * `@DaoReturnTypeConverters` must sit on the `@Dao` type for `pagedItems`'s `PagingSource`
 * return to generate on non-Android targets (same as `FeedDao`, spike S2).
 */
@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
abstract class ImportDao {
    @Insert
    abstract suspend fun insertSession(row: ImportSessionEntity): Long

    @Insert
    abstract suspend fun insertItems(rows: List<ImportItemEntity>)

    /** Every item of one session in file order (small reads; the UI pages [pagedItems] instead). */
    @Query("SELECT * FROM import_item WHERE sessionId = :sessionId ORDER BY ordinal")
    abstract suspend fun itemsOf(sessionId: Long): List<ImportItemEntity>

    /** The session row — the read side of `updateOptions`'s read-modify-write (05 Preview). */
    @Query("SELECT * FROM import_session WHERE id = :sessionId")
    abstract suspend fun sessionById(sessionId: Long): ImportSessionEntity?

    /** The preview/progress screen's session row (05 `ImportRepository.observeSession`). */
    @Query("SELECT * FROM import_session WHERE id = :sessionId")
    abstract fun observeSession(sessionId: Long): Flow<ImportSessionEntity?>

    /** 05 `observeOpenSessions()`: the resume banner's sessions, oldest first. */
    @Query(
        "SELECT * FROM import_session WHERE state IN ('PREVIEW', 'COMMITTED', 'FETCHING')" +
            " ORDER BY createdAt, id",
    )
    abstract fun observeOpenSessions(): Flow<List<ImportSessionEntity>>

    /** The fetch runner's work list (05 §7): sessions whose loop can run, oldest first. */
    @Query(
        "SELECT * FROM import_session WHERE state IN ('COMMITTED', 'FETCHING')" +
            " ORDER BY createdAt, id",
    )
    abstract suspend fun activeSessions(): List<ImportSessionEntity>

    /** 02 Import commit: per-status counts — the progress screen's "Fetched 87 of 139". */
    @Query(
        "SELECT status, COUNT(*) AS count FROM import_item WHERE sessionId = :sessionId" +
            " GROUP BY status",
    )
    abstract fun observeProgress(sessionId: Long): Flow<List<ImportStatusCount>>

    /**
     * The preview header's `ImportSessionView` tallies (05 §5): total, selected and YouTube
     * items in one observed read beside [observeProgress]'s per-status counts.
     */
    @Query(
        "SELECT COUNT(*) AS itemCount," +
            " COUNT(CASE WHEN selected = 1 THEN 1 END) AS selectedCount," +
            " COUNT(CASE WHEN kind = 'YOUTUBE' THEN 1 END) AS youtubeCount" +
            " FROM import_item WHERE sessionId = :sessionId",
    )
    abstract fun observeItemTallies(sessionId: Long): Flow<ImportItemTallies>

    /**
     * 02 Import commit: the preview and report lists, file order, filtered by 05's
     * `ImportItemFilter` → statuses table (the enum bounds the `IN` list; `ALL` passes every
     * status). Observes `import_item` via the `(sessionId, status)` index.
     */
    @Query(
        "SELECT * FROM import_item WHERE sessionId = :sessionId AND status IN (:statuses)" +
            " ORDER BY ordinal",
    )
    abstract fun pagedItems(
        sessionId: Long,
        statuses: List<ImportItemStatus>,
    ): PagingSource<Int, ImportItemEntity>

    /** 05 §6 commit (B5): the selected, still-`PREVIEW` items in file order. */
    @Query(
        "SELECT * FROM import_item WHERE sessionId = :sessionId AND selected = 1" +
            " AND status = 'PREVIEW' ORDER BY ordinal",
    )
    abstract suspend fun selectedItems(sessionId: Long): List<ImportItemEntity>

    /** The fetch loop and report's status-filtered sets (05 §7–8); the enum bounds `statuses`. */
    @Query(
        "SELECT * FROM import_item WHERE sessionId = :sessionId AND status IN (:statuses)" +
            " ORDER BY ordinal",
    )
    abstract suspend fun itemsByStatus(
        sessionId: Long,
        statuses: List<ImportItemStatus>,
    ): List<ImportItemEntity>

    /**
     * The fix-ups' input set (05 §8 `retry`/`remove` take ordinals). `IN` list chunked at
     * [BIND_CHUNK]; rows are ordinal-ordered within each chunk, so an unordered input list
     * does not come back globally sorted.
     */
    suspend fun itemsByOrdinals(
        sessionId: Long,
        ordinals: Collection<Int>,
    ): List<ImportItemEntity> =
        ordinals
            .distinct()
            .chunked(BIND_CHUNK)
            .flatMap { itemsByOrdinalsChunk(sessionId, it) }

    @Query(
        "SELECT * FROM import_item WHERE sessionId = :sessionId AND ordinal IN (:ordinals)" +
            " ORDER BY ordinal",
    )
    protected abstract suspend fun itemsByOrdinalsChunk(
        sessionId: Long,
        ordinals: List<Int>,
    ): List<ImportItemEntity>

    /** 05 §7 finish guard: "no item QUEUED → state = DONE". */
    @Query("SELECT COUNT(*) FROM import_item WHERE sessionId = :sessionId AND status = 'QUEUED'")
    abstract suspend fun queuedCount(sessionId: Long): Int

    // Selection writes (05 §5: per-row toggles and "select all/none"). All three write only
    // `PREVIEW` rows — 05's only selectable status — so `selected` shares [selectedItems]'
    // domain and `observeItemTallies.selectedCount` stays the committable count even when a
    // filter (or a group-resolved ordinal set) names non-selectable rows.

    @Query(
        "UPDATE import_item SET selected = :selected" +
            " WHERE sessionId = :sessionId AND ordinal = :ordinal" +
            " AND status = 'PREVIEW' AND selected <> :selected",
    )
    abstract suspend fun setSelected(
        sessionId: Long,
        ordinal: Int,
        selected: Boolean,
    ): Int

    /**
     * Group-level selection: `groupNamesJson` is JSON (02 forbids SQL inside it), so callers
     * resolve the ordinals in Kotlin; `IN` list chunked at [BIND_CHUNK].
     */
    suspend fun setSelectedOrdinals(
        sessionId: Long,
        ordinals: Collection<Int>,
        selected: Boolean,
    ): Int =
        ordinals
            .distinct()
            .chunked(BIND_CHUNK)
            .sumOf { setSelectedOrdinalsChunk(sessionId, it, selected) }

    @Query(
        "UPDATE import_item SET selected = :selected" +
            " WHERE sessionId = :sessionId AND ordinal IN (:ordinals)" +
            " AND status = 'PREVIEW' AND selected <> :selected",
    )
    protected abstract suspend fun setSelectedOrdinalsChunk(
        sessionId: Long,
        ordinals: List<Int>,
        selected: Boolean,
    ): Int

    /**
     * "Select all/none" of a filter: every `PREVIEW` item in the given statuses — the caller
     * passes the statuses its filter shows, which may list non-selectable ones (05's ALL and
     * NOT_IMPORTED do).
     */
    @Query(
        "UPDATE import_item SET selected = :selected" +
            " WHERE sessionId = :sessionId AND status IN (:statuses)" +
            " AND status = 'PREVIEW' AND selected <> :selected",
    )
    abstract suspend fun setSelectedByStatus(
        sessionId: Long,
        statuses: List<ImportItemStatus>,
        selected: Boolean,
    ): Int

    // Session writes. Guards per 02 DAO rule 2: identical writes would still invalidate observers.

    /** Non-terminal transitions: `confirm` → COMMITTED, runner → FETCHING, `cancel` → CANCELLED. */
    @Query("UPDATE import_session SET state = :state WHERE id = :sessionId AND state <> :state")
    abstract suspend fun setState(
        sessionId: Long,
        state: ImportState,
    ): Int

    /** 05 §7 finish: `DONE` stamps `finishedAt`, which opens the 7-day cleanup window (02). */
    @Query("UPDATE import_session SET state = 'DONE', finishedAt = :finishedAt WHERE id = :sessionId")
    abstract suspend fun markDone(
        sessionId: Long,
        finishedAt: Long,
    ): Int

    /** `updateOptions` and the commit/fetch bookkeeping inside `optionsJson` (05). */
    @Query(
        "UPDATE import_session SET optionsJson = :optionsJson" +
            " WHERE id = :sessionId AND optionsJson IS NOT :optionsJson",
    )
    abstract suspend fun setOptionsJson(
        sessionId: Long,
        optionsJson: String?,
    ): Int

    @Query(
        "UPDATE import_session SET warningsJson = :warningsJson" +
            " WHERE id = :sessionId AND warningsJson IS NOT :warningsJson",
    )
    abstract suspend fun setWarningsJson(
        sessionId: Long,
        warningsJson: String?,
    ): Int

    /** 05 §1: the payload file's `import/{id}.bin` cache-relative path, stored after the rename. */
    @Query(
        "UPDATE import_session SET payloadPath = :payloadPath" +
            " WHERE id = :sessionId AND payloadPath IS NOT :payloadPath",
    )
    abstract suspend fun setPayloadPath(
        sessionId: Long,
        payloadPath: String?,
    ): Int

    /** Android `OpenableColumns.DISPLAY_NAME` / desktop file name, learned at acquire time. */
    @Query(
        "UPDATE import_session SET sourceName = :sourceName" +
            " WHERE id = :sessionId AND sourceName IS NOT :sourceName",
    )
    abstract suspend fun setSourceName(
        sessionId: Long,
        sourceName: String?,
    ): Int

    // Item writes.

    /**
     * 02 Import commit step 5: one committed item becomes `QUEUED` with its new or existing
     * podcast (`podcastId` stays `null` while an M8 item awaits YouTube resolution).
     */
    @Query(
        "UPDATE import_item SET status = 'QUEUED', podcastId = :podcastId" +
            " WHERE sessionId = :sessionId AND ordinal = :ordinal",
    )
    abstract suspend fun markQueued(
        sessionId: Long,
        ordinal: Int,
        podcastId: Long?,
    ): Int

    /**
     * Fetch outcomes and fix-ups (05 §7–8): a final status plus `errorDetail` (the
     * `FeedErrorKind` name or `DEFERRED`; `null` clears). `podcastId` is never written —
     * 02's merge keeps it current.
     */
    @Query(
        "UPDATE import_item SET status = :status, errorDetail = :errorDetail" +
            " WHERE sessionId = :sessionId AND ordinal = :ordinal" +
            " AND (status <> :status OR errorDetail IS NOT :errorDetail)",
    )
    abstract suspend fun setItemStatus(
        sessionId: Long,
        ordinal: Int,
        status: ImportItemStatus,
        errorDetail: String?,
    ): Int

    /** Retry's bulk re-queue (05 §8): `IN` list chunked at [BIND_CHUNK]; `errorDetail` clears. */
    suspend fun requeueItems(
        sessionId: Long,
        ordinals: Collection<Int>,
    ): Int = ordinals.distinct().chunked(BIND_CHUNK).sumOf { requeueItemsChunk(sessionId, it) }

    @Query(
        "UPDATE import_item SET status = 'QUEUED', errorDetail = NULL" +
            " WHERE sessionId = :sessionId AND ordinal IN (:ordinals)" +
            " AND (status <> 'QUEUED' OR errorDetail IS NOT NULL)",
    )
    protected abstract suspend fun requeueItemsChunk(
        sessionId: Long,
        ordinals: List<Int>,
    ): Int

    /** 05 §6 step 3: commit rewrites `originalUrl` through `Redactor.url` — no password persists. */
    @Query(
        "UPDATE import_item SET originalUrl = :originalUrl" +
            " WHERE sessionId = :sessionId AND ordinal = :ordinal AND originalUrl <> :originalUrl",
    )
    abstract suspend fun setItemOriginalUrl(
        sessionId: Long,
        ordinal: Int,
        originalUrl: String,
    ): Int

    /** 05 §8 Remove: rows of podcasts this session created go after unsubscribing. Chunked. */
    suspend fun deleteItems(
        sessionId: Long,
        ordinals: Collection<Int>,
    ): Int = ordinals.distinct().chunked(BIND_CHUNK).sumOf { deleteItemsChunk(sessionId, it) }

    @Query("DELETE FROM import_item WHERE sessionId = :sessionId AND ordinal IN (:ordinals)")
    protected abstract suspend fun deleteItemsChunk(
        sessionId: Long,
        ordinals: List<Int>,
    ): Int

    // Session cleanup (02 Import commit; 05 §9's seven-day rule).

    /**
     * `DONE`/`CANCELLED` and abandoned `PREVIEW` sessions past the 7-day window — never
     * `COMMITTED`/`FETCHING`, which are live work. The caller deletes
     * `<cache>/{payloadPath}` first, then [deleteSession].
     */
    @Query(
        "SELECT id, payloadPath FROM import_session" +
            " WHERE state IN ('DONE', 'CANCELLED', 'PREVIEW')" +
            " AND COALESCE(finishedAt, createdAt) < :cutoff",
    )
    abstract suspend fun expiredSessions(cutoff: Long): List<ExpiredImportSession>

    /** The row delete; `import_item` rows cascade (02 FK). */
    @Query("DELETE FROM import_session WHERE id = :sessionId")
    abstract suspend fun deleteSession(sessionId: Long): Int

    private companion object {
        /** 02 Transactions and threading: `IN (:ids)` lists are chunked at 500 bound variables. */
        const val BIND_CHUNK = 500
    }
}

/** One row of [ImportDao.observeProgress]: a status and how many of the session's items hold it. */
data class ImportStatusCount(
    val status: ImportItemStatus,
    val count: Int,
)

/** [ImportDao.observeItemTallies]' row — the `ImportSessionView` header numbers (05 §5). */
data class ImportItemTallies(
    val itemCount: Int,
    val selectedCount: Int,
    val youtubeCount: Int,
)

/** [ImportDao.expiredSessions]' row: the expired session and its payload file (when stored). */
data class ExpiredImportSession(
    val id: Long,
    val payloadPath: String?,
)
