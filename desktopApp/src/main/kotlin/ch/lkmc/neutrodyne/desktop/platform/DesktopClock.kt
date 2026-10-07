// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.platform

import ch.lkmc.neutrodyne.core.common.Clock

/** The desktop [Clock]: wall time from the system, elapsed time from `nanoTime` (monotonic). */
internal object DesktopClock : Clock {
    private val epochNanos = System.nanoTime()

    override fun now(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = (System.nanoTime() - epochNanos) / NANOS_PER_MILLI

    private const val NANOS_PER_MILLI = 1_000_000L
}
