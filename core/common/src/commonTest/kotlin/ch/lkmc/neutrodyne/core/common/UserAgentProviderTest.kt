// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object FakePlatform : PlatformInfo {
    override val kind = PlatformKind.DESKTOP
    override val userAgentPlatform = "Linux; x64"
    override val androidSdkInt: Int? = null
    override val regionCode = "DE"
}

class UserAgentProviderTest {
    @Test
    fun `builds the documented agent string`() {
        assertEquals(
            "Neutrodyne/0.1.0 (Linux; x64; +https://github.com/L-K-M/Neutrodyne)",
            UserAgentProvider(FakePlatform, "0.1.0", "https://github.com/L-K-M/Neutrodyne").value,
        )
    }

    @Test
    fun `non-ascii characters become question marks`() {
        val ua =
            UserAgentProvider(
                object : PlatformInfo {
                    override val kind = PlatformKind.DESKTOP
                    override val userAgentPlatform = "Linüx; x64"
                    override val androidSdkInt: Int? = null
                    override val regionCode = "DE"
                },
                "0.1.0",
                "https://example.com",
            )
        assertTrue("Lin?x" in ua.value)
        assertFalse(ua.value.any { it.code > 126 })
    }
}

class CredentialLookupTest {
    @Test
    fun `none never returns credentials`() =
        runTest {
            assertNull(CredentialLookup.None.basicAuthorization(Origin("https", "h.test", 443)))
            CredentialLookup.None.awaitLoaded()
        }
}

class LocalNetworkAccessTest {
    @Test
    fun `syncAllowed defaults false and follows setSyncAllowed`() =
        runTest {
            val access = LocalNetworkAccess()
            assertFalse(access.syncAllowed.value)
            access.setSyncAllowed(true)
            assertTrue(access.syncAllowed.value)
        }
}
