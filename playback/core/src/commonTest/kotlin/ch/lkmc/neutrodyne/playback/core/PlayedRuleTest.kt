// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayedRuleTest {
    private val rule = PlayedRule()

    @Test fun durationPrefersPlayerThenMeasuredThenFeed() {
        assertEquals(100L, rule.duration(100, 200, 300))
        assertEquals(200L, rule.duration(null, 200, 300))
        assertEquals(300L, rule.duration(null, null, 300))
        assertNull(rule.duration(null, null, null))
    }

    @Test fun durationSkipsNonPositiveHints() {
        assertEquals(200L, rule.duration(0, 200, 300))
        assertEquals(50L, rule.duration(-5, 0, 50))
        assertNull(rule.duration(0, -1, null))
    }

    @Test fun nearEndIsFalseWithoutAKnownDuration() {
        assertFalse(rule.isNearEnd(Long.MAX_VALUE, null))
        assertFalse(rule.isNearEnd(Long.MAX_VALUE, 0))
        assertFalse(rule.isNearEnd(Long.MAX_VALUE, -30_000))
    }

    @Test fun thirtySecondFloorCoversWholeShortEpisode() {
        // 20s: 3% is 600ms, so the 30s floor makes every position near the end.
        assertTrue(rule.isNearEnd(0, 20_000))
        assertTrue(rule.isNearEnd(19_999, 20_000))
    }

    @Test fun thirtySecondFloorBoundsUntilThreePercentWins() {
        // 60s: 3% is 1.8s, near end still starts at 30s.
        assertFalse(rule.isNearEnd(29_999, 60_000))
        assertTrue(rule.isNearEnd(30_000, 60_000))
        // 999s: 3% is 29_970ms, just under the floor; boundary stays at duration - 30s.
        assertFalse(rule.isNearEnd(968_999, 999_000))
        assertTrue(rule.isNearEnd(969_000, 999_000))
    }

    @Test fun threePercentBoundsLongEpisodes() {
        // 20min: 3% is 36s, near end starts at 19:24.
        assertFalse(rule.isNearEnd(1_163_999, 1_200_000))
        assertTrue(rule.isNearEnd(1_164_000, 1_200_000))
        // 3h: 3% is 324s, near end starts at 2:54:36.
        assertFalse(rule.isNearEnd(10_475_999, 10_800_000))
        assertTrue(rule.isNearEnd(10_476_000, 10_800_000))
    }

    @Test fun autoEndAndEndOfItemPauseAlwaysMarkPlayed() {
        assertTrue(mark(PlaybackExit.AUTO, 0, null, hasPlayed = false))
        assertTrue(mark(PlaybackExit.END, 0, null, hasPlayed = false))
        assertTrue(mark(PlaybackExit.END_OF_ITEM_PAUSE, 5_000, 20_000, hasPlayed = false))
    }

    @Test fun pauseErrorSkipAndDismissNeverMarkPlayed() {
        // A real near-end position with recorded progress still must not mark.
        assertFalse(mark(PlaybackExit.MARKED_PLAYED, 1_199_999, 1_200_000, hasPlayed = true))
        assertFalse(mark(PlaybackExit.PAUSE, 1_199_999, 1_200_000, hasPlayed = true))
        assertFalse(mark(PlaybackExit.ERROR_SKIP, 1_199_999, 1_200_000, hasPlayed = true))
        assertFalse(mark(PlaybackExit.DISMISS, 1_199_999, 1_200_000, hasPlayed = true))
    }

    @Test fun seekOrNewStartNearEndMarksOnlyWithProgress() {
        assertTrue(mark(PlaybackExit.SEEK, 1_164_000, 1_200_000, hasPlayed = true))
        assertTrue(mark(PlaybackExit.NEW_START, 1_164_000, 1_200_000, hasPlayed = true))
        assertFalse(mark(PlaybackExit.SEEK, 1_163_999, 1_200_000, hasPlayed = true))
        assertFalse(mark(PlaybackExit.NEW_START, 10_475_999, 10_800_000, hasPlayed = true))
    }

    @Test fun neverStartedSkipDoesNotMarkEvenAtTheEnd() {
        assertFalse(mark(PlaybackExit.SEEK, 1_200_000, 1_200_000, hasPlayed = false))
        assertFalse(mark(PlaybackExit.NEW_START, 1_200_000, 1_200_000, hasPlayed = false))
        // Unknown duration can never be near the end either.
        assertFalse(mark(PlaybackExit.SEEK, Long.MAX_VALUE, null, hasPlayed = true))
    }

    private fun mark(
        exit: PlaybackExit,
        positionMs: Long,
        durationMs: Long?,
        hasPlayed: Boolean,
    ) = rule.shouldMarkPlayed(exit, positionMs, durationMs, hasPlayed)
}
