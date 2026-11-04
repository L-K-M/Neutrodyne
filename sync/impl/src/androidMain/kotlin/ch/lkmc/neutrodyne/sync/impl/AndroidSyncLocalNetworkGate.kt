// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.content.Context
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.net.InetAddress

/**
 * Android [LocalNetworkGate]: delegates to [LocalNetworkPermissionGate] so the link flow and the
 * feed clients share one classification (10 Local-network gate).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidSyncLocalNetworkGate
    @Inject
    constructor(
        context: Context,
        platform: PlatformInfo,
        access: LocalNetworkAccess,
    ) : LocalNetworkGate {
        private val gate = LocalNetworkPermissionGate(context, platform, access)

        override fun needsPermission(host: String): Boolean =
            gate.classify(host) == LocalNetworkClassification.Local && !gate.hasPermission()

        override fun hasPermission(): Boolean = gate.hasPermission()

        override fun onPermissionResult(granted: Boolean) = gate.onPermissionResult(granted)

        override fun allowsPlainHttp(host: String): Boolean =
            isLoopback(host) || gate.classify(host) == LocalNetworkClassification.Local

        override fun location(host: String): LocalNetworkLocation =
            if (gate.classify(host) == LocalNetworkClassification.Local) {
                LocalNetworkLocation.LOCAL
            } else {
                LocalNetworkLocation.NOT_LOCAL
            }

        private fun isLoopback(host: String): Boolean =
            host.equals("localhost", ignoreCase = true) ||
                runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }
