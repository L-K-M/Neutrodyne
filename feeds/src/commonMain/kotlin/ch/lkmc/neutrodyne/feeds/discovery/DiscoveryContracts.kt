// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.discovery

import okio.Source

/** Pure parser output, mapped and URL-validated into core:model DirectoryHit by core:data. */
data class DirectoryRecord(
    val feedUrl: String?,
    val title: String?,
    val author: String? = null,
    val artworkUrl: String? = null,
    val description: String? = null,
    val episodeCount: Int? = null,
    val lastEpisodeAt: Long? = null,
    val podcastGuid: String? = null,
    val genres: List<String> = emptyList(),
    val explicit: Boolean? = null,
    val directoryId: String? = null,
    val rank: Int,
    val dead: Boolean = false,
    val medium: String? = null,
)

/** Recognition is pure; execution and disabled-provider checks belong to the Add resolver. */
sealed interface HostAction {
    data class AppleLookup(
        val id: String,
    ) : HostAction

    data class PodcastIndexLookup(
        val id: String,
    ) : HostAction

    data object SpotifyShow : HostAction

    data object FyydPage : HostAction

    data object Fetch : HostAction
}

enum class DiscoverySource { ALTERNATE_LINK, APPLE_ANCHOR, SAME_SITE_ANCHOR }

data class DiscoveredFeed(
    val url: String,
    val title: String?,
    val source: DiscoverySource,
)

/**
 * Pure HTML extraction; no fetch/probes here. The caller owns/closes html. Read at most
 * MAX_HTML_BYTES, honor base href, drop comments, rank alternates before anchors.
 * The transport must also enforce this HTML-only cap after sniffing; feed limits stay intact.
 */
interface Autodiscovery {
    fun candidates(
        html: Source,
        charset: String?,
        pageUrl: String,
    ): List<DiscoveredFeed>

    companion object {
        const val MAX_HTML_BYTES: Long = 2L * 1024 * 1024
        const val MAX_CANDIDATES: Int = 5
        const val VERIFICATION_CONCURRENCY: Int = 2
        const val PROBE_BYTES: Long = 64L * 1024
        const val PROBE_BUDGET_MS: Long = 15_000
    }
}
