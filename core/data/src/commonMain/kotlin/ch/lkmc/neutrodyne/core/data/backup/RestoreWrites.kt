// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.EpisodeEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.PositionSource
import dev.zacsweers.metro.Inject

private const val NO_STATE_TS = 0L

/** 02's stub `sortDate`: `MIN(pubDate, now + 24 h)` — a future-dated stub sorts no newer than a day out. */
private const val SORT_DATE_MAX_FUTURE_MS = 86_400_000L

/**
 * The per-episode write primitives of 02 Restore matching that `RestoreMerger`'s transactions
 * B/C apply per matched or stubbed line (05 Merge and Replace rules; B10/B11 call these).
 * Restore timestamps: every row written stores the backup's `ts`/`posAt` as `updatedAt`,
 * never the restore's `now`; a kept local value keeps its own `updatedAt`.
 *
 * Callers write state before position for a line (05 step 5's order) — the primitives rely
 * on it: `replaceState` is `INSERT OR REPLACE` (delete-and-insert), so a position write
 * first would lose the in-progress proxy row `finishPosition` derived when the verbatim
 * state row lands; and the post rules read `episode_state.playedAt`, so a merge position
 * write must see the merged state, not the pre-merge one.
 */
@Inject
internal class RestoreWrites(
    private val db: NeutrodyneDatabase,
) {
    private val backupDao get() = db.backupDao()
    private val mergeDao get() = db.podcastMergeDao()
    private val stateDao get() = db.episodeStateDao()
    private val episodeDao get() = db.episodeDao()
    private val queueDao get() = db.queueDao()

    /**
     * 05 Played (Merge): played OR (`playedAt`/`playCount`/`lastPlayedAt` = max), favourite
     * and tombstone OR, `startedAt` = earliest non-null, `measuredDurationMs` = local first.
     * A `ts = 0` line carries no state fields — nothing is written (05).
     */
    suspend fun mergeState(
        episodeId: Long,
        state: RestoreStateValues,
    ) {
        if (state.ts == NO_STATE_TS) return
        // The lazy-row ensure stamped `ts`: a state row the restore creates on backup evidence
        // carries the backup's clock, never now (02 Restore matching).
        stateDao.ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = state.ts))
        backupDao.mergeState(
            episodeId = episodeId,
            startedAt = state.startedAt,
            playedAt = state.playedAt,
            playCount = state.playCount,
            lastPlayedAt = state.lastPlayedAt,
            isFavorite = state.isFavorite,
            downloadDismissedAt = state.downloadDismissedAt,
            measuredDurationMs = state.measuredDurationMs,
            ts = state.ts,
        )
        // `startedAt` merged as earliest non-null above; a played result clears it (05).
        mergeDao.clearPlayedStart(episodeId)
    }

    /**
     * 05 Position (Merge): `INSERT OR IGNORE` then the newer-`updatedAt`-wins UPDATE — a
     * backup 0 or null never replaces a local non-zero position (N1), so a non-positive
     * [position] writes no position at all. The post rules run on the merged result
     * regardless: a pos-0 line over a row `mergeState` just marked played still resets
     * its older stored position to `0@playedAt`, and an unplayed result whose local
     * position survived still derives its in-progress proxy.
     */
    suspend fun mergePosition(
        episodeId: Long,
        position: RestorePositionValues,
    ) {
        if (position.positionMs > 0) {
            backupDao.ensurePositionForMerge(
                episodeId,
                position.positionMs,
                position.durationMs,
                position.positionSource,
                position.posAt,
            )
            backupDao.mergePosition(
                episodeId,
                position.positionMs,
                position.durationMs,
                position.positionSource,
                position.posAt,
            )
        }
        finishPosition(episodeId)
    }

    /**
     * 05 Played (Replace): the backup's state row verbatim — the Replace pre-step deleted the
     * local one. A `ts = 0` line means unplayed: no state field written (05). One column is
     * not verbatim: `clearPlayedStart` nulls `startedAt` on a played result, so a played
     * foreign row drops the in-progress marker it carried (played ⇒ no progress, 05).
     */
    suspend fun replaceState(
        episodeId: Long,
        state: RestoreStateValues,
    ) {
        if (state.ts == NO_STATE_TS) return
        backupDao.replaceState(
            episodeId = episodeId,
            startedAt = state.startedAt,
            playedAt = state.playedAt,
            playCount = state.playCount,
            lastPlayedAt = state.lastPlayedAt,
            isFavorite = state.isFavorite,
            downloadDismissedAt = state.downloadDismissedAt,
            measuredDurationMs = state.measuredDurationMs,
            ts = state.ts,
        )
        mergeDao.clearPlayedStart(episodeId)
    }

    /** 05 Position (Replace): the backup's position verbatim, then the same post rules. */
    suspend fun replacePosition(
        episodeId: Long,
        position: RestorePositionValues,
    ) {
        if (position.positionMs <= 0) return
        backupDao.replacePosition(
            episodeId,
            position.positionMs,
            position.durationMs,
            position.positionSource,
            position.posAt,
        )
        finishPosition(episodeId)
    }

    /**
     * The shared post-position rules (05 Position): mark-played semantics reset a position
     * older than the merged played mark (`updatedAt` = `playedAt`), and a non-zero, unplayed
     * result derives its in-progress proxy — `ensure(id, posAt)` for a missing row,
     * `startedAt = COALESCE(startedAt, posAt)` for an existing one whose `updatedAt` stays.
     */
    private suspend fun finishPosition(episodeId: Long) {
        // Projection cleanup is not a new reset action and must not replace a literal capture.
        db.withWriteTransaction {
            db.syncStateDao().withApplying {
                mergeDao.resetPlayedPosition(episodeId)
                mergeDao.ensureProgressState(episodeId)
                mergeDao.deriveProgressStart(episodeId)
            }
        }
    }

    /**
     * 02 Restore matching's stub (05 Stubs and matching): `INSERT OR IGNORE` of the line's
     * feed columns as an `inFeed = false` row the next ingest completes by identity key,
     * enclosure or guid — `contentHash = 0` guarantees that diff updates it. An ignored
     * insert (a repeated line or a row refresh committed first) resolves by `(podcastId,
     * identityKey)` and is treated as matched; null only when even that lookup finds nothing.
     */
    suspend fun insertStub(
        podcastId: Long,
        stub: RestoreStubFields,
        now: Long,
    ): Long? {
        val id = episodeDao.insertStub(stub.toEntity(podcastId, now))
        if (id != -1L) return id
        return episodeDao.byIdentityKey(podcastId, stub.identityKey)?.id
    }

    /**
     * D98's restore rule (05 `PodcastV1.guidAmbiguous`): unions the backup's ambiguous-GUID
     * set into `KNOWN_AMBIGUOUS` — the only provenance a backup carries. A restore hint can
     * never mint `KNOWN_INDEPENDENT` (the call shape has no independent set), and a stored
     * ambiguity is never demoted (`IngestDao.recordGuidKnowledge`'s sticky union).
     */
    suspend fun recordAmbiguousGuids(
        podcastId: Long,
        guids: Set<String>,
    ) {
        if (guids.isEmpty()) return
        db.ingestDao().recordGuidKnowledge(podcastId, guids, emptySet(), emptySet())
    }

    /**
     * Up next (05): one queued insert per resolved backup ref in archive order — [orderKey]
     * and [addedAt] come from the caller (`OrderKey.after(last)`/backup `ok`; the archive's
     * timestamps). An already-queued episode keeps its entry (OR IGNORE).
     */
    suspend fun insertQueueEntry(
        episodeId: Long,
        orderKey: String,
        addedAt: Long,
    ): Boolean = queueDao.insert(QueueEntryEntity(episodeId = episodeId, orderKey = orderKey, addedAt = addedAt)) != -1L
}

