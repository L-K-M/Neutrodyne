// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Filters backed by live producers; Unplayed, media and age filters are always available. */
enum class FeedFilterCapability { IN_PROGRESS, DOWNLOADED }

/** IN_PROGRESS ships with M4 positions; DOWNLOADED with M6 downloads. */
val AVAILABLE_FEED_FILTER_CAPABILITIES: Set<FeedFilterCapability> =
    setOf(FeedFilterCapability.IN_PROGRESS, FeedFilterCapability.DOWNLOADED)

/** Derive effective filters without rewriting stored or synced preferences. */
fun FeedFilters.withCapabilities(available: Set<FeedFilterCapability>): FeedFilters =
    copy(
        downloadedOnly = downloadedOnly && FeedFilterCapability.DOWNLOADED in available,
        inProgressOnly = inProgressOnly && FeedFilterCapability.IN_PROGRESS in available,
    )
