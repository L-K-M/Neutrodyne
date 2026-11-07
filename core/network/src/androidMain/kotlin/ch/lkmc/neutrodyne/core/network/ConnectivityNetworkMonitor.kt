// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * `NetworkMonitor` on top of `ConnectivityManager` (01 NetworkMonitor): one
 * `registerDefaultNetworkCallback` mapping `NetworkCapabilities` onto `NetworkStatus`. The
 * callback registers synchronously at injection and `status` seeds synchronously from
 * `activeNetwork` — consumers read `status.value` without ever collecting, so both must
 * already be right without waiting on a dispatcher.
 *
 * After the seed the delivered callback arguments are authoritative: re-reading
 * `getNetworkCapabilities(activeNetwork)` inside a callback races the default-network swap —
 * and on the API 26 managed-device image that synchronous read stayed stale for tens of
 * seconds while the callback stream already carried the metered cellular caps (CI runs
 * 42113975704, 42145320791). A reported `blocked` maps to DISCONNECTED because the uid-level
 * filter makes `activeNetwork`/`NetworkInfo` report the same.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class ConnectivityNetworkMonitor
    @Inject
    constructor(
        app: Application,
    ) : NetworkMonitor {
        private val manager = checkNotNull(app.getSystemService(ConnectivityManager::class.java))

        private val mutableStatus =
            MutableStateFlow(manager.getNetworkCapabilities(manager.activeNetwork).toStatus())
        override val status: StateFlow<NetworkStatus> = mutableStatus

        private var network: Network? = null
        private var caps: NetworkCapabilities? = null
        private var blocked = false

        private val callback =
            object : NetworkCallback() {
                private fun emit() {
                    mutableStatus.value = if (blocked) DISCONNECTED else caps.toStatus()
                }

                private fun update(
                    network: Network,
                    delivered: NetworkCapabilities?,
                ) {
                    this@ConnectivityNetworkMonitor.network = network
                    caps = delivered
                    emit()
                }

                // 2026-11-04, CI runs 42113975704/42145320791: on API 26 a synchronous
                // getNetworkCapabilities read inside onAvailable can return the previous
                // network's (or no) capabilities while the callback itself already carries
                // the new network's state. Delivered capabilities are authoritative.
                override fun onAvailable(network: Network) = update(network, manager.getNetworkCapabilities(network))

                // onCapabilitiesChanged fires for every capability update on the tracked
                // network; the delivered value replaces the synchronous-read result.
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) = update(network, caps)

                // A retired default's LOST can arrive after the new default's events;
                // only the tracked network's loss clears the status.
                override fun onLost(network: Network) {
                    if (this@ConnectivityNetworkMonitor.network != network) return
                    this@ConnectivityNetworkMonitor.network = null
                    caps = null
                    emit()
                }

                override fun onUnavailable() {
                    network = null
                    caps = null
                    emit()
                }

                override fun onBlockedStatusChanged(
                    network: Network,
                    blocked: Boolean,
                ) {
                    this@ConnectivityNetworkMonitor.blocked = blocked
                    emit()
                }
            }

        init {
            // Register synchronously: inside a callbackFlow produce, the request only
            // reaches ConnectivityService once the collecting coroutine dispatches. On
            // the loaded API 26 managed-device image that dispatch queued long enough
            // to miss the whole Wi-Fi to cellular transition, freezing status at its
            // construction seed for the test's whole 30 s await (CI run 42328682766).
            manager.registerDefaultNetworkCallback(callback)
        }

        private fun NetworkCapabilities?.toStatus(): NetworkStatus =
            if (this == null || !hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                DISCONNECTED
            } else {
                NetworkStatus(
                    isConnected = true,
                    isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    isMetered = isMetered(),
                    isVpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                )
            }

        // NET_CAPABILITY_NOT_METERED is absent when the network is metered;
        // API 30+ adds a second signal used while a metered override applies.
        private fun NetworkCapabilities.isMetered(): Boolean =
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                        !hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                )

        private companion object {
            val DISCONNECTED = NetworkStatus(isConnected = false, isValidated = false, isMetered = false, isVpn = false)
        }
    }
