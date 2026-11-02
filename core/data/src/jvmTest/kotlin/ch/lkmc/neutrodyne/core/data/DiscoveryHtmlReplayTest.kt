// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.feeds.directory.HtmlReplayAssertions
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupAutodiscovery
import org.junit.Test

class DiscoveryHtmlReplayTest {
    @Test fun allRecordedCandidatesMatch() =
        HtmlReplayAssertions.recordedCandidates(JsoupAutodiscovery()) { name ->
            checkNotNull(javaClass.getResourceAsStream("/discovery/$name")).bufferedReader().use { it.readText() }
        }

    @Test fun htmlReadStopsAtTwoMiB() = HtmlReplayAssertions.boundedPrefix(JsoupAutodiscovery())
}
