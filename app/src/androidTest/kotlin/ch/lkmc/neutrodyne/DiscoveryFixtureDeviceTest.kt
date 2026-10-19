// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.feeds.directory.DirectoryReplayAssertions
import ch.lkmc.neutrodyne.feeds.directory.HtmlReplayAssertions
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupAutodiscovery
import org.junit.Test
import org.junit.runner.RunWith

/** Assets and assertions are shared with the desktop and Android host suites. No live requests. */
@ReleaseSmoke
@RunWith(AndroidJUnit4::class)
class DiscoveryFixtureDeviceTest {
    @Test fun appleSearchAndLookup() = DirectoryReplayAssertions.appleSearchAndLookup(::fixture)

    @Test fun modernAndLegacyChartOrder() = DirectoryReplayAssertions.modernAndLegacyChartOrder(::fixture)

    @Test fun fyydAndIndexFields() = DirectoryReplayAssertions.fyydAndIndexFields(::fixture)

    @Test fun allRecordedCandidatesMatch() = HtmlReplayAssertions.recordedCandidates(JsoupAutodiscovery(), ::fixture)

    @Test fun htmlReadStopsAtTwoMiB() = HtmlReplayAssertions.boundedPrefix(JsoupAutodiscovery())

    private fun fixture(name: String) =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("discovery/$name")
            .bufferedReader()
            .use { it.readText() }
}
