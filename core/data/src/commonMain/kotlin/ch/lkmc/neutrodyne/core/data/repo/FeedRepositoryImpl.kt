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
 * `FeedRepository` (05 Group feeds), M1a slice. [pagedFeed] builds one `Pager` per call whose
 * `PagingSource` is `FeedDao.page(FeedQueryBuilder.page(source, filters, order))` — SQL lives only
 * in `FeedQueryBuilder` — and maps `EpisodeRowProjection` to `EpisodeRow` with `PagingData.map`.
 * Room's observed-entity invalidation regenerates the page on any relevant write. The repository
 * owns the fixed paging config below (08 Paging hand-off); `cachedIn`, the per-source LRU and
 * filter observation stay with the ViewModel. [setFeedOrder] writes the persisted order column per
 * 05's Sources and tabs table.
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
        Pager(PAGING_CONFIG) {
            db.feedDao().page(FeedQueryBuilder.page(source, filters, order))
        }.flow.map { data -> data.map(::toRow) }

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

            FeedSource.All, FeedSource.Ungrouped -> {
                throw IllegalArgumentException("feed order is fixed NEWEST_FIRST for $source")
            }
        }
    }

    private fun toRow(p: EpisodeRowProjection): EpisodeRow =
        EpisodeRow(
            id = p.id,
            podcastId = p.podcastId,
            title = p.title,
            podcastTitle = p.podcastTitle,
            sortDate = p.sortDate,
            pubDate = p.pubDate,
            durationMs = p.durationMs,
            isVideo = p.isVideo,
            isShort = p.isShort,
            availability = p.availability,
            episodeType = p.episodeType,
            episodeDisplay = p.episodeDisplay,
            sourceType = p.sourceType,
            externalMediaId = p.externalMediaId,
            isNew = p.isNew,
            firstSeenAt = p.firstSeenAt,
            artwork = ArtworkRef(p.artworkKey, p.artworkUrl, p.artworkVersion),
            artworkAvgArgb = p.artworkAvgArgb,
            podcastArtwork = ArtworkRef(p.podcastArtworkKey, p.podcastArtworkUrl, p.podcastArtworkVersion),
            podcastArtworkAvgArgb = p.podcastArtworkAvgArgb,
            playedAt = p.playedAt,
            startedAt = p.startedAt,
            isFavorite = p.isFavorite,
            downloadState = p.downloadState,
        )

    private companion object {
        /** 08 Paging hand-off: page size and prefetch distance share one value. */
        const val PAGE_SIZE = 40
        const val INITIAL_LOAD_SIZE = 80
        const val MAX_SIZE = 400

        val PAGING_CONFIG =
            PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = true,
                maxSize = MAX_SIZE,
            )
    }
}
