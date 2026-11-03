// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineClockTest {
    @Test
    fun pauseFreezesThePositionUntilAnotherMarker() {
        var played = 500L
        val clock = TimelineClock(1_000, 0) { played }
        clock.push(TimelineClock.Marker(0, "item", 0, 1f))
        assertEquals(500L, clock.positionMs())
        clock.pause()
        played = 700
        assertEquals(500L, clock.positionMs())
    }

    @Test
    fun clearDoesNotKeepThePreviousItemsPausedPosition() {
        val clock = TimelineClock(1_000, 0) { 500L }
        clock.push(TimelineClock.Marker(0, "item", 0, 1f))
        clock.pause()
        clock.clear()
        assertEquals(0L, clock.positionMs())
    }

    @Test
    fun speedAndSkipMarkersUseConsumedFramesWithLatency() {
        var played = 450L
        val clock = TimelineClock(1_000, 50) { played }
        clock.push(TimelineClock.Marker(0, "item", 0, 1f))
        clock.push(TimelineClock.Marker(500, "item", 2_000_000, 2f))
        assertEquals(400L, clock.positionMs())
        played = 650
        assertEquals(2_200L, clock.positionMs())
    }
}
