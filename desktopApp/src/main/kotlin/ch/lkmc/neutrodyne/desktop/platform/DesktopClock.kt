// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.platform

import ch.lkmc.neutrodyne.core.common.Clock
import java.lang.System

/**
 * The desktop [Clock] (01 Coroutines and threading): the wall clock for timestamps that must
 * survive a reboot, and `System.nanoTime` origin for monotonic durations — the one place on the
 * desktop that may call the JDK time sources directly.
 */
internal object DesktopClock : Clock {
    override fun now(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = System.nanoTime() / NANOS_PER_MILLI

    private const val NANOS_PER_MILLI = 1_000_000L
}
