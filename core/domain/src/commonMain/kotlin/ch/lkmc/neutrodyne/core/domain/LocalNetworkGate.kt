// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * Where a sync server host sits, for the local-network gate's verdicts (10 Local-network gate).
 * Loopback is deliberately not "local": Android does not need `ACCESS_LOCAL_NETWORK` for it, but
 * plain `http://` is still admitted to it.
 */
enum class LocalNetworkLocation { NOT_LOCAL, LOCAL }

/**
 * 10 Local-network gate, common seam: how the link flow treats a server address on this platform
 * before discovery. Android implements it with `ACCESS_LOCAL_NETWORK`; the desktop never needs
 * the runtime permission but keeps the same plain-http rule.
 */
interface LocalNetworkGate {
    /** Whether reaching [host] needs Android's `ACCESS_LOCAL_NETWORK` on this device. */
    fun needsPermission(host: String): Boolean

    /** Whether the runtime permission is currently granted (always true off Android 17). */
    fun hasPermission(): Boolean

    /** Applies the Android permission result to the shared LAN-guard bypass. */
    fun onPermissionResult(granted: Boolean)

    /** Plain `http://` is admitted for loopback and local addresses only (10 TLS stance). */
    fun allowsPlainHttp(host: String): Boolean

    /** Classifies [host]; exposed for diagnostics. */
    fun location(host: String): LocalNetworkLocation
}
