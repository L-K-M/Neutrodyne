// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrubberClockTest {
    @Test fun interpolationUsesElapsedRealtimeIncludingDeepSleep() {
        val clock = TestClock(nowMs = 2_000_000, elapsedMs = 91_000)
        val snapshot = samplePosition(advancing = true).copy(sampledAtElapsedMs = 90_000, speed = 1.5f)
        assertEquals(13_500L, snapshot.positionAt(clock.elapsedRealtime()))
        clock.nowMs -= 100_000
        assertEquals(13_500L, snapshot.positionAt(clock.elapsedRealtime()))
        clock.elapsedMs += 2_000
        assertEquals(16_500L, snapshot.positionAt(clock.elapsedRealtime()))
    }

    @Test fun pauseAndBufferingDoNotAdvance() {
        assertEquals(12_000L, samplePosition().positionAt(99_000_000))
    }

    @Test fun clampsToDurationAndIgnoresEarlierClock() {
        assertEquals(60_000L, samplePosition(advancing = true).positionAt(99_000_000))
        assertEquals(12_000L, samplePosition(advancing = true).positionAt(0))
    }

    @Test fun unknownDurationStillInterpolates() {
        assertEquals(13_000L, samplePosition(advancing = true).copy(durationMs = null).positionAt(2_000))
    }

    @Test fun formattingUsesContentTimeWithoutJvmFormatting() {
        assertEquals("0:00", playerTime(-1))
        assertEquals("12:04", playerTime(724_000))
        assertEquals("125:05", playerTime(7_505_000))
    }
}
