// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlinx.coroutines.flow.Flow

/**
 * Suspend/resume notices for the desktop runner and playback (11 Runner contract, Power).
 * Implemented by `OsPowerMonitor` in `:desktop:system` and bound in `DesktopAppGraph`.
 */
interface PowerMonitor {
    val events: Flow<PowerEvent>
}

enum class PowerEvent { Suspending, Resumed }
