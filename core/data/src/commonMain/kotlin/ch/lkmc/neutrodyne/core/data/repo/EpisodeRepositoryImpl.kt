// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.playback.EpisodeIntent
import ch.lkmc.neutrodyne.core.data.playback.captureEpisodeIntents
import ch.lkmc.neutrodyne.core.data.playback.markEpisodesPlayed
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.EpisodeDetailRow
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.MarkFeedPlayedResult
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
    /**
     * "decoded and sanitised lazily (LRU 16)" (03): keyed by episode id, and a hit requires every
     * input the sanitised document depends on — the blob it was decoded from (its header carries
     * the format) and the link base relative URLs resolve against. A description rewrite, a format
     * flip or an episode-link move must never be served the previous note.
     */
    private val notesMutex = Mutex()
    private val notesCache = LinkedHashMap<Long, CachedNotes>(SHOW_NOTES_CACHE_SIZE + 1)

    private class CachedNotes(
        val bytes: ByteArray,
        val baseUri: String,
        val notes: ShowNotes,
    )

    override fun observeEpisode(episodeId: Long): Flow<EpisodeDetail?> =
        db.episodeDao().observeDetail(episodeId).map { it?.toDetail() }

    override fun observeShowNotes(episodeId: Long): Flow<ShowNotes?> =
        combine(
            db.episodeDao().observeDetail(episodeId),
            db.episodeDao().observeDescription(episodeId),
        ) { detail, bytes ->
            bytes ?: return@combine null
            val baseUri = detail?.link ?: detail?.feedUrl ?: ""
            notesMutex
                .withLock {
                    notesCache
                        .remove(episodeId)
                        ?.takeIf {
                            it.bytes.contentEquals(bytes) && it.baseUri == baseUri
                        }?.also { notesCache[episodeId] = it }
                }?.notes ?: decode(episodeId, bytes, baseUri)
        }

    override suspend fun setPlayed(
        episodeIds: List<Long>,
        played: Boolean,
    ) {
        val now = clock.now()
        db.withWriteTransaction {
            if (played) {
                db.markEpisodesPlayed(episodeIds, now)
            } else {
                for (chunk in episodeIds.distinct().chunked(BIND_CHUNK)) {
                    db.episodeStateDao().markUnplayed(chunk, now)
                    db.positionDao().reset(chunk, now)
                    db.captureEpisodeIntents(chunk, EpisodeIntent.UNPLAYED, EpisodeIntent.RESET_POSITION)
                }
            }
        }
    }

    override suspend fun markFeedPlayed(
        source: FeedSource,
        sortDateBefore: Long?,
    ) {
        markFeedPlayedSelection(source, sortDateBefore, expectedCount = null)
    }

    override suspend fun markFeedPlayed(
        source: FeedSource,
        sortDateBefore: Long?,
        expectedCount: Int,
    ): MarkFeedPlayedResult {
        require(expectedCount >= 0) { "Expected count must be non-negative" }
        return markFeedPlayedSelection(source, sortDateBefore, expectedCount)
    }

    private suspend fun markFeedPlayedSelection(
        source: FeedSource,
        sortDateBefore: Long?,
        expectedCount: Int?,
    ): MarkFeedPlayedResult =
        db.withWriteTransaction {
            val feed = db.feedDao()
            if (expectedCount != null) {
                val actual = feed.countUnplayed(FeedQueryBuilder.countUnplayed(source, sortDateBefore))
                if (actual != expectedCount) return@withWriteTransaction MarkFeedPlayedResult.SelectionChanged(actual)
            }

            val ids = feed.unplayedIds(FeedQueryBuilder.unplayedIds(source, sortDateBefore))
            val now = clock.now()
            db.markEpisodesPlayed(ids, now)
            MarkFeedPlayedResult.Played(ids.size)
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

    /** Decode → sanitise → model, then remember under the LRU cap. */
    private suspend fun decode(
        episodeId: Long,
        bytes: ByteArray,
        baseUri: String,
    ): ShowNotes {
        val decoded = EpisodeDescriptionCodec.decode(bytes)
        val notes =
            sanitizer
                .toDocument(decoded.text, isHtml = decoded.format == DescriptionFormat.HTML, baseUri = baseUri)
                .toModel()
        notesMutex.withLock {
            notesCache.remove(episodeId)
            notesCache[episodeId] = CachedNotes(bytes, baseUri, notes)
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
