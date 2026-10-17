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
) {
    fun toFlags(): Int =
        (if (unplayedOnly) FilterFlagBits.UNPLAYED else 0) or
            (if (downloadedOnly) FilterFlagBits.DOWNLOADED else 0) or
            (if (inProgressOnly) FilterFlagBits.IN_PROGRESS else 0)

    companion object {
        fun fromFlags(
            flags: Int,
            media: MediaFilter,
        ): FeedFilters =
            FeedFilters(
                unplayedOnly = flags and FilterFlagBits.UNPLAYED != 0,
                downloadedOnly = flags and FilterFlagBits.DOWNLOADED != 0,
                inProgressOnly = flags and FilterFlagBits.IN_PROGRESS != 0,
                media = media,
            )
    }
}

/** Persisted tab metadata; virtual feeds have no group UUID or appearance overrides (05). */
data class FeedTab(
    val source: FeedSource,
    val title: String,
    val colorArgb: Int?,
    val iconKey: String?,
    val groupUuid: String?,
)

data class FeedPrefs(
    val feedOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    val filters: FeedFilters = FeedFilters(),
    val hideOlderThanDays: Int? = null,
    val lastViewedAt: Long? = null,
) {
    /** Hour rounding stabilises the query between hourly age-window updates (05). */
    fun at(now: Long): FeedPrefs =
        copy(filters = filters.copy(minSortDate = hideOlderThanDays?.let { now - now.mod(HOUR_MS) - it * DAY_MS }))

    companion object {
        const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
    }
}

fun isSupportedFeedAgeLimit(days: Int?): Boolean = days == null || days in SUPPORTED_FEED_AGE_LIMITS

private val SUPPORTED_FEED_AGE_LIMITS = setOf(1, 3, 7, 14, 30, 90, 365)

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
