// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

/** One network interface, reduced to the facts the monitor needs (tests fake it directly). */
internal data class NdInterface(
    val name: String,
    val up: Boolean,
    val loopback: Boolean,
    val pointToPoint: Boolean,
    val addresses: List<InetAddress>,
)

/** Where interfaces come from — `java.net.NetworkInterface` in production, a fake in tests. */
internal fun interface NetworkInterfaceSource {
    fun interfaces(): List<NdInterface>
}

internal object JdkNetworkInterfaceSource : NetworkInterfaceSource {
    override fun interfaces(): List<NdInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { n ->
            NdInterface(
                name = n.name,
                up = n.isUp,
                loopback = n.isLoopback,
                pointToPoint = n.isPointToPoint,
                addresses = n.inetAddresses.toList(),
            )
        }
}

/**
 * Desktop `NetworkMonitor` (01 NetworkMonitor): the JVM has no network-change callback, so this
 * polls the local interfaces every 15 s, and re-checks immediately after a `PowerMonitor`
 * `Resumed` event and when the platform classifier asks via [recheck] (after an `Offline`,
 * `DnsFailure` or `ConnectionFailed` classification). It **never probes a remote host** (N3).
 *
 * `isConnected` = some interface is up, not loopback, not point-to-point-only, with a
 * non-link-local unicast address; `isValidated = isConnected` (no captive-portal detection);
 * `isMetered = false` in v1.0 (D85); `isVpn` = an up interface named `tun*`/`utun*`/`wg*`/`ppp*`/
 * `tap*` (diagnostics only — macOS's always-up `utun` adapters make this best-effort).
 */
class DesktopNetworkMonitor internal constructor(
    private val scope: CoroutineScope,
    powerMonitor: PowerMonitor,
    private val source: NetworkInterfaceSource,
) : NetworkMonitor {
    private val flow = MutableStateFlow(inspect())

    init {
        scope.launch {
            while (true) {
                delay(POLL_INTERVAL)
                recheck()
            }
        }
        scope.launch {
            powerMonitor.events.collect { event ->
                if (event == PowerEvent.Resumed) recheck()
            }
        }
    }

    override val status: StateFlow<NetworkStatus> = flow.asStateFlow()

    /** Re-inspects the interfaces now; the failure path calls this after a failed request. */
    internal fun recheck() {
        flow.value = inspect()
    }

    private fun inspect(): NetworkStatus {
        val interfaces = runCatching { source.interfaces() }.getOrDefault(emptyList())
        val connected = interfaces.any { it.isConnectedCandidate() }
        val vpn = interfaces.any { i -> i.up && !i.loopback && VPN_NAME_PREFIXES.any(i.name::startsWith) }
        return NetworkStatus(
            isConnected = connected,
            isValidated = connected,
            isMetered = false,
            isVpn = vpn,
        )
    }

    private fun NdInterface.isConnectedCandidate(): Boolean =
        up && !loopback && !pointToPoint && addresses.any { it.isRoutableUnicast() }

    private fun InetAddress.isRoutableUnicast(): Boolean =
        !isLinkLocalAddress && !isLoopbackAddress && !isMulticastAddress && !isAnyLocalAddress

    private companion object {
        val POLL_INTERVAL = 15.seconds
        val VPN_NAME_PREFIXES = listOf("tun", "utun", "wg", "ppp", "tap")
    }
}

/**
 * The monitor's graph entries (01 Components and scopes): the concrete monitor for the desktop
 * classifier's `recheck` hook, and it as `NetworkMonitor` for everyone else. A binding container
 * rather than `@ContributesBinding` so the injectable constructor stays free of the internal
 * `NetworkInterfaceSource` type.
 */
@ContributesTo(AppScope::class)
@BindingContainer
interface DesktopNetworkMonitorBindings {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun desktopNetworkMonitor(
            @ApplicationScope scope: CoroutineScope,
            powerMonitor: PowerMonitor,
        ): DesktopNetworkMonitor = DesktopNetworkMonitor(scope, powerMonitor, JdkNetworkInterfaceSource)

        @Provides
        fun networkMonitor(monitor: DesktopNetworkMonitor): NetworkMonitor = monitor
    }
}
