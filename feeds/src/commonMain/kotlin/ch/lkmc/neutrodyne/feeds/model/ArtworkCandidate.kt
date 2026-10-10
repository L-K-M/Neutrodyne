// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.model

import kotlinx.serialization.Serializable

/** Where an artwork candidate came from; recorded in the golden JSON (03 Artwork candidates). */
@Serializable
public enum class ArtworkSource {
    ITUNES_IMAGE,
    PODCAST_IMAGE,
    PODCAST_IMAGES,
    MEDIA_THUMBNAIL,
    MEDIA_CONTENT,
    RSS_IMAGE,
    ATOM_LOGO,
    ATOM_ICON,
    GOOGLEPLAY_IMAGE,
}

/**
 * One candidate artwork URL in precedence order; the first valid absolute `http(s)` URL wins at ingest.
 * `width`/`height` are the declared pixel sizes when the source carries them (`podcast:image`,
 * `media:thumbnail`, `media:content`), null otherwise.
 */
@Serializable
public data class ArtworkCandidate(
    val url: String,
    val source: ArtworkSource,
    val width: Int? = null,
    val height: Int? = null,
)
