// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The overflow-safe time-base conversions of 11 Demux and decode. */
class FfmpegTimeTest {
    @Test
    fun ticksToScaledKeepsNormalTimeBases() {
        assertEquals(1L, FfmpegTime.ticksToScaled(1, 1, 1_000, FfmpegTime.MILLISECONDS_PER_SECOND))
        assertEquals(2_000L, FfmpegTime.ticksToScaled(180_000, 1, 90_000, FfmpegTime.MILLISECONDS_PER_SECOND))
        assertEquals(1_000_000L, FfmpegTime.ticksToScaled(1, 1, 1, FfmpegTime.MICROS_PER_SECOND))
        assertEquals(0L, FfmpegTime.ticksToScaled(0, 1, 1_000, FfmpegTime.MILLISECONDS_PER_SECOND))
    }

    @Test
    fun ticksToScaledSaturatesInsteadOfWrapping() {
        val huge = Long.MAX_VALUE / 1_000 + 1
        assertEquals(Long.MAX_VALUE, FfmpegTime.ticksToScaled(huge, 1, 1, FfmpegTime.MICROS_PER_SECOND))
        assertEquals(Long.MIN_VALUE, FfmpegTime.ticksToScaled(-huge, 1, 1, FfmpegTime.MICROS_PER_SECOND))
        // A malformed rational reports no time rather than throwing during open.
        assertEquals(0L, FfmpegTime.ticksToScaled(1, 1, 0, FfmpegTime.MILLISECONDS_PER_SECOND))
        assertEquals(0L, FfmpegTime.ticksToScaled(1, 1, -5, FfmpegTime.MILLISECONDS_PER_SECOND))
    }

    @Test
    fun msToTicksKeepsNormalTimeBasesAndSaturates() {
        assertEquals(90_000L, FfmpegTime.msToTicks(1_000, 1, 90_000))
        assertEquals(1_000L, FfmpegTime.msToTicks(1_000, 1, 1_000))
        assertEquals(Long.MAX_VALUE, FfmpegTime.msToTicks(Long.MAX_VALUE, 1, 2))
        assertEquals(0L, FfmpegTime.msToTicks(1_000, 0, 1))
    }

    @Test
    fun defaultMasksFollowFfmpegNineChannelLayoutDefaults() {
        assertEquals(0x4L, defaultChannelMask(1))
        assertEquals(0x3L, defaultChannelMask(2))
        assertEquals(0x7L, defaultChannelMask(3))
        assertEquals(0x107L, defaultChannelMask(4))
        assertEquals(0x37L, defaultChannelMask(5))
        assertEquals(0x3FL, defaultChannelMask(6))
        assertEquals(0x70FL, defaultChannelMask(7))
        assertEquals(0x63FL, defaultChannelMask(8))
        // Unusual counts keep the stereo fallback.
        assertEquals(0x3L, defaultChannelMask(0))
        assertEquals(0x3L, defaultChannelMask(9))
    }
}
