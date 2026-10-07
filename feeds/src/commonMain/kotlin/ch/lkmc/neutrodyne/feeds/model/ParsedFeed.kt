// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.model

import kotlinx.serialization.Serializable

/**
 * The fully normalised output of [FeedParser][ch.lkmc.neutrodyne.feeds.parse.FeedParser]: one normalised
 * model for RSS 2.0, Atom, RSS 1.0/RDF, iTunes, Podcasting 2.0, Media RSS and Podlove Simple Chapters
 * (03 Parser). Immutable and serialisable for the golden corpus.
 */
@Serializable
public data class ParsedFeed(
    val format: FeedFormat,
    val title: String? = null,
    val author: String? = null,
    val descriptionHtml: String? = null,
    val link: String? = null,
    val language: String? = null,
    val categories: List<List<String>> = emptyList(),
    val explicit: Boolean? = null,
    val showType: String? = null,
    val complete: Boolean = false,
    val newFeedUrl: String? = null,
    val podcastGuid: String? = null,
    val locked: Boolean? = null,
    val medium: String? = null,
    val artwork: List<ArtworkCandidate> = emptyList(),
    val bannerUrl: String? = null,
    val funding: List<Funding> = emptyList(),
    val persons: List<Person> = emptyList(),
    val updateFrequencyRrule: String? = null,
    val ttlMinutes: Int? = null,
    val paging: Paging = Paging(),
    val hubUrl: String? = null,
    val usesPodping: Boolean = false,
    /** Raw feed-level `yt:channelId`; interpreted by 04 (04 Atom feed ingestion). */
    val ytChannelId: String? = null,
    val items: List<ParsedEpisode> = emptyList(),
    val warnings: List<ParseWarning> = emptyList(),
)
