// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.RequiresPermission
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.youtube.engine.DefaultNetworkReset
import ch.lkmc.neutrodyne.youtube.engine.IpFamilyHints
import java.io.Closeable

/** No startup registration or production binding. W11 owns lifetime when activation is approved. */
class AndroidIpFamilyHints(
    private val hints: DnsFamilyHints,
) : IpFamilyHints {
    override fun current(): IpFamily? = hints.familyFor("googlevideo.com")

    override fun set(family: IpFamily?) {
        hints.set("googlevideo.com", family)
    }
}

/** Uses actual default Network identity, independent of reachability/metering equality. */
class AndroidDefaultNetworkReset
    @RequiresPermission(ACCESS_NETWORK_STATE)
    constructor(
        private val connectivity: ConnectivityManager,
        reset: () -> Unit,
    ) : Closeable {
        private val changes = DefaultNetworkReset(reset)
        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    changes.changed(network)
                }

                @RequiresPermission(ACCESS_NETWORK_STATE)
                override fun onLost(network: Network) {
                    // A late loss for the previous network must not clear the current identity.
                    changes.changed(connectivity.activeNetwork)
                }
            }

        init {
            connectivity.registerDefaultNetworkCallback(callback)
            changes.changed(connectivity.activeNetwork)
        }

        override fun close() {
            connectivity.unregisterNetworkCallback(callback)
        }
    }
