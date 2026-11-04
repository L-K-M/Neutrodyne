// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.net.InetAddress

/**
 * Desktop [LocalNetworkGate]: no runtime permission exists, but plain `http://` stays confined to
 * loopback and local addresses (10 TLS stance).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DesktopSyncLocalNetworkGate
    @Inject
    constructor() : LocalNetworkGate {
        override fun needsPermission(host: String): Boolean = false

        override fun hasPermission(): Boolean = true

        override fun onPermissionResult(granted: Boolean) = Unit

        override fun allowsPlainHttp(host: String): Boolean =
            isLoopback(host) || runCatching { InetAddress.getByName(host).isSiteLocalAddress }.getOrDefault(false)

        override fun location(host: String): LocalNetworkLocation =
            if (runCatching { InetAddress.getByName(host).isSiteLocalAddress }.getOrDefault(false)) {
                LocalNetworkLocation.LOCAL
            } else {
                LocalNetworkLocation.NOT_LOCAL
            }

        private fun isLoopback(host: String): Boolean =
            host.equals("localhost", ignoreCase = true) ||
                runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }
