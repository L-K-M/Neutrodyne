// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.youtube.api

/**
 * What a user-supplied YouTube input turned out to be ([YouTubeUrlClassifier.classify]).
 *
 * Only [Channel] carries a canonical identity; every other variant is an input that
 * `YouTubeChannelResolver` turns into a `UC…` ID before anything is stored, and handles and
 * custom URLs are never persisted (04 Channel resolution, R3.1).
 */
sealed interface YtRef {
    /** A `UC…` channel ID, validated by [YouTubeIds.CHANNEL]. [variantsHint] is the uploads-playlist bit set. */
    data class Channel(
        val id: String,
        val variantsHint: Int? = null,
    ) : YtRef

    /** A `@handle`, percent-decoded and NFC-normalized. */
    data class Handle(
        val handle: String,
    ) : YtRef

    /** A legacy path: `c/{name}`, `user/{name}` or a bare custom URL `{name}`. */
    data class LegacyPath(
        val path: String,
    ) : YtRef

    /** An 11-character video ID, validated by [YouTubeIds.VIDEO]. */
    data class Video(
        val id: String,
    ) : YtRef

    /** A playlist ID; playlists are not subscribable in v1 (D53). */
    data class Playlist(
        val id: String,
    ) : YtRef

    /** Free text for the engine's channel search; only [YouTubeUrlClassifier.classifyOrQuery] produces it. */
    data class Query(
        val text: String,
    ) : YtRef
}
