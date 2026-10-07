// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Duration

/**
 * Deterministic [Clock] for tests (09 Shared helpers). The default instant is fixed at
 * `1_791_072_000_000` = 2026-10-04T00:00:00Z so tests never depend on the wall clock.
 * `TestClock.from(scheduler)` keeps the clock in lockstep with a `runTest`/`StandardTestDispatcher`
 * virtual time — advance the scheduler and the clock sees it.
 */
class TestClock(
    var nowMs: Long = DEFAULT_NOW,
    var elapsedMs: Long = 0L,
) : Clock {
    override fun now(): Long = nowMs

    override fun elapsedRealtime(): Long = elapsedMs

    fun advanceBy(d: Duration) {
        nowMs += d.inWholeMilliseconds
        elapsedMs += d.inWholeMilliseconds
    }

    companion object {
        /** 2026-10-04T00:00:00Z in epoch milliseconds — the deterministic default instant. */
        const val DEFAULT_NOW = 1_791_072_000_000L

        /** A clock driven by [s]'s virtual time so `advanceTimeBy`/`runCurrent` move both. */
        @ExperimentalCoroutinesApi
        fun from(
            s: TestCoroutineScheduler,
            start: Long = DEFAULT_NOW,
        ) = object : Clock {
            override fun now(): Long = start + s.currentTime

            override fun elapsedRealtime(): Long = s.currentTime
        }
    }
}
