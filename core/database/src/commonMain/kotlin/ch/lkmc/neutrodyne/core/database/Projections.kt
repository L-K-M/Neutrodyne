// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.GuidKnowledge
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType

/**
 * The feed page's row (02 Feed pages, `ROW_COLUMNS`): `:core:data` maps it to
 * `EpisodeRow` (`artwork`/`podcastArtwork` columns compose an `ArtworkRef` there; DAO projections
 * stay flat — 02 DAO rule 6).
 */
data class EpisodeRowProjection(
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
    val isNew: Boolean,
    val firstSeenAt: Long,
    val artworkKey: String,
    val artworkUrl: String?,
    val artworkVersion: Int,
    val artworkAvgArgb: Int?,
    val podcastArtworkKey: String,
    val podcastArtworkUrl: String?,
    val podcastArtworkVersion: Int,
    val podcastArtworkAvgArgb: Int?,
    val playedAt: Long?,
    val startedAt: Long?,
    val isFavorite: Boolean,
    val downloadState: DownloadState?,
)

/** One due feed's scheduling and validator snapshot (02 Refresh selection and fetch-state writes). */
data class DueFeed(
    val id: Long,
    val feedUrl: String,
    val sourceType: SourceType,
    val youtubeChannelId: String?,
    val youtubeVariants: Int,
    val channelMetadataAt: Long?,
    val etag: String?,
    val lastModified: String?,
    val contentSha256: String?,
    val parserVersion: Int,
    val lastParseOk: Boolean,
    val credentialId: Long?,
    val failureCount: Int,
    val initialFetch: Boolean,
    val status: PodcastStatus,
    val lastSuccessAt: Long?,
    val lastFullFetchAt: Long?,
    val pendingNewFeedUrl: String?,
    val pagingNextUrl: String?,
    val pagingComplete: Boolean,
    val complete: Boolean,
    val ttlMinutes: Int?,
    val latestEpisodeAt: Long?,
    val subscribedAt: Long,
    val lastAttemptAt: Long?,
    val lastErrorKind: FeedErrorKind?,
    /** D98: provenance coverage marker (02 `podcast.guidCoverageSince`); `null` = uncovered. */
    val guidCoverageSince: Long?,
)

/**
 * `PodcastDao.rebaseCandidates` row (added 2026-10-07): a `PodcastFetchState` plus `subscribedAt`,
 * which the rebase formula needs (`target = COALESCE(lastSuccessAt, subscribedAt) + I`).
 */
data class RebaseCandidate(
    val id: Long,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val nextRefreshAt: Long?,
    val failureCount: Int,
    val lastErrorKind: FeedErrorKind?,
    val lastErrorDetail: String?,
    val gone: Boolean,
    val needsCredentials: Boolean,
    val etag: String?,
    val lastModified: String?,
    val lastFullFetchAt: Long?,
    val lastParseOk: Boolean,
    val subscribedAt: Long,
) {
    /** The batched-write form (the rebase changes `nextRefreshAt` only). */
    fun fetchState(nextRefreshAt: Long?) =
        PodcastFetchState(
            id = id,
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            nextRefreshAt = nextRefreshAt,
            failureCount = failureCount,
            lastErrorKind = lastErrorKind,
            lastErrorDetail = lastErrorDetail,
            gone = gone,
            needsCredentials = needsCredentials,
            etag = etag,
            lastModified = lastModified,
            lastFullFetchAt = lastFullFetchAt,
            lastParseOk = lastParseOk,
        )
}

/**
 * The partial `podcast` write of batched refresh outcomes (02 Refresh selection and fetch-state
 * writes): only scheduling, error and validator columns — never feed data or user columns.
 * Invariant for callers: one row is one *committed* fetch outcome — never construct an instance
 * before the fetch finished, so a crash mid-fetch cannot persist a fabricated result.
 */
data class PodcastFetchState(
    val id: Long,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val nextRefreshAt: Long?,
    val failureCount: Int,
    val lastErrorKind: FeedErrorKind?,
    val lastErrorDetail: String?,
    val gone: Boolean,
    val needsCredentials: Boolean,
    val etag: String?,
    val lastModified: String?,
    val lastFullFetchAt: Long?,
    val lastParseOk: Boolean,
)

