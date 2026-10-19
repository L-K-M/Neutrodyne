// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = ImportHostApplication::class, sdk = [34])
class DiscoveryManifestTest {
    @Test
    fun exportedMainResolvesOnlyTheDocumentedLinkAndTextFilters() {
        val context = RuntimeEnvironment.getApplication()

        fun resolves(intent: Intent): Boolean =
            context.packageManager
                .queryIntentActivities(intent, 0)
                .any { it.activityInfo.name == MainActivity::class.java.name }
        for (url in listOf(
            "feed:https://example.test/x",
            "pcast://example.test/x",
            "podcast:https://example.test/x",
            "itpc://example.test/x",
            "neutrodyne://subscribe?url=x",
            "https://podcasts.apple.com/us/podcast/id1",
        )) {
            assertThat(
                resolves(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)),
            ).isTrue()
        }
        for (url in listOf(
            "https://example.test/x",
            "neutrodyne://open/podcast/1",
            "https://subscribe/x",
            "neutrodyne://podcasts.apple.com/x",
        )) {
            assertThat(resolves(Intent(Intent.ACTION_VIEW, Uri.parse(url)))).isFalse()
        }
        assertThat(resolves(Intent(Intent.ACTION_SEND).setType("text/plain"))).isTrue()
        assertThat(resolves(Intent(Intent.ACTION_SEND).setType("application/zip"))).isFalse()
    }

    @Test
    fun filtersKeepSchemesSeparateAndNeverRequestDomainVerification() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val main =
            (0 until activities.length)
                .map { activities.item(it) as Element }
                .single { it.getAttribute("android:name") == ".MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        assertThat(filters.length).isEqualTo(8)
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as Element
            assertThat(filter.getAttribute("android:autoVerify")).isNotEqualTo("true")
            val data = filter.getElementsByTagName("data")
            assertThat(data.length).isAtMost(1)
            if (data.length == 0) continue
            val entry = data.item(0) as Element
            val scheme = entry.getAttribute("android:scheme")
            if (scheme != "https") assertThat(filter.getAttribute("android:autoVerify")).isEmpty()
            when (scheme) {
                "https" -> {
                    assertThat(entry.getAttribute("android:host")).isEqualTo("podcasts.apple.com")
                    assertThat(filter.getAttribute("android:autoVerify")).isEqualTo("false")
                }

                "neutrodyne" -> {
                    assertThat(entry.getAttribute("android:host")).isEqualTo("subscribe")
                }

                else -> {
                    assertThat(entry.getAttribute("android:host")).isEmpty()
                }
            }
        }
    }
}
