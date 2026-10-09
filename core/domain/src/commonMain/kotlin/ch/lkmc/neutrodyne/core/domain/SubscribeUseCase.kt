// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome

/**
 * The subscribe transaction (03 Subscribe transaction; implemented in `:core:data` because it needs
 * one multi-table transaction). `invoke` covers RSS feeds resolved through the preview cache;
 * `youTube` arrives with M8's `ChannelResolution`.
 */
interface SubscribeUseCase {
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
    data class AlreadySubscribed(
        val podcastId: Long,
    ) : SubscribeError

    /** The preview had expired and the re-fetch failed. */
    data class Fetch(
        val error: AddPodcastError,
    ) : SubscribeError

    data object NoMedia : SubscribeError

    data object Storage : SubscribeError
}
