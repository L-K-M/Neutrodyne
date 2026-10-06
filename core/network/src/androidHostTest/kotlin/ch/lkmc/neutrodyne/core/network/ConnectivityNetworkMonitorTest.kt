// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * `ConnectivityNetworkMonitor` under Robolectric (01 Testing): `ShadowConnectivityManager` drives
 * `activeNetwork`/`getNetworkCapabilities`, so each `NetworkStatus` field and the callback update
 * are exercised without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityNetworkMonitorTest {
    private val app = RuntimeEnvironment.getApplication() as Application
    private val manager = checkNotNull(app.getSystemService(ConnectivityManager::class.java))
    private val shadow: ShadowConnectivityManager get() = shadowOf(manager)

    @Before
    fun resetConnectivity() {
        shadow.clearAllNetworks()
        shadow.setActiveNetworkInfo(null)
        shadow.setDefaultNetworkActive(false)
    }

    private fun connect(
        transport: Int,
        vararg capabilities: Int,
    ): Network {
        // The shadow looks up activeNetwork by NetworkInfo.type in a netId-keyed map, so the
        // created network's netId must equal the type of the info we mark active.
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        // `NetworkCapabilities.Builder` and `Network(int)` are hidden APIs — the shadows create both.
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        shadow.setNetworkCapabilities(network, caps)
        // The real NetworkInfo ctor is broken under instrumentation; the shadow factory is not.
        val info =
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                // isAvailable =
                true,
                // isConnected =
                true,
            )
        shadow.addNetwork(network, info)
        shadow.setActiveNetworkInfo(info)
        shadow.setDefaultNetworkActive(true)
        return network
    }

    @Test
    fun `no active network is disconnected`() =
        runTest {
            // robolectric.properties pins sdk=36; failing here means it is not on the test classpath.
            assertThat(Build.VERSION.SDK_INT).isEqualTo(36)
            val monitor = ConnectivityNetworkMonitor(app, backgroundScope)
            assertThat(monitor.status.value).isEqualTo(
                NetworkStatus(isConnected = false, isValidated = false, isMetered = false, isVpn = false),
            )
        }

    @Test
    fun `validated unmetered wifi reports connected and unmetered`() =
        runTest {
            connect(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            )
            val monitor = ConnectivityNetworkMonitor(app, backgroundScope)
            with(monitor.status.value) {
                assertThat(isConnected).isTrue()
                assertThat(isValidated).isTrue()
                assertThat(isMetered).isFalse()
                assertThat(isVpn).isFalse()
            }
        }

    @Test
    fun `a connected but unvalidated metered network`() =
        runTest {
            connect(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
            )
            val monitor = ConnectivityNetworkMonitor(app, backgroundScope)
            with(monitor.status.value) {
                assertThat(isConnected).isTrue()
                assertThat(isValidated).isFalse()
                assertThat(isMetered).isTrue()
            }
        }

    @Test
    fun `temporarily-not-metered counts as unmetered on api 30 plus`() =
        runTest {
            connect(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED,
            )
            val monitor = ConnectivityNetworkMonitor(app, backgroundScope)
            assertThat(monitor.status.value.isMetered).isFalse()
        }

    @Test
    fun `vpn transport reports isVpn`() =
        runTest {
            connect(
                NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            )
            val monitor = ConnectivityNetworkMonitor(app, backgroundScope)
            assertThat(monitor.status.value.isVpn).isTrue()
        }

    @Test
    fun `a capabilities callback pushes a fresh status`() =
        runTest {
            val network =
                connect(
                    NetworkCapabilities.TRANSPORT_WIFI,
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                )
            val monitor = ConnectivityNetworkMonitor(app, backgroundScope)
            runCurrent()
            assertThat(monitor.status.value.isMetered).isFalse()

            // Turn metered, then fire the registered callback like the framework would.
            val metered = ShadowNetworkCapabilities.newInstance()
            shadowOf(metered).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(metered).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(metered).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadow.setNetworkCapabilities(network, metered)
            shadow.networkCallbacks.first().onCapabilitiesChanged(network, metered)
            runCurrent()

            assertThat(monitor.status.value.isMetered).isTrue()
        }
}
