// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mutable [NetworkMonitor] for tests (09 Shared helpers): [setStatus] flips the flow
 * synchronously — no sleeping, no platform callbacks.
 */
class FakeNetworkMonitor(
    initial: NetworkStatus = OFFLINE,
) : NetworkMonitor {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    fun setStatus(status: NetworkStatus) {
        _status.value = status
    }

    companion object {
        val OFFLINE =
            NetworkStatus(
                isConnected = false,
                isValidated = false,
                isMetered = false,
                isVpn = false,
            )
        val ONLINE =
            NetworkStatus(
                isConnected = true,
                isValidated = true,
                isMetered = false,
                isVpn = false,
            )
    }
}
