// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Filters whose producers arrive after M2; Unplayed, media and age filters are always available. */
enum class FeedFilterCapability { IN_PROGRESS, DOWNLOADED }

/** Enable IN_PROGRESS with M4 positions and DOWNLOADED with M6 downloads. */
val AVAILABLE_FEED_FILTER_CAPABILITIES: Set<FeedFilterCapability> = emptySet()

/** Derive effective filters without rewriting stored or synced preferences. */
fun FeedFilters.withCapabilities(available: Set<FeedFilterCapability>): FeedFilters =
    copy(
        downloadedOnly = downloadedOnly && FeedFilterCapability.DOWNLOADED in available,
        inProgressOnly = inProgressOnly && FeedFilterCapability.IN_PROGRESS in available,
    )
