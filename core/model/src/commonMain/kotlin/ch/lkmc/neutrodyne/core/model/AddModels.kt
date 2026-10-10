// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * The add-podcast sheet's in-memory preview of a fetched feed (03 Preview and dedupe). Previews are
 * never persisted — [previewId] references a `PreviewCache` entry the subscribe path resolves.
 */
data class FeedPreview(
    val previewId: String,
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: ShowNotes?,
    val artworkUrl: String?,
    val link: String?,
    val categories: List<List<String>>,
    val language: String?,
    val explicit: Boolean?,
    val episodeCount: Int,
    val latestEpisodeAt: Long?,
    /** The newest 200 items, display-mapped. */
    val episodes: List<PreviewEpisode>,
    val hasOlderPages: Boolean,
    val isPrivate: Boolean,
    val alreadySubscribed: AlreadySubscribed?,
    /** Zero parsed items — a new show without episodes yet; the Subscribe button stays enabled. */
    val emptyFeed: Boolean,
)

data class PreviewEpisode(
    val title: String,
    val pubDate: Long?,
    val durationMs: Long?,
    val snippet: String?,
    val imageUrl: String?,
    val isVideo: Boolean,
)

/** One autodiscovery or directory candidate the sheet may offer (03; M7 fills the lists). */
data class FeedCandidate(
    val url: String,
    val title: String?,
    val episodeCount: Int?,
    val source: String,
)

/**
 * Dedupe hit on the previewed feed (03 Preview and dedupe): [exact] = the URL identity matched
 * (`podcast.feedKey` or an alias); a non-exact hit shares a real `podcastGuid` only.
 */
data class AlreadySubscribed(
    val podcastId: Long,
    val exact: Boolean,
)

/** Basic-auth credentials typed into the add sheet or "Enter password" (03 Basic auth). */
data class BasicCredentials(
    val username: String,
    val password: String,
)
