// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.feeds.discovery.DiscoverySource
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupAutodiscovery
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDiscoveryReplayTest {
    @Test fun alternateAndAppleFixturesUseThePlatformBinding() {
        val discovery = AndroidDataBindings.autodiscovery()
        val links =
            discovery.candidates(
                Buffer().writeUtf8(fixture("alternate.html")),
                null,
                "https://example.org/page",
            )
        assertEquals(
            listOf("https://example.org/shows/podcast.xml", "https://example.org/shows/blog.xml"),
            links.map {
                it.url
            },
        )
        val apple = discovery.candidates(Buffer().writeUtf8(fixture("apple-only.html")), null, "https://example.org/")
        assertEquals(DiscoverySource.APPLE_ANCHOR, apple.single().source)
        assertEquals(JsoupAutodiscovery()::class, discovery::class)
    }

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/discovery/$name")).bufferedReader().use {
            it.readText()
        }
}
