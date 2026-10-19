// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.os.Environment
import androidx.core.content.FileProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30, 33, 34, 36])
class DownloadFileProviderTest {
    @Test fun primaryAndInternalAudioAreShareableButOtherPrivateAndRemovableFilesAreHidden() {
        val app = RuntimeEnvironment.getApplication()
        val authority = "${app.packageName}.fileprovider"
        val primary = File(app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "podcast/audio.mp3")
        val internal = File(app.filesDir, "downloads/podcast/audio.mp3")
        assertEquals("podcasts", FileProvider.getUriForFile(app, authority, primary).pathSegments.first())
        assertEquals("downloads", FileProvider.getUriForFile(app, authority, internal).pathSegments.first())
        assertFailsWith<IllegalArgumentException> {
            FileProvider.getUriForFile(app, authority, File(app.filesDir, "credentials.bin"))
        }
        assertFailsWith<IllegalArgumentException> {
            FileProvider.getUriForFile(
                app,
                authority,
                File("/storage/ABCD-1234/Android/data/${app.packageName}/files/Podcasts/audio.mp3"),
            )
        }
    }

    @Test fun manifestProtectsUidtAndActionReceiverAndIncludesOnlyNarrowPermissions() {
        val app = RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        val service =
            pm.getServiceInfo(
                android.content.ComponentName(app, "ch.lkmc.neutrodyne.download.impl.ManualDownloadJobService"),
                0,
            )
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
        assertFalse(service.exported)
        val receiver =
            pm.getReceiverInfo(
                android.content.ComponentName(app, "ch.lkmc.neutrodyne.download.impl.DownloadActionReceiver"),
                0,
            )
        assertFalse(receiver.exported)
        val requested =
            pm
                .getPackageInfo(
                    app.packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()
                .toSet()
        assertTrue(android.Manifest.permission.RUN_USER_INITIATED_JOBS in requested)
        assertFalse(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE in requested)
        // Debug LeakCanary contributes legacy READ/WRITE permissions. The release
        // verifyManifestPermissions gate checks the shipped permission set exactly.
    }
}
