// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.TransientKind

interface YouTubeChannelRepository {
    /** Nonzero subset of 1/2/4; UI keeps LONG_FORM. Refreshes after commit; Shorts hide immediately. */
    suspend fun setVariants(
        podcastId: Long,
        variants: Int,
    )

    suspend fun ensureChannelArt(podcastId: Long)

    suspend fun loadOlder(podcastId: Long): LoadOlderResult

    suspend fun findRssAlternative(channelTitle: String): RssAlternative?

    suspend fun recheckAvailability(episodeId: Long): Availability?
}

sealed interface LoadOlderResult {
    data class Loaded(
        val inserted: Int,
        val hasMore: Boolean,
    ) : LoadOlderResult

    data object Unsupported : LoadOlderResult

    data class Failed(
        val kind: TransientKind,
    ) : LoadOlderResult
}

data class RssAlternative(
    val feedUrl: String,
    val title: String,
    val artworkUrl: String?,
    val provider: String,
)

/**
 * Shared Add/import transaction boundary, implemented in core data. Derive canonical feedKey
 * inside the transaction, insert PENDING_FIRST_FETCH/initialFetch plus syncId and memberships.
 * Never store aliases. KEEP_EXISTING unions memberships without changing existing variants/title;
 * REJECT returns AlreadySubscribed. The caller schedules INITIAL refresh only after commit.
 */
interface YouTubeSubscriptionWriter {
    suspend fun write(
        channel: ChannelResolution.Resolved,
        variants: Int,
        groupIds: Set<Long>,
        duplicatePolicy: YouTubeDuplicatePolicy,
        customTitle: String? = null,
    ): Outcome<YouTubeSubscription, SubscribeError>
}

enum class YouTubeDuplicatePolicy { REJECT, KEEP_EXISTING }

data class YouTubeSubscription(
    val podcastId: Long,
    val disposition: YouTubeSubscriptionDisposition,
)

enum class YouTubeSubscriptionDisposition { INSERTED, EXISTING }

/**
 * One app-scoped coordinator shared by adapter afterIngest and repository ensureChannelArt.
 * Coalesces same-podcast calls, upgrades pending requests to FULL for DETAIL, owns the sliding
 * 10-min budget (100 never-fetched + 30 stale), concurrency 2 and 0.5–1.5 s jitter. A fetch has a
 * 20 s timeout; failures leave metadataAt unchanged. Changed avatar keys are explicitly re-pinned.
 */
interface YouTubeChannelMetadata {
    suspend fun refresh(
        podcastId: Long,
        reason: ChannelMetadataReason,
    ): ChannelMetadataResult
}

enum class ChannelMetadataReason { AFTER_INGEST, DETAIL }

enum class ChannelMetadataResult { UPDATED, FRESH, DEFERRED, FAILED }

/** Call only after successful OS handoff. True means caller offers the ordinary 5 s played Undo.
 * Applies mark_played_on_open only for EXTERNAL_OR_UNPLAYABLE; playable overflow never marks.
 * Undo restores unplayed only, not position or Up next. Cancellation/no handler never call this. */
interface YouTubeExternalOpen {
    suspend fun onOpened(
        episodeId: Long,
        context: YouTubeOpenContext,
    ): Boolean
}

enum class YouTubeOpenContext { EXTERNAL_OR_UNPLAYABLE, PLAYABLE_OVERFLOW }
