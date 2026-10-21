// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import ch.lkmc.neutrodyne.core.artwork.ArtworkProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ArtworkManifestTest {
    @Test fun providerIsPublicReadOnlyArtworkAuthorityInMainProcess() {
        val app: Application = RuntimeEnvironment.getApplication()
        val provider = app.packageManager.getProviderInfo(ComponentName(app, ArtworkProvider::class.java), 0)
        assertEquals("${app.packageName}.artwork", provider.authority)
        assertTrue(provider.exported)
        assertTrue(provider.enabled)
        assertNull(provider.readPermission)
        assertNull(provider.writePermission)
        assertFalse(provider.grantUriPermissions)
        assertEquals(app.applicationInfo.processName, provider.processName)
    }
}
