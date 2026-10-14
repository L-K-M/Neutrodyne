// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.common.Clock
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The `LocalUiClock` fallback (no shell-provided clock): `elapsedRealtime()` must report real
 * monotonic milliseconds from one process-wide origin (01's Clock contract), not mark-and-read
 * in the same expression.
 */
@OptIn(ExperimentalTestApi::class)
class UiLocalsTest {
    @Test
    fun uiClockFallbackCountsMonotonicTime() =
        runDesktopComposeUiTest(width = 100, height = 100) {
            lateinit var clock: Clock
            setContent { clock = LocalUiClock.current }
            waitForIdle()

            val first = clock.elapsedRealtime()
            Thread.sleep(50)
            val second = clock.elapsedRealtime()
            assertTrue(
                second > first,
                "elapsedRealtime must advance (read $first, then $second after a 50 ms sleep)",
            )
        }
}
