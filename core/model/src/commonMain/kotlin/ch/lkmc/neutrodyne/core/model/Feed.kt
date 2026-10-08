// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * Feed sort order (02 `Feed pages`; stored in `podcast_group.feedOrder`, `podcast.episodeOrder`,
 * `play_session.contextOrder`; unknown values read back as [NEWEST_FIRST]).
 */
enum class FeedOrder { NEWEST_FIRST, OLDEST_FIRST }

/** Audio/video filter of feeds, groups and play contexts (02; fallback [ALL]). */
enum class MediaFilter { ALL, AUDIO, VIDEO }

/**
 * Which episodes a feed page shows (02 Feed pages; the 05 feed scopes). [All] and [Ungrouped] are
 * virtual: they are never `podcast_group` rows. A play context's [ContextType] maps onto these.
 */
sealed interface FeedSource {
    data object All : FeedSource

    data object Ungrouped : FeedSource

    data class Group(
        val groupId: Long,
    ) : FeedSource

    data class Podcast(
        val podcastId: Long,
    ) : FeedSource
}

/**
 * The user feed filters (02 Feed pages). Every flag is optional; [minSortDate] is a lower bound on
 * `episode.sortDate`.
 */
data class FeedFilters(
    val unplayedOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    val inProgressOnly: Boolean = false,
    val media: MediaFilter = MediaFilter.ALL,
    val minSortDate: Long? = null,
)

/** Pinned artwork reference (02 Feed pages; 08 resolves the key against its artwork store). */
data class ArtworkRef(
    val key: String,
    val url: String?,
    val version: Int,
)

/**
 * One rendered feed row (02 Feed pages): the `:core:model` type that `:core:data` maps
 * `EpisodeRowProjection` into. `artwork` is the episode's own art or, when the episode has none,
 * the podcast cover; `podcastArtwork` is always the cover/channel avatar so 08 can render the
 * `CHANNEL_AVATAR` YouTube row style and detect "episode has its own art" by comparing keys.
 */
data class EpisodeRow(
    val id: Long,
    val podcastId: Long,
    val title: String,
    val podcastTitle: String,
    val sortDate: Long,
    val pubDate: Long?,
    val durationMs: Long?,
    val isVideo: Boolean,
    val isShort: Boolean,
    val availability: Availability,
    val episodeType: EpisodeType?,
    val episodeDisplay: String?,
    val sourceType: SourceType,
    val externalMediaId: String?,
    /** "new since last visit" = `isNew && firstSeenAt > lastViewedAt` (08). */
    val isNew: Boolean,
    val firstSeenAt: Long,
    val artwork: ArtworkRef,
    val artworkAvgArgb: Int?,
    val podcastArtwork: ArtworkRef,
    val podcastArtworkAvgArgb: Int?,
    val playedAt: Long?,
    val startedAt: Long?,
    val isFavorite: Boolean,
    val downloadState: DownloadState?,
)
