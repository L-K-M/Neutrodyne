// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo

/** The gate's verdict for one server address (10 Local-network gate). */
sealed interface LocalNetworkClassification {
    /** Loopback or a public address: no permission needed. */
    data object NotLocal : LocalNetworkClassification

    /** A local-network address: API 37+ needs `ACCESS_LOCAL_NETWORK`. */
    data object Local : LocalNetworkClassification
}

/**
 * 10 Local-network gate: classifies the server before the discovery request (without the
 * permission that request would time out), and sets [LocalNetworkAccess.syncAllowed] only while
 * the permission is granted. Every other client keeps the guard, so LAN feeds still show
 * `LocalNetworkUnsupported`.
 */
class LocalNetworkPermissionGate(
    private val context: Context,
    private val platform: PlatformInfo,
    private val access: LocalNetworkAccess,
) {
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Whether the runtime permission is granted (below API 37 it is always true). */
    fun hasPermission(): Boolean {
        val sdkInt = platform.androidSdkInt
        if (sdkInt == null || sdkInt < 37) return true
        return context.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Re-classifies after the permission result; grants the SYNC bypass while granted. */
    fun onPermissionResult(granted: Boolean) {
        access.setSyncAllowed(granted)
    }

    /** Classifies the host: `.local` by name, else the active network's resolved addresses. */
    fun classify(host: String): LocalNetworkClassification {
        if (host.endsWith(".local", ignoreCase = true)) return LocalNetworkClassification.Local
        // An IP literal needs no resolution (and DNS itself needs the permission).
        runCatching { java.net.InetAddress.getByName(host) }.getOrNull()?.let { literal ->
            return if (isLocalAddress(
                    literal,
                )
            ) {
                LocalNetworkClassification.Local
            } else {
                LocalNetworkClassification.NotLocal
            }
        }
        val addresses = resolve(host)
        return if (addresses.isNotEmpty() && addresses.all { isLocalAddress(it) }) {
            LocalNetworkClassification.Local
        } else {
            LocalNetworkClassification.NotLocal
        }
    }

    private fun resolve(host: String): List<java.net.InetAddress> {
        val network = connectivity?.activeNetwork ?: return emptyList()
        val properties = connectivity?.getLinkProperties(network) ?: return emptyList()
        return runCatching {
            java.net.InetAddress
                .getAllByName(host)
                .toList()
        }.getOrDefault(emptyList())
    }

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /**
         * Android's local-network definition (10 Local-network gate 1): IPv4 private, link-local,
         * CGNAT, multicast and broadcast; IPv6 link-local. Loopback is not local.
         */
        fun isLocalAddress(address: java.net.InetAddress): Boolean {
            if (address.isLoopbackAddress) return false
            val bytes = address.address
            if (bytes.size == 4) {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168) ||
                    (first == 169 && second == 254) || (first == 100 && second in 64..127) ||
                    (first in 224..239) || (first == 255 && second == 255)
            }
            if (bytes.size == 16) {
                val first = bytes[0].toInt() and 0xFF
                return (first and 0xFE) == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80
            }
            return false
        }
    }
}
