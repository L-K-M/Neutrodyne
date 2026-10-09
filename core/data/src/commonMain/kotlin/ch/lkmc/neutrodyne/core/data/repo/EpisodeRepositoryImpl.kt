// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.EpisodeDetailRow
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `EpisodeRepository` (03's interface): the detail/show-notes reads and 02's user-state write
 * chains — mark-played (`ensureAll` + `markPlayed` + position reset + Up-next removal) and
 * mark-unplayed (`markUnplayed` + reset), one transaction, `IN` lists chunked at 500 (02 DAO
 * rule).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class EpisodeRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val sanitizer: ShowNotesSanitizer,
    private val clock: Clock,
) : EpisodeRepository {
    /** "decoded and sanitised lazily (LRU 16)" (03): keyed by episode id. */
    private val notesMutex = Mutex()
    private val notesCache = LinkedHashMap<Long, ShowNotes>(SHOW_NOTES_CACHE_SIZE + 1)

    override fun observeEpisode(episodeId: Long): Flow<EpisodeDetail?> =
        db.episodeDao().observeDetail(episodeId).map { it?.toDetail() }

    override fun observeShowNotes(episodeId: Long): Flow<ShowNotes?> =
        combine(
            db.episodeDao().observeDetail(episodeId),
            db.episodeDao().observeDescription(episodeId),
        ) { detail, bytes ->
            bytes ?: return@combine null
            notesMutex.withLock {
                notesCache.remove(episodeId)?.also { notesCache[episodeId] = it }
            } ?: decode(episodeId, bytes, detail?.link ?: detail?.feedUrl ?: "")
        }

    override suspend fun setPlayed(
        episodeIds: List<Long>,
        played: Boolean,
    ) {
        val now = clock.now()
        db.withWriteTransaction {
            for (chunk in episodeIds.chunked(BIND_CHUNK)) {
                if (played) {
                    markPlayedChunk(chunk, now)
                } else {
                    db.episodeStateDao().markUnplayed(chunk, now)
                    db.positionDao().reset(chunk, now)
                }
            }
        }
    }

    /** R2.6's bulk mark-played: the confirmation count and marked rows share one predicate (02). */
    override suspend fun markFeedPlayed(
        source: FeedSource,
        sortDateBefore: Long?,
    ) {
        val now = clock.now()
        db.withWriteTransaction {
            val ids = db.episodeDao().unplayedIds(FeedQueryBuilder.unplayedIds(source, sortDateBefore))
            for (chunk in ids.chunked(BIND_CHUNK)) markPlayedChunk(chunk, now)
        }
    }

    override suspend fun setFavorite(
        episodeId: Long,
        favorite: Boolean,
    ) {
        val now = clock.now()
        db.withWriteTransaction {
            db.episodeStateDao().ensure(EpisodeStateEntity(episodeId = episodeId, updatedAt = now))
            db.episodeStateDao().setFavorite(episodeId, favorite, now)
        }
    }

    /** 02's mark-played chain for one ≤ 500-id chunk, inside the caller's transaction. */
    private suspend fun markPlayedChunk(
        ids: List<Long>,
        now: Long,
    ) {
        db.episodeStateDao().ensureAll(ids, now)
        db.episodeStateDao().markPlayed(ids, now)
        db.positionDao().reset(ids, now)
        db.queueDao().removeEpisodes(ids)
    }

    /** Decode → sanitise → model, then remember under the LRU cap. */
    private suspend fun decode(
        episodeId: Long,
        bytes: ByteArray,
        baseUri: String,
    ): ShowNotes {
        val notes =
            sanitizer
                .toDocument(EpisodeDescriptionCodec.decode(bytes), isHtml = true, baseUri = baseUri)
                .toModel()
        notesMutex.withLock {
            notesCache.remove(episodeId)
            notesCache[episodeId] = notes
            while (notesCache.size > SHOW_NOTES_CACHE_SIZE) {
                notesCache.remove(notesCache.keys.first())
            }
        }
        return notes
    }

    private fun EpisodeDetailRow.toDetail(): EpisodeDetail =
        EpisodeDetail(
            id = id,
            podcastId = podcastId,
            podcastTitle = podcastTitle,
            title = title,
            pubDate = pubDate,
            durationMs = durationMs,
            artwork = ArtworkRef(artworkKey, artworkUrl, artworkVersion),
            isVideo = isVideo,
            sourceType = sourceType,
            externalMediaId = externalMediaId,
            availability = availability,
            episodeDisplay = episodeDisplay,
            link = link,
            playedAt = playedAt,
            isFavorite = isFavorite,
            downloadState = downloadState,
        )

    private companion object {
        /** 02 Transactions and threading: `IN (:ids)` lists are chunked at 500 bound variables. */
        const val BIND_CHUNK = 500
        const val SHOW_NOTES_CACHE_SIZE = 16
    }
}
