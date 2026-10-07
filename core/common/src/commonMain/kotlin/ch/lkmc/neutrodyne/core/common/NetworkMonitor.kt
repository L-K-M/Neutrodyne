// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlinx.coroutines.flow.StateFlow

/** A snapshot of reachability — never probed on demand, kept hot by [NetworkMonitor] (01). */
data class NetworkStatus(
    val isConnected: Boolean,
    val isValidated: Boolean,
    val isMetered: Boolean,
    val isVpn: Boolean,
)

/**
 * The app's view of connectivity (01 Networking baseline): Android binds `ConnectivityManager`
 * (`registerDefaultNetworkCallback`), desktop a JDK poll on a 5s `IO` timer with reachability +
 * DNS probe + interfaces-walk plus the `NetworkChangeNotifier` boost when bound (11 OS services).
 * Bind `@SingleIn(AppScope::class)`; tests substitute `FakeNetworkMonitor`.
 */
interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
}
