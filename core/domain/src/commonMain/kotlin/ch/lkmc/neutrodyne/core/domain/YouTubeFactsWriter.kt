// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.youtube.api.VideoFacts

/** Resolve/media callers own availability only. W9 uses changed-value EpisodeDao.setAvailability. */
interface YouTubeAvailabilityRecorder {
    suspend fun record(
        episodeId: Long,
        reason: Availability,
    )
}

/** W9 owns transactions/codec/snippet writes; no user state, Atom identity/hash/date or feed fields.
 * Null facts preserve stored values; positive duration only; Shorts is sticky OR. Description is
 * filled only when absent, using existing blobs/codec. Truncated text is not stored as a complete
 * description; leave it absent so chapter consumers never mistake an incomplete list for complete.
 * Current-item description fetch is coalesced, bounded, cancellable and checked for obsolescence
 * before committing. W7 reads through the chapter domain service, never through a stream URL DTO.
 */
interface YouTubeFactsWriter {
    suspend fun applyFacts(
        podcastId: Long,
        facts: List<VideoFacts>,
    )

    suspend fun fillDescriptionIfAbsent(
        episodeId: Long,
        description: String,
        completeness: DescriptionCompleteness,
    )
}

// Retain the existing Kotlin source name without a database -> domain dependency.
typealias DescriptionCompleteness = ch.lkmc.neutrodyne.core.model.DescriptionCompleteness
