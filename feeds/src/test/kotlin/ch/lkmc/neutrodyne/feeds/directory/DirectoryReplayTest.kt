// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.directory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Same short recordings execute on the desktop and Android host targets. */
class DirectoryReplayTest {
    private val fixture: (String) -> String = ::classpathFixture

    @Test fun appleSearchAndLookup() = DirectoryReplayAssertions.appleSearchAndLookup(fixture)

    @Test fun modernAndLegacyChartOrder() = DirectoryReplayAssertions.modernAndLegacyChartOrder(fixture)

    @Test fun fyydAndIndexFields() = DirectoryReplayAssertions.fyydAndIndexFields(fixture)
}

private fun classpathFixture(name: String): String =
    checkNotNull(DirectoryReplayTest::class.java.getResourceAsStream("/discovery/$name")).bufferedReader().use {
        it.readText()
    }
