// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.LocalNetworkGate
import ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation

/**
 * A scripted [LocalNetworkGate]: the classification and the permission are settable, so link-flow
 * tests can drive both the granted and the denied path without Android.
 */
class FakeLocalNetworkGate(
    location: LocalNetworkLocation = LocalNetworkLocation.NOT_LOCAL,
    permissionGranted: Boolean = true,
) : LocalNetworkGate {
    var location: LocalNetworkLocation = location
        private set

    var permissionGranted: Boolean = permissionGranted
        private set

    /** Plain-http verdict; defaults to the loopback/local rule the platform gates implement. */
    var plainHttpAllowed: Boolean? = null
        private set

    val results = mutableListOf<Boolean>()

    fun setLocation(value: LocalNetworkLocation) {
        location = value
    }

    fun setPermissionGranted(value: Boolean) {
        permissionGranted = value
    }

    fun setPlainHttpAllowed(value: Boolean?) {
        plainHttpAllowed = value
    }

    override fun needsPermission(host: String): Boolean = location == LocalNetworkLocation.LOCAL && !permissionGranted

    override fun hasPermission(): Boolean = permissionGranted

    override fun onPermissionResult(granted: Boolean) {
        results += granted
        permissionGranted = granted
    }

    override fun allowsPlainHttp(host: String): Boolean =
        plainHttpAllowed ?: (location != LocalNetworkLocation.NOT_LOCAL)

    override fun location(host: String): LocalNetworkLocation = location
}
