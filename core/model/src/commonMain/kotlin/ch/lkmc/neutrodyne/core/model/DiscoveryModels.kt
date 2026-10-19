// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

enum class ProviderId(
    val host: String,
) {
    APPLE("itunes.apple.com"),
    FYYD("api.fyyd.de"),
    PODCAST_INDEX("api.podcastindex.org"),
}

/** Fusion tie order and disclosure order, independent of enum declaration order. */
val DIRECTORY_PROVIDER_ORDER: ImmutableList<ProviderId> =
    persistentListOf(ProviderId.APPLE, ProviderId.PODCAST_INDEX, ProviderId.FYYD)

enum class DirectoryKeySource { NONE, BUILD, USER }

/** Configuration and last operation status are separate: a failed provider remains enabled. */
data class ProviderInfo(
    val id: ProviderId,
    val enabled: Boolean,
    val keySource: DirectoryKeySource = DirectoryKeySource.NONE,
    val status: ProviderStatus = ProviderStatus.Pending,
    /** Changes on country/switch/key replacement, even when effective recipients stay the same. */
    val configurationVersion: Long = 0,
) {
    val host: String get() = id.host
    val needsKey: Boolean get() = id == ProviderId.PODCAST_INDEX
    val hasKey: Boolean get() = keySource != DirectoryKeySource.NONE
    val effective: Boolean get() = enabled && (!needsKey || hasKey)
}

/** Lets settings distinguish rejected credentials from ordinary provider failures without raw errors. */
enum class ProviderFailure { REQUEST, CREDENTIALS }

sealed interface ProviderStatus {
    data object Ok : ProviderStatus

    data object Pending : ProviderStatus

    data object Disabled : ProviderStatus

    data object RateLimited : ProviderStatus

    /** No query, URL, credentials or raw response text may appear in this error. */
    data class Failed(
        val error: NetError?,
        val reason: ProviderFailure = ProviderFailure.REQUEST,
    ) : ProviderStatus
}

/** In-memory directory metadata; the repository validates HTTP(S) feed URLs before emission. */
data class DirectoryHit(
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    val description: String? = null,
    val episodeCount: Int? = null,
    val lastEpisodeAt: Long? = null,
    val podcastGuid: String? = null,
    val providers: ImmutableSet<ProviderId>,
    val genres: ImmutableList<String> = persistentListOf(),
    val explicit: Boolean? = null,
    val subscribedPodcastId: Long? = null,
    /** Original one-based ranks survive deduplication and fusion. */
    val ranks: ImmutableMap<ProviderId, Int> = persistentMapOf(),
)

/** All provider statuses are present, including Disabled; complete means no Pending remains. */
data class SearchResults(
    val query: String,
    val hits: ImmutableList<DirectoryHit>,
    val statuses: ImmutableMap<ProviderId, ProviderStatus>,
    val complete: Boolean,
)

/** Stable navigation IDs are Apple's numeric IDs, not localized labels or enum names. */
enum class ChartGenre(
    val id: String,
) {
    TECHNOLOGY("1318"),
    NEWS("1489"),
    FICTION("1483"),
    COMEDY("1303"),
    SCIENCE("1533"),
    HISTORY("1487"),
    SPORTS("1545"),
    TRUE_CRIME("1488"),
    BUSINESS("1321"),
    ARTS("1301"),
    EDUCATION("1304"),
    HEALTH_FITNESS("1512"),
    KIDS_FAMILY("1305"),
    MUSIC("1310"),
    SOCIETY_CULTURE("1324"),
    TV_FILM("1309"),
    RELIGION("1314"),
    GOVERNMENT("1511"),
    LEISURE("1502"),
    ;

    companion object {
        fun fromId(id: String): ChartGenre? = entries.firstOrNull { it.id == id }
    }
}

/** One explicit lookup, scoped to this call; it never changes the provider preference. */
enum class DirectoryAccess { ENABLED_ONLY, ONE_TIME_LOOKUP }