/** The `episode_state` columns an episode line carries (05 `EpisodeLineV1`'s pl/pc/st/lp/fav/dis/md + `ts`). */
internal data class RestoreStateValues(
    val playedAt: Long?,
    val playCount: Int,
    val startedAt: Long?,
    val lastPlayedAt: Long?,
    val isFavorite: Boolean,
    val downloadDismissedAt: Long?,
    val measuredDurationMs: Long?,
    /** `episode_state.updatedAt` on the source; `0` is 05's sentinel for "no state fields". */
    val ts: Long,
)

/** The `episode_position` columns a line carries (`pos`/`dur`/`posSrc`/`posAt`). */
internal data class RestorePositionValues(
    val positionMs: Long,
    val durationMs: Long?,
    val positionSource: PositionSource,
    val posAt: Long,
)

/** The stub's feed columns (02's stub SQL: `k`/`g`/`t`/`d`/`u`/`ty`/`dur`/`yt`/`l`). */
internal data class RestoreStubFields(
    val identityKey: String,
    val guid: String? = null,
    val title: String,
    val pubDate: Long? = null,
    val enclosureUrl: String? = null,
    val enclosureType: String? = null,
    val durationMs: Long? = null,
    val externalMediaId: String? = null,
    val link: String? = null,
) {
    fun toEntity(
        podcastId: Long,
        now: Long,
    ): EpisodeEntity =
        EpisodeEntity(
            podcastId = podcastId,
            identityKey = identityKey,
            guid = guid,
            title = title,
            pubDate = pubDate,
            sortDate = minOf(pubDate ?: now, now + SORT_DATE_MAX_FUTURE_MS),
            feedOrder = 0,
            firstSeenAt = now,
            lastSeenAt = now,
            inFeed = false,
            isNew = false,
            enclosureUrl = enclosureUrl,
            enclosureType = enclosureType,
            durationMs = durationMs,
            externalMediaId = externalMediaId,
            link = link,
            contentHash = 0,
            availability = Availability.AVAILABLE,
            isShort = false,
        )
}
