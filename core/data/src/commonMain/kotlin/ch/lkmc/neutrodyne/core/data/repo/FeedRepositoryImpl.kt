// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `FeedRepository` (05 Group feeds), M1a slice: [pagedFeed] for `FeedSource.All` and
 * `FeedSource.Podcast` over `FeedDao.page` + `FeedQueryBuilder`, and [setFeedOrder] writing the
 * podcast/group order column. The paging configuration is 05's (05 Paging hand-off to 08); the
 * 3-flow LRU and `cachedIn` live in the Feeds ViewModel.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class FeedRepositoryImpl(
    private val db: NeutrodyneDatabase,
) : FeedRepository {
    override fun pagedFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): Flow<PagingData<EpisodeRow>> =
        Pager(
            config = PAGE_CONFIG,
            pagingSourceFactory = { db.feedDao().page(FeedQueryBuilder.page(source, filters, order)) },
        ).flow
            .map { data -> data.map { it.toEpisodeRow() } }

    override suspend fun setFeedOrder(
        source: FeedSource,
        order: FeedOrder,
    ) {
        when (source) {
            is FeedSource.Podcast -> {
                db.podcastDao().setEpisodeOrder(source.podcastId, order)
            }

            is FeedSource.Group -> {
                db.groupDao().setFeedOrder(source.groupId, order)
            }

            // All and Ungrouped are fixed NEWEST_FIRST in v1 (05 Sources and tabs).
            FeedSource.All,
            FeedSource.Ungrouped,
            -> {
                throw IllegalArgumentException("feed order is fixed for $source")
            }
        }
    }

    private companion object {
        /** 05 Paging hand-off to 08. */
        val PAGE_CONFIG =
            PagingConfig(
                pageSize = 40,
                prefetchDistance = 40,
                initialLoadSize = 80,
                enablePlaceholders = true,
                maxSize = 400,
            )
    }
}

/** `EpisodeRowProjection` → `:core:model`'s `EpisodeRow` (02 Feed pages). */
private fun EpisodeRowProjection.toEpisodeRow(): EpisodeRow =
    EpisodeRow(
        id = id,
        podcastId = podcastId,
        title = title,
        podcastTitle = podcastTitle,
        sortDate = sortDate,
        pubDate = pubDate,
        durationMs = durationMs,
        isVideo = isVideo,
        isShort = isShort,
        availability = availability,
        episodeType = episodeType,
        episodeDisplay = episodeDisplay,
        sourceType = sourceType,
        externalMediaId = externalMediaId,
        isNew = isNew,
        firstSeenAt = firstSeenAt,
        artwork = ArtworkRef(artworkKey, artworkUrl, artworkVersion),
        artworkAvgArgb = artworkAvgArgb,
        podcastArtwork = ArtworkRef(podcastArtworkKey, podcastArtworkUrl, podcastArtworkVersion),
        podcastArtworkAvgArgb = podcastArtworkAvgArgb,
        playedAt = playedAt,
        startedAt = startedAt,
        isFavorite = isFavorite,
        downloadState = downloadState,
    )
