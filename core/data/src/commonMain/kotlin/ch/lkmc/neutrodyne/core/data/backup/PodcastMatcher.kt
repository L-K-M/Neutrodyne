// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * 02 Restore matching's podcast ladder and the syncId adoption it documents — restore (05
 * transaction A) and sync's same-feed-collision path share it. Precedence is strict:
 * `feedKey`/alias evidence first (one ranked query), then the real `podcast:guid`; a stronger
 * step's hit makes a weaker step's evidence irrelevant, and a derived GUID is never evidence.
 */
@Inject
internal class PodcastMatcher(
    private val db: NeutrodyneDatabase,
) {
    /**
     * The local podcast the backup line claims (02 steps 1–2): `[feedKey]` + [aliases] are the
     * backup's identity set against local `feedKey`s and `podcast_url_alias`es, ranked; only
     * then the real [podcastGuid]. Null = no local podcast — the caller inserts (05 step 3).
     */
    suspend fun match(
        feedKey: String,
        aliases: List<String>,
        podcastGuid: String?,
    ): Long? {
        db.backupDao().matchPodcastByKeys(feedKey, listOf(feedKey) + aliases)?.let { return it }
        return podcastGuid?.let { db.backupDao().matchPodcastByRealGuid(it) }
    }

    /** 02 Group match: `uuid` first, then `nameKey`. */
    suspend fun matchGroup(
        uuid: String?,
        nameKey: String,
    ): Long? =
        uuid?.let { db.backupDao().matchGroupByUuid(it) }
            ?: db.groupDao().groupByNameKey(nameKey)?.id

    /**
     * 02 Podcast syncId: the backup's `syncId` for an inserted podcast when no local row uses
     * it; a collision (or an absent backup id) gets a fresh UUIDv4.
     */
    suspend fun syncIdForInsert(backupSyncId: String?): String =
        backupSyncId
            ?.takeUnless { db.backupDao().syncIdInUse(it) }
            ?: Uuid.random().toString()
}
