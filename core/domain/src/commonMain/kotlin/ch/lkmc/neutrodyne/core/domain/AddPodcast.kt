// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.NetError

/**
 * The add-podcast pipeline's front door (03 Add podcast flow): normalisation, host recognition,
 * fetch/sniff, in-memory preview and dedupe. Implemented by `AddPodcastResolverImpl` (`:core:data`).
 */
interface AddPodcastResolver {
    /** Canonical entry: every entry point ends here; a `Choose` pick calls `resolve(candidate.url)`. */
    suspend fun resolve(input: String): AddResolution

    /** The retry after [AddPodcastError.AuthRequired] with the credentials the user typed. */
    suspend fun resolve(
        input: String,
        credentials: BasicCredentials,
    ): AddResolution

    /** `PodcastPreviewKey(feedUrl)` (directory results): reuses a cached entry or fetches anew. */
    suspend fun preview(feedUrl: String): Outcome<FeedPreview, AddPodcastError>
}

sealed interface AddResolution {
    data class Feed(
        val preview: FeedPreview,
    ) : AddResolution

    data class Choose(
        val candidates: List<FeedCandidate>,
    ) : AddResolution

    data class Failure(
        val error: AddPodcastError,
    ) : AddResolution
}

sealed interface AddPodcastError {
    /** The text is not URL-like; the UI offers "Search for …" with [query]. */
    data class NotAUrl(
        val query: String,
    ) : AddPodcastError

    data object InvalidUrl : AddPodcastError

    data class Network(
        val error: NetError,
    ) : AddPodcastError

    data class Http(
        val code: Int,
    ) : AddPodcastError

    data class AuthRequired(
        val realm: String?,
    ) : AddPodcastError

    data object NotAFeed : AddPodcastError

    data object NoMedia : AddPodcastError

    data object TooLarge : AddPodcastError

    data object Malformed : AddPodcastError

    data object UnsupportedListFeed : AddPodcastError

    /** The Apple lookup returned a show without a `feedUrl` (M7). */
    data object AppleOnlyShow : AddPodcastError

    /** Spotify shows have no public RSS feed; the UI explains searching by name (M7). */
    data object SpotifyShow : AddPodcastError

    /** An OPML document was fetched; the UI offers the import of 05. */
    data class SubscriptionList(
        val url: String,
    ) : AddPodcastError

    /** The Apple token bucket is empty (M7). */
    data object DirectoryBusy : AddPodcastError

    /** YouTube input on a build before M8's subscribe flow exists (the M1–M2 host check lands here). */
    data object YouTubeNotYetSupported : AddPodcastError
}
