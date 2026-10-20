// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StartPositionRuleTest {
    private val rule = StartPositionRule()

    @Test fun playedOrMissingProgressStartsFromZero() {
        assertEquals(0, start(500_000, durationMs = 1_200_000, played = true))
        assertEquals(0, start(null, durationMs = 1_200_000))
    }

    @Test fun savedPositionOfFiveSecondsOrLessStartsFromZero() {
        assertEquals(0, start(0, durationMs = 1_200_000))
        assertEquals(0, start(5_000, durationMs = 1_200_000))
        assertEquals(5_001, start(5_001, durationMs = 1_200_000))
    }

    @Test fun savedInsideLastThirtySecondsStartsFromZero() {
        // 20min episode: positions from 19:30 on count as finished.
        assertEquals(1_169_999, start(1_169_999, durationMs = 1_200_000))
        assertEquals(0, start(1_170_000, durationMs = 1_200_000))
        assertEquals(0, start(1_500_000, durationMs = 1_200_000))
        // 3h episode: boundary at 2:59:30.
        assertEquals(0, start(10_770_000, durationMs = 10_800_000))
        // A 20s episode is shorter than the ignore window and always restarts.
        assertEquals(0, start(15_000, durationMs = 20_000))
    }

    @Test fun unknownDurationKeepsSavedPosition() {
        assertEquals(60_000, start(60_000, durationMs = null))
        assertEquals(60_000, start(60_000, durationMs = 0))
        assertEquals(60_000, start(60_000, durationMs = -1))
    }

    @Test fun smartRewindAppliesOnlyAfterStrictlyFiveMinutesIdle() {
        // Exactly five minutes idle is not stale.
        assertEquals(60_000, start(60_000, savedAt = 10_000, now = 310_000, smartResume = true))
        // One millisecond more rewinds 5s.
        assertEquals(55_000, start(60_000, savedAt = 10_000, now = 310_001, smartResume = true))
        // A clock at or before the save time never rewinds.
        assertEquals(60_000, start(60_000, savedAt = 10_000, now = 10_000, smartResume = true))
        assertEquals(60_000, start(60_000, savedAt = 10_000, now = 5_000, smartResume = true))
        // Disabled smart resume and undated saves never rewind.
        assertEquals(60_000, start(60_000, savedAt = 10_000, now = 10_000_000, smartResume = false))
        assertEquals(60_000, start(60_000, savedAt = null, now = 10_000_000, smartResume = true))
    }

    @Test fun smartRewindNeverProducesANegativePosition() {
        // 5_001ms is the smallest resumable position; rewinding leaves 1ms.
        assertEquals(1, start(5_001, savedAt = 0, now = 300_001, smartResume = true))
        assertEquals(1_000, start(6_000, savedAt = 0, now = 300_001, smartResume = true))
    }

    private fun start(
        saved: Long?,
        savedAt: Long? = null,
        durationMs: Long? = null,
        played: Boolean = false,
        now: Long = 0,
        smartResume: Boolean = false,
    ) = rule.startPositionMs(saved, savedAt, durationMs, played, now, smartResume)
}
