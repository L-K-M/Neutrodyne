// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 10 Local-network gate: classification before the discovery request (MS2 acceptance 5). */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val access = LocalNetworkAccess()

    @Test
    fun dotLocalNamesAreLocalWithoutResolution() {
        val gate = gate()
        assertEquals(LocalNetworkClassification.Local, gate.classify("server.local"))
    }

    @Test
    fun ipv4Ranges() {
        assertEquals(LocalNetworkClassification.Local, gate().classify("192.168.1.5"))
        assertEquals(LocalNetworkClassification.Local, gate().classify("10.0.0.8"))
        assertEquals(LocalNetworkClassification.Local, gate().classify("172.16.4.2"))
        assertEquals(LocalNetworkClassification.Local, gate().classify("169.254.1.1"))
        assertEquals(LocalNetworkClassification.Local, gate().classify("100.64.0.1"))
        assertEquals(LocalNetworkClassification.NotLocal, gate().classify("1.1.1.1"))
    }

    @Test
    fun loopbackIsNotLocal() {
        assertFalse(LocalNetworkPermissionGate.isLocalAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(LocalNetworkPermissionGate.isLocalAddress(InetAddress.getByName("::1")))
    }

    @Test
    fun theSyncBypassIsOnlySetWhileGranted() {
        val gate = gate()
        gate.onPermissionResult(true)
        assertTrue(access.syncAllowed.value)
        gate.onPermissionResult(false)
        assertFalse(access.syncAllowed.value)
    }

    private fun gate(): LocalNetworkPermissionGate = LocalNetworkPermissionGate(context, platform(), access)

    private fun platform(): PlatformInfo =
        object : PlatformInfo {
            override val kind: PlatformKind = PlatformKind.ANDROID

            override val userAgentPlatform: String = "Android 17"

            override val androidSdkInt: Int = 37

            override val regionCode: String = "US"
        }
}
