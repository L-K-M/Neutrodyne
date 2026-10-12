// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.RefreshPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/** 03 nextRefreshAt policy edges the callers are not expected to pre-filter. */
class RefreshPolicyTest {
    @Test
    fun nonPositiveIntervalSchedulesNever() {
        // A raw `0` ("Manual only") or a negative must not reach `now + interval` — the feed
        // would look perpetually due instead of parked at NEVER.
        assertEquals(
            RefreshPolicy.NEVER,
            RefreshPolicy.successNextRefreshAt(
                NOW,
                0,
                complete = false,
                latestEpisodeAt = null,
                ttlMinutes = null,
                maxAgeSec = null,
            ),
        )
        assertEquals(
            RefreshPolicy.NEVER,
            RefreshPolicy.successNextRefreshAt(
                NOW,
                -1,
                complete = false,
                latestEpisodeAt = null,
                ttlMinutes = null,
                maxAgeSec = null,
            ),
        )
        assertEquals(
            RefreshPolicy.NEVER,
            RefreshPolicy.successNextRefreshAt(
                NOW,
                null,
                complete = false,
                latestEpisodeAt = null,
                ttlMinutes = null,
                maxAgeSec = null,
            ),
        )
    }

    @Test
    fun positiveIntervalSchedulesNowPlusInterval() {
        assertEquals(
            NOW + 60 * 60_000L,
            RefreshPolicy.successNextRefreshAt(
                NOW,
                60,
                complete = false,
                latestEpisodeAt = NOW,
                ttlMinutes = null,
                maxAgeSec = null,
            ),
        )
    }

    private companion object {
        const val NOW = 1_791_000_000_000L
    }
}