/** A library tile row (02 Library tiles and mosaics); the mapper sorts by title in Kotlin. */
data class LibraryTileRow(
    val id: Long,
    val title: String,
    val sourceType: SourceType,
    val status: PodcastStatus,
    val artworkKey: String,
    val artworkUrl: String?,
    val artworkVersion: Int,
    val artworkAvgArgb: Int?,
    val gone: Boolean,
    val needsCredentials: Boolean,
    val failureCount: Int,
    val lastErrorKind: FeedErrorKind?,
    val lastSuccessAt: Long?,
    val latestEpisodeAt: Long?,
    val subscribedAt: Long,
    val unplayedCount: Int,
)

/** The `IngestDao.existing` row 03 builds its in-memory identity maps from (02 Ingestion support). */
data class ExistingEpisodeKey(
    val id: Long,
    val identityKey: String,
    val guid: String?,
    val enclosureUrl: String?,
    /** Added 2026-10-07 for the diff's pass-2 MIME-major-type guard (03 step 5). */
    val enclosureType: String?,
    val title: String,
    val pubDate: Long?,
    /** Added 2026-10-07: pass-2's matched-window floor and `isNew`'s date basis. */
    val sortDate: Long,
    /** Added 2026-10-07 for the diff's pass-2 duration guard (03 step 5). */
    val durationMs: Long?,
    val contentHash: Long,
    val inFeed: Boolean,
    /** Added 2026-10-07: `sortDate` is recomputed against the stored `firstSeenAt` (03 sortDate). */
    val firstSeenAt: Long,
    /** Added 2026-10-07: step 6's JSON-chapters invalidation compares the stored value. */
    val chaptersUrl: String?,
)

/** `IngestDao.guidKnowledge` row (D98): one stored provenance fact of the podcast. */
data class GuidKnowledgeRow(
    val guid: String,
    val knowledge: GuidKnowledge,
)

/** `PodcastDao.observeCategoryRows` row: 03's `CategoryCount` source (suggested groups, M7). */
data class PodcastCategories(
    val id: Long,
    val categoriesJson: String?,
)

/** `EpisodeDao.observeDetail` row, mapped to `EpisodeDetail` in `:core:data` (02 DAO rule 6). */
data class EpisodeDetailRow(
    val id: Long,
    val podcastId: Long,
    val podcastTitle: String,
    val title: String,
    val pubDate: Long?,
    val durationMs: Long?,
    val artworkKey: String,
    val artworkUrl: String?,
    val artworkVersion: Int,
    val isVideo: Boolean,
    val sourceType: SourceType,
    val externalMediaId: String?,
    val availability: Availability,
    val episodeDisplay: String?,
    val link: String?,
    val playedAt: Long?,
    val isFavorite: Boolean,
    val downloadState: DownloadState?,
    /** The show-notes `baseUri` fallback (03 Sanitiser): episode `link` first, then this. */
    val feedUrl: String,
)

/**
 * One `updateFeedFields` row (02 Ingestion support): every feed column of `episode` except `id`,
 * `podcastId`, `identityKey`, `firstSeenAt`, `isNew` and `inFeed` (that flag flips through
 * `setInFeed`). The `null`-preserving columns keep their stored value when the parsed value is
 * null: `durationMs`, `imageUrl` + `artworkKey` (a pair), `chaptersUrl` + `chaptersType`,
 * `availability`, `isShort`, `isVideo`.
 */
data class EpisodeFeedUpdate(
    val id: Long,
    val guid: String?,
    val title: String,
    val pubDate: Long?,
    val rawPubDate: String?,
    val sortDate: Long,
    val feedOrder: Int,
    val lastSeenAt: Long,
    val enclosureUrl: String?,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val externalMediaId: String?,
    val season: Int?,
    val seasonName: String?,
    val episodeNumber: String?,
    val episodeDisplay: String?,
    val episodeType: EpisodeType?,
    val explicit: Boolean?,
    val link: String?,
    val contentHash: Long,
    val snippet: String?,
    // Null-preserving columns (03 Column rules on update).
    val durationMs: Long?,
    val imageUrl: String?,
    val artworkKey: String?,
    val chaptersUrl: String?,
    val chaptersType: String?,
    val availability: Availability?,
    val isShort: Boolean?,
    val isVideo: Boolean?,
)

