// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Embedded
import androidx.room3.Relation
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceType

/**
 * One `queue_entry` row joined to its feed row, the projection of `QueueDao.observeUpNext`
 * (02 "Up next ordering", Observe row). `episode` carries the same flat columns as the feed page;
 * `episode_position` is never joined.
 */
data class UpNextRow(
    val entryId: Long,
    val orderKey: String,
    val episodeId: Long,
    @Embedded val episode: EpisodeRowProjection,
)

/**
 * `EpisodeDao.mediaInfo`/`observeMediaInfo` row (02 Media lookup): the metadata 06's projector,
 * media-item factory and now-playing surfaces need, low-churn tables only, never
 * `episode_position`. `durationMs` is the effective value (`COALESCE(measured, feed)`);
 * `measuredDurationMs` the raw stored one (06 Measured duration's >1 s writeback check). The two
 * URL columns complete the `ArtworkRef`s (`:core:data` composes them; pinned-first resolution is
 * 08's artwork store). The M5 alternate relation is batched and read in the same transaction.
 */
data class MediaLookupRow(
    val id: Long,
    val podcastId: Long,
    val title: String,
    val sortDate: Long,
    val enclosureUrl: String?,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val externalMediaId: String?,
    val isVideo: Boolean,
    val pubDate: Long?,
    val availability: Availability,
    val chaptersUrl: String?,
    val chaptersType: String?,
    val durationMs: Long?,
    val measuredDurationMs: Long?,
    val playedAt: Long?,
    val downloadState: DownloadState?,
    val podcastTitle: String,
    val author: String?,
    val sourceType: SourceType,
    val credentialId: Long?,
    val artworkKey: String,
    val artworkUrl: String?,
    val artworkVersion: Int,
    val podcastArtworkKey: String,
    val podcastArtworkUrl: String?,
    val podcastArtworkVersion: Int,
    /** Batched relation: JSON remains opaque in SQL; the mapper can try every ranked row. */
    @Relation(parentColumns = ["id"], entityColumns = ["episodeId"])
    val audioAlternates: List<EpisodeAltEnclosureEntity>,
)

/**
 * The add-eligibility columns `QueueRepositoryImpl` validates a whole batch against before any
 * queue write (06 Up next operations, Reject row): row existence, `availability` and the owning
 * podcast's `sourceType`, plus the `externalMediaId` a YouTube item needs for resolution.
 */
data class QueueEligibilityRow(
    val id: Long,
    val availability: Availability,
    val sourceType: SourceType,
    val externalMediaId: String?,
)
