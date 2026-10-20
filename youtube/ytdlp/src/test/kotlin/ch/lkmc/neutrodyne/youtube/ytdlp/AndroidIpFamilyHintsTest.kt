// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.net.ConnectivityManager
import ch.lkmc.neutrodyne.core.model.IpFamily
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidIpFamilyHintsTest {
    @Test fun familyAdapterWritesOnlyGooglevideoSuffixAndClearsIt() {
        val dns = DnsFamilyHints()
        dns.set("unrelated.invalid", IpFamily.V6)
        val hints = AndroidIpFamilyHints(dns)
        assertNull(hints.current())
        hints.set(IpFamily.V4)
        assertEquals(IpFamily.V4, dns.familyFor("rr1.googlevideo.com"))
        assertEquals(IpFamily.V4, hints.current())
        assertNull(dns.familyFor("notgooglevideo.com"))
        hints.set(null)
        assertNull(dns.familyFor("rr1.googlevideo.com"))
        assertEquals(IpFamily.V6, dns.familyFor("unrelated.invalid"))
    }

    @Test fun defaultCallbackDistinguishesNetworkIdentityAndUnregisters() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        val before = shadow.networkCallbacks.size
        var resets = 0
        val registration = AndroidDefaultNetworkReset(manager) { resets++ }
        assertEquals(before + 1, shadow.networkCallbacks.size)
        val callback = shadow.networkCallbacks.last()
        val first = ShadowNetwork.newInstance(101)
        val second = ShadowNetwork.newInstance(102)
        val seeded = resets
        callback.onAvailable(first)
        assertEquals(seeded + 1, resets)
        callback.onAvailable(first)
        assertEquals(seeded + 1, resets)
        callback.onAvailable(second)
        assertEquals(seeded + 2, resets)
        registration.close()
        assertEquals(before, shadow.networkCallbacks.size)
    }

    @Test fun constructingFamilyAdapterDoesNotRegisterOrStartHost() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        val before = shadow.networkCallbacks.toSet()
        assertNull(AndroidIpFamilyHints(DnsFamilyHints()).current())
        assertTrue(before == shadow.networkCallbacks.toSet())
    }
}
