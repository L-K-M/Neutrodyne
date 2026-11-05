// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** The output cursor's advance for one block, at sizes beyond Int range once multiplied by the rate. */
class DeviceFramesTest {
    @Test
    fun largeBlocksAdvanceTheCursorWithoutOverflow() {
        // 100,000 x 48,000 exceeds Int.MAX_VALUE; a FLAC block or the 1 MiB skipper buffer reaches it.
        assertEquals(100_000.0, deviceFrames(100_000, 48_000, 48_000, 1f))
        assertEquals(65_535.0 * 48_000 / 44_100, deviceFrames(65_535, 44_100, 48_000, 1f), 1e-6)
        assertEquals(262_144.0 / 3, deviceFrames(262_144, 48_000, 48_000, 3f), 1e-6)
    }
}
