// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import dev.zacsweers.metro.Inject

/** URL/key/alias writes share the ingest writer; collisions leave it before credential cleanup. */
@Inject
internal class FeedMoveCommitter(
    private val db: NeutrodyneDatabase,
    private val merger: PodcastMerger,
    private val clock: Clock,
) {
    /** Advisory only: decide whether to flush batches; all writes recheck under the writer. */
    suspend fun willMove(
        podcastId: Long,
        permanentUrl: String? = null,
    ): Boolean {
        val stored = db.podcastDao().byId(podcastId) ?: return false
        if (stored.sourceType != SourceType.RSS) return false
        val target = permanentUrl ?: stored.feedUrl
        val key = UrlNormalizer.forIdentity(target) ?: return false
        return target != stored.feedUrl || key != stored.feedKey
    }

    /** Called only under the writer, with a permanent target only after an accepted parse. */
    suspend fun applyInTransaction(
        stored: PodcastEntity,
        permanentUrl: String?,
        reason: AliasReason = if (permanentUrl != null) AliasReason.REDIRECT else AliasReason.RENORMALISED,
    ): PodcastEntity {
        if (stored.sourceType != SourceType.RSS) return stored

        val target = permanentUrl ?: stored.feedUrl
        val key = UrlNormalizer.forIdentity(target) ?: return stored
        if (target == stored.feedUrl && key == stored.feedKey) return stored

        val podcasts = db.podcastDao()
        val owner = podcasts.byFeedKey(key)?.id ?: podcasts.aliasOwner(key)
        if (owner != null && owner != stored.id) throw FeedMoveCollision(key)

        podcasts.deleteAlias(stored.id, key)
        podcasts.moveFeed(stored.id, target, key)
        if (key != stored.feedKey) {
            podcasts.insertAlias(
                PodcastUrlAliasEntity(
                    url = stored.feedKey,
                    podcastId = stored.id,
                    reason = reason,
                    addedAt = clock.now(),
                ),
            )
        }
        return stored.copy(feedUrl = target, feedKey = key, etag = null, lastModified = null, contentSha256 = null)
    }

    /** Neither a Room transaction nor credential coordination may be held by this caller. */
    suspend fun mergeCollision(
        podcastId: Long,
        collision: FeedMoveCollision,
    ): DueFeed? {
        val owner = merger.mergeIntoKeyOwner(podcastId, collision.key) ?: return null
        return db.podcastDao().dueFeedById(owner)
    }

    /** 304 and Unchanged are successes too, but provide no evidence for redirect adoption. */
    suspend fun renormalise(podcastId: Long): FeedMoveResult {
        while (true) {
            try {
                val feed =
                    db.withWriteTransaction {
                        val stored = db.podcastDao().byId(podcastId) ?: return@withWriteTransaction null
                        val updated = applyInTransaction(stored, permanentUrl = null)
                        FeedMoveResult(
                            db.podcastDao().dueFeedById(podcastId),
                            moved = updated.feedKey != stored.feedKey,
                        )
                    }
                return feed ?: FeedMoveResult(null)
            } catch (collision: FeedMoveCollision) {
                val survivor = mergeCollision(podcastId, collision) ?: continue
                return FeedMoveResult(survivor, mergedInto = survivor.id)
            }
        }
    }
}

/** Unwinds the attempted ingest without writes so the merger can acquire locks in order. */
internal class FeedMoveCollision(
    val key: String,
) : Exception("Feed key is already owned: $key")

internal data class FeedMoveResult(
    val feed: DueFeed?,
    val mergedInto: Long? = null,
    val moved: Boolean = false,
)
