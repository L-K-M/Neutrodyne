// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution

/**
 * The subscribe transaction (03 Subscribe transaction; implemented in `:core:data` because it needs
 * one multi-table transaction). `invoke` covers RSS feeds resolved through the preview cache;
 * `youTube` freezes M8's channel contract; W6 supplies its implementation.
 */
interface SubscribeUseCase {
    /** Canonical-key transaction shared with imports; validates variants before writing. */
    suspend fun youTube(
        resolution: ChannelResolution.Resolved,
        variants: Int,
        groupIds: Set<Long>,
    ): Outcome<Long, SubscribeError>

    /**
     * Subscribes the preview identified by [previewId] (evicted entries are re-fetched) and puts it
     * into [groupIds]. Returns the new `podcast.id`.
     */
    suspend operator fun invoke(
        previewId: String,
        groupIds: Set<Long>,
    ): Outcome<Long, SubscribeError>
}

sealed interface SubscribeError {
    /** W1 freezes the signature; W6 supplies the transaction and refresh integration. */
    data object YouTubeNotYetAvailable : SubscribeError

    data class AlreadySubscribed(
        val podcastId: Long,
    ) : SubscribeError

    /** The preview had expired and the re-fetch failed. */
    data class Fetch(
        val error: AddPodcastError,
    ) : SubscribeError

    data object NoMedia : SubscribeError

    /** The resolved feed URL carries no usable identity — the feed can never be subscribed. */
    data object InvalidUrl : SubscribeError

    data object Storage : SubscribeError
}
