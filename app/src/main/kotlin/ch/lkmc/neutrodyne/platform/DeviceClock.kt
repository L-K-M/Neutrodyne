// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.platform

import android.os.SystemClock
import ch.lkmc.neutrodyne.core.common.Clock

/** The Android [Clock]: wall time from the system, elapsed time from [SystemClock] (counts deep sleep). */
internal object DeviceClock : Clock {
    override fun now(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
