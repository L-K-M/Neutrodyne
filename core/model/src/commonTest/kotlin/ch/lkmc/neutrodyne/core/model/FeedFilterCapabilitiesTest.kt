// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedFilterCapabilitiesTest {
    /** M4 (W10): In progress is the only producer-backed chip; Downloaded waits for M6. */
    @Test
    fun availableCapabilitiesEnableInProgressOnly() {
        assertTrue(FeedFilterCapability.IN_PROGRESS in AVAILABLE_FEED_FILTER_CAPABILITIES)
        assertFalse(FeedFilterCapability.DOWNLOADED in AVAILABLE_FEED_FILTER_CAPABILITIES)
        assertEquals(
            setOf(FeedFilterCapability.IN_PROGRESS),
            FeedFilterCapability.entries.toSet() intersect AVAILABLE_FEED_FILTER_CAPABILITIES,
        )
    }

    /** Gated flags drop out of the effective filters; stored values are never rewritten (08). */
    @Test
    fun gatedFlagsDropWithoutTouchingTheRest() {
        val stored =
            FeedFilters(
                unplayedOnly = true,
                downloadedOnly = true,
                inProgressOnly = true,
                media = MediaFilter.AUDIO,
                minSortDate = 42,
            )
        assertEquals(
            stored.copy(downloadedOnly = false, inProgressOnly = false),
            stored.withCapabilities(emptySet()),
        )
        assertEquals(stored, stored.withCapabilities(FeedFilterCapability.entries.toSet()))
        assertEquals(
            stored.copy(downloadedOnly = false),
            stored.withCapabilities(setOf(FeedFilterCapability.IN_PROGRESS)),
        )
        assertEquals(
            FeedFilters(),
            FeedFilters().withCapabilities(emptySet()),
        )
    }

    /** The milestone set keeps a stored In-progress flag but still hides a stored Downloaded one. */
    @Test
    fun milestoneSetKeepsInProgressAndDropsDownloaded() {
        val stored = FeedFilters(downloadedOnly = true, inProgressOnly = true)
        assertEquals(
            FeedFilters(inProgressOnly = true),
            stored.withCapabilities(AVAILABLE_FEED_FILTER_CAPABILITIES),
        )
    }
}
