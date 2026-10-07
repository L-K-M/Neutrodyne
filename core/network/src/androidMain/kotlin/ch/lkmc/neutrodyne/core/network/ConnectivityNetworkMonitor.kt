// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * `NetworkMonitor` on top of `ConnectivityManager` (01 NetworkMonitor): one
 * `registerDefaultNetworkCallback` mapping `NetworkCapabilities` onto `NetworkStatus`. The flow
 * shares `Eagerly` and seeds synchronously from `activeNetwork` — consumers read `status.value`
 * without ever collecting, so the initial value must already be right.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class ConnectivityNetworkMonitor
    @Inject
    constructor(
        app: Application,
        @ApplicationScope scope: CoroutineScope,
    ) : NetworkMonitor {
        private val manager = checkNotNull(app.getSystemService(ConnectivityManager::class.java))

        override val status: StateFlow<NetworkStatus> =
            callbackFlow {
                val callback =
                    object : NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trySend(current())
                        }

                        override fun onLost(network: Network) {
                            trySend(current())
                        }

                        override fun onUnavailable() {
                            trySend(current())
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            caps: NetworkCapabilities,
                        ) {
                            trySend(current())
                        }

                        override fun onBlockedStatusChanged(
                            network: Network,
                            blocked: Boolean,
                        ) {
                            trySend(current())
                        }
                    }
                manager.registerDefaultNetworkCallback(callback)
                awaitClose { manager.unregisterNetworkCallback(callback) }
            }.stateIn(scope, SharingStarted.Eagerly, current())

        private fun current(): NetworkStatus {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return DISCONNECTED
            val unmetered =
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                    )
            return NetworkStatus(
                isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                // NOT_METERED wins; on API 30+ a TEMPORARILY_NOT_METERED network is unmetered too.
                isMetered = !unmetered,
                isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            )
        }

        private companion object {
            val DISCONNECTED =
                NetworkStatus(
                    isConnected = false,
                    isValidated = false,
                    isMetered = false,
                    isVpn = false,
                )
        }
    }
