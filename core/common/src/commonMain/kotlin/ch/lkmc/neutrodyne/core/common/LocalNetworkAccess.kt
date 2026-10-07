// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one writer for the Android LAN-guard bypass (01 Interceptors). Only 10's
 * `LocalNetworkPermissionGate` calls [setSyncAllowed]; `:core:network:okhttp`'s
 * `LocalNetworkGuard` reads [syncAllowed] to let the SYNC client reach a LAN sync endpoint while
 * `ACCESS_LOCAL_NETWORK` is granted on API 37+. On the desktop the guard is a pass-through, so the
 * flag is never read there.
 */
@SingleIn(AppScope::class)
@Inject
class LocalNetworkAccess {
    private val _syncAllowed = MutableStateFlow(false)

    /** Whether sync may reach private-range addresses; `false` until the gate grants it. */
    val syncAllowed: StateFlow<Boolean> = _syncAllowed.asStateFlow()

    fun setSyncAllowed(allowed: Boolean) {
        _syncAllowed.value = allowed
    }
}
