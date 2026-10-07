// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Both clocks a service might need (01 Coroutines and threading): wall-clock epoch milliseconds and
 * monotonic elapsed time (the platform's uptime clock). The device implementation is bound
 * `@SingleIn(AppScope::class)` by the shells; tests substitute `TestClock` from `:core:testing`.
 */
interface Clock {
    /** Wall clock — epoch milliseconds; only for timestamps meant to survive a reboot. */
    fun now(): Long

    /** Monotonic elapsed milliseconds; for durations, timeouts and timeouts bookkeeping. */
    fun elapsedRealtime(): Long
}
