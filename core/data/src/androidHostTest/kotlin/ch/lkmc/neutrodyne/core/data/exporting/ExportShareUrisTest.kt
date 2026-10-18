// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import ch.lkmc.neutrodyne.core.common.StoragePaths
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * `ExportShareUris` (05 Destinations): only a minted leaf name under `<cache>/export/` may
 * become a `content://` share URI. The positive legs run the real `FileProvider` declared in
 * this source set's manifest — same `${applicationId}.fileprovider` authority shape as :app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportShareUrisTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var shareUris: ExportShareUris

    @Before
    fun setUp() {
        // `FileProvider` keeps parsed roots in a static map keyed by authority; Robolectric
        // runs each method in a new sandbox under a different cacheDir, so a cached root from
        // an earlier test would point at a dead dir and fail every later lookup. Clear it.
        FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
            (get(null) as MutableMap<*, *>).clear()
        }
        shareUris = ExportShareUris(app, StoragePaths(app))
    }

    @Test
    fun authorityDerivesFromTheApplicationId() {
        assertThat(shareUris.authority)
            .isEqualTo("${app.packageName}${ExportShareUris.PROVIDER_SUFFIX}")
        assertThat(shareUris.authority).endsWith(".fileprovider")
    }

    @Test
    fun mintedLeafResolvesUnderTheExportRoot() {
        File(app.cacheDir, "export").mkdirs()
        File(app.cacheDir, "export/feeds.opml").writeText("opml")

        val uri = shareUris.uriFor("feeds.opml")

        assertThat(uri).isNotNull()
        assertThat(uri!!.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(shareUris.authority)
        assertThat(uri.encodedPath).isEqualTo("/export/feeds.opml")
    }

    @Test
    fun nonLeafNamesNeverReachTheProvider() {
        for (name in listOf("", ".", "..", "../secret", "a/b", "a\\b", "a/./b", "//x")) {
            assertThat(shareUris.uriFor(name)).isNull()
        }
    }

    @Test
    fun uriOutsideTheExportRootCannotBeShared() {
        // The leaf check passes but the file is not under <cache>/export/ — the provider's
        // own root check is the second gate (a name can never climb out through it anyway,
        // because non-leaf names are already rejected).
        File(app.cacheDir, "not-export").mkdirs()
        File(app.cacheDir, "not-export/private.bin").writeText("x")

        assertThat(shareUris.uriFor("not-export/private.bin")).isNull()
    }

    @Test
    fun zipAndTimestampedNamesResolveAsLeaves() {
        File(app.cacheDir, "export").mkdirs()
        for (name in listOf("backup-2026-10-16.zip", "feeds flat.opml", "a.b.opml")) {
            File(app.cacheDir, "export/$name").writeText("x")
            val uri = shareUris.uriFor(name)

            assertWithMessage("uriFor(%s)", name).that(uri).isNotNull()
            assertThat(uri!!.encodedPath).isEqualTo("/export/" + Uri.encode(name))
        }
    }
}