/**
 * The ingest-side `podcast` write (02 Ingestion support): 03-owned metadata, validators and
 * scheduling columns. Never `id`, `syncId`, `feedUrl`, `feedKey`, `customTitle`, `includeInAll`,
 * `episodeOrder`, `autoDownloadEligibleAfter`, `youtubeVariants`, `channelMetadataAt`,
 * `youtubeChannelId`, `credentialId` or `subscribedAt`.
 */
data class PodcastFeedMetadata(
    val id: Long,
    val podcastGuid: String?,
    val podcastGuidDerived: Boolean,
    val title: String,
    val author: String?,
    val link: String?,
    val language: String?,
    val explicit: Boolean?,
    val showType: ch.lkmc.neutrodyne.core.model.ShowType?,
    val medium: String?,
    val locked: Boolean?,
    val complete: Boolean,
    val artworkUrl: String?,
    val artworkKey: String,
    val bannerUrl: String?,
    val status: PodcastStatus,
    val initialFetch: Boolean,
    val latestEpisodeAt: Long?,
    val etag: String?,
    val lastModified: String?,
    val contentSha256: String?,
    val parserVersion: Int,
    val lastParseOk: Boolean,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val lastFullFetchAt: Long?,
    val nextRefreshAt: Long?,
    val failureCount: Int,
    val lastErrorKind: FeedErrorKind?,
    val lastErrorDetail: String?,
    val gone: Boolean,
    val needsCredentials: Boolean,
    val ttlMinutes: Int?,
    val updateFrequencyRrule: String?,
    val pendingNewFeedUrl: String?,
    val pagingNextUrl: String?,
    val pagingComplete: Boolean,
    val hubUrl: String?,
    val usesPodping: Boolean,
    val descriptionHtml: String?,
    val categoriesJson: String?,
)

/**
 * The `YOUTUBE_CHANNEL` variant of [PodcastFeedMetadata] (04 Atom feed ingestion): additionally
 * omits `artworkUrl`, `artworkKey`, `bannerUrl`, `descriptionHtml`, `link` and `youtubeChannelId`,
 * which only `PodcastDao.applyYouTubeChannelMetadata` writes.
 */
data class YouTubeFeedMetadata(
    val id: Long,
    val podcastGuid: String?,
    val podcastGuidDerived: Boolean,
    val title: String,
    val author: String?,
    val language: String?,
    val explicit: Boolean?,
    val showType: ch.lkmc.neutrodyne.core.model.ShowType?,
    val medium: String?,
    val locked: Boolean?,
    val complete: Boolean,
    val status: PodcastStatus,
    val initialFetch: Boolean,
    val latestEpisodeAt: Long?,
    val etag: String?,
    val lastModified: String?,
    val contentSha256: String?,
    val parserVersion: Int,
    val lastParseOk: Boolean,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val lastFullFetchAt: Long?,
    val nextRefreshAt: Long?,
    val failureCount: Int,
    val lastErrorKind: FeedErrorKind?,
    val lastErrorDetail: String?,
    val gone: Boolean,
    val needsCredentials: Boolean,
    val ttlMinutes: Int?,
    val updateFrequencyRrule: String?,
    val pendingNewFeedUrl: String?,
    val pagingNextUrl: String?,
    val pagingComplete: Boolean,
    val hubUrl: String?,
    val usesPodping: Boolean,
    val categoriesJson: String?,
)

/** `alias`-insert row of subscribe/import/merge paths — reason is a parameter, not a column. */
data class NewAlias(
    val url: String,
    val podcastId: Long,
    val reason: AliasReason,
    val addedAt: Long,
)
