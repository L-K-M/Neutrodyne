// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * The read models of 03's "Unsubscribe and other podcast operations" (SQL: 02 Library tiles and
 * mosaics, rendering: 08). Display title = `customTitle ?: title`.
 */

/** A podcast's refresh health; [possiblyDead] is computed from the row and `Clock` in `:core:data`. */
data class FeedHealth(
    val gone: Boolean,
    val needsCredentials: Boolean,
    val failureCount: Int,
    val lastSuccessAt: Long?,
    val lastErrorKind: FeedErrorKind?,
    val possiblyDead: Boolean,
)

/** One library cover-grid tile (02 `LibraryTileRow` mapped). */
data class LibraryTile(
    val podcastId: Long,
    val displayTitle: String,
    val sourceType: SourceType,
    val status: PodcastStatus,
    val artwork: ArtworkRef,
    val artworkAvgArgb: Int?,
    val health: FeedHealth,
    val latestEpisodeAt: Long?,
    val subscribedAt: Long,
    val unplayedCount: Int,
)

/** The podcast detail screen's header data (03; 08 renders it). */
data class PodcastDetail(
    val id: Long,
    val displayTitle: String,
    val author: String?,
    val description: ShowNotes?,
    val artwork: ArtworkRef,
    val bannerUrl: String?,
    val sourceType: SourceType,
    val link: String?,
    val episodeCount: Int,
    val latestEpisodeAt: Long?,
    val status: PodcastStatus,
    val health: FeedHealth,
    val isPrivate: Boolean,
    val episodeOrder: FeedOrder?,
    val showType: ShowType?,
    /** `pagingNextUrl != null` — older pages may be fetched on demand (03). */
    val hasOlderPages: Boolean,
)

/** The episode detail screen's data (position comes from `EpisodeLiveStateSource`, not here). */
data class EpisodeDetail(
    val id: Long,
    val podcastId: Long,
    val podcastTitle: String,
    val title: String,
    val pubDate: Long?,
    val durationMs: Long?,
    val artwork: ArtworkRef,
    val isVideo: Boolean,
    val sourceType: SourceType,
    val externalMediaId: String?,
    val availability: Availability?,
    val episodeDisplay: String?,
    val link: String?,
    val playedAt: Long?,
    val isFavorite: Boolean,
    val downloadState: DownloadState?,
)

/**
 * The feed-info section of podcast settings (03). `feedUrl` is shown only after an explicit tap;
 * everything else displays [redactedUrl] (01's `Redactor.url`).
 */
data class FeedInfo(
    val feedUrl: String,
    val redactedUrl: String,
    val isPrivate: Boolean,
    val moves: List<FeedMove>,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val nextRefreshAt: Long?,
    val lastErrorKind: FeedErrorKind?,
    val lastErrorDetail: String?,
    val pendingNewFeedUrl: String?,
)

/** One recorded move of a feed (aliases with reason `REDIRECT` / `NEW_FEED_URL`). */
data class FeedMove(
    val fromHost: String,
    val reason: AliasReason,
    val at: Long,
)

/** A category and the subscribed podcasts carrying it (03's suggested groups, M7). */
data class CategoryCount(
    val category: String,
    val podcastIds: List<Long>,
)
