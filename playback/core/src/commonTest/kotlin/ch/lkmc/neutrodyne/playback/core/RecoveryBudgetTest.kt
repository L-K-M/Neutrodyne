// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryBudgetTest {
    private val clock = TestClock()
    private val budget = RecoveryBudget(clock)

    @Test fun reprepareAllowsThreeThenRefusesInsideWindow() {
        repeat(3) { assertTrue(budget.tryReprepare(1)) }
        assertFalse(budget.tryReprepare(1))
        assertFalse(budget.tryReprepare(1))
    }

    @Test fun reprepareWindowIsRollingPerAttempt() {
        clock.elapsedMs = 0
        assertTrue(budget.tryReprepare(1))
        clock.elapsedMs = 60_000
        assertTrue(budget.tryReprepare(1))
        clock.elapsedMs = 119_999
        assertTrue(budget.tryReprepare(1))
        assertFalse(budget.tryReprepare(1))

        // At exactly two minutes the oldest attempt expires and frees one slot.
        clock.elapsedMs = 120_000
        assertTrue(budget.tryReprepare(1))
        assertFalse(budget.tryReprepare(1))

        // The second attempt expires at 180s, freeing exactly one more slot.
        clock.elapsedMs = 180_000
        assertTrue(budget.tryReprepare(1))
        assertFalse(budget.tryReprepare(1))
    }

    @Test fun reprepareBudgetsAreIndependentPerItem() {
        repeat(3) { assertTrue(budget.tryReprepare(1)) }
        assertFalse(budget.tryReprepare(1))

        repeat(3) { assertTrue(budget.tryReprepare(2)) }
        assertFalse(budget.tryReprepare(2))
        assertFalse(budget.tryReprepare(1))
    }

    @Test fun wallClockJumpsDoNotExpireTheMonotonicWindow() {
        repeat(3) { assertTrue(budget.tryReprepare(1)) }
        clock.nowMs += 10 * 60_000
        // Only elapsedRealtime drives the window, so the budget is still spent.
        assertFalse(budget.tryReprepare(1))
    }

    @Test fun fiveSkipsExecuteThenTheNextIsRefused() {
        repeat(5) { assertTrue(budget.trySkip()) }
        assertFalse(budget.trySkip())
        assertFalse(budget.trySkip())
    }

    @Test fun onPlayingResetsSkipsButNotReprepares() {
        repeat(5) { assertTrue(budget.trySkip()) }
        budget.onPlaying()
        repeat(5) { assertTrue(budget.trySkip()) }
        assertFalse(budget.trySkip())

        repeat(3) { assertTrue(budget.tryReprepare(1)) }
        budget.onPlaying()
        assertFalse(budget.tryReprepare(1))
    }

    @Test fun clearResetsBothBudgets() {
        repeat(3) { assertTrue(budget.tryReprepare(1)) }
        repeat(5) { assertTrue(budget.trySkip()) }
        budget.clear()
        assertTrue(budget.tryReprepare(1))
        assertTrue(budget.trySkip())
    }
}
