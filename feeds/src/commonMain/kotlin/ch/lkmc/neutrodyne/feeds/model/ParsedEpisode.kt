// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.model

import kotlinx.serialization.Serializable

/**
 * One normalised feed item in document order (03 Parser). Immutable and serialisable for the golden
 * corpus. `showType`/`episodeType` stay lowercase strings; `:core:data` maps them to its enums.
 */
@Serializable
public data class ParsedEpisode(
    val feedOrder: Int,
    val guid: String? = null,
    val title: String? = null,
    val pubDate: Long? = null,
    val rawPubDate: String? = null,
    val descriptionHtml: String? = null,
    val descriptionIsHtml: Boolean = false,
    val link: String? = null,
    val enclosures: List<Enclosure> = emptyList(),
    val primaryEnclosure: Enclosure? = null,
    val alternateEnclosures: List<AlternateEnclosure> = emptyList(),
    val durationMs: Long? = null,
    val season: Int? = null,
    val seasonName: String? = null,
    val episodeNumber: String? = null,
    val episodeDisplay: String? = null,
    val episodeType: String? = null,
    val explicit: Boolean? = null,
    val artwork: List<ArtworkCandidate> = emptyList(),
    val chaptersUrl: String? = null,
    val chaptersType: String? = null,
    val inlineChapters: List<InlineChapter> = emptyList(),
    val transcripts: List<TranscriptRef> = emptyList(),
    /** Null when the item declares no `podcast:person` — the channel list then applies; an item-level
     * list REPLACES it (Podcasting 2.0). The parser never emits an empty list. */
    val persons: List<Person>? = null,
    val funding: List<Funding> = emptyList(),
    /** Raw `yt:videoId`; interpreted by 04 (04 Atom feed ingestion). */
    val externalMediaId: String? = null,
    /** Raw entry-level `yt:channelId`; interpreted by 04 (04 Atom feed ingestion). */
    val ytChannelId: String? = null,
    /** Raw `media:group/media:community/media:statistics@views`; interpreted by 04. */
    val mediaStatisticsViews: Long? = null,
)
