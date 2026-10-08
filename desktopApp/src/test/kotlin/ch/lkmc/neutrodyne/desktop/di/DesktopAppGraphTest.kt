// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppDirs
import dev.zacsweers.metro.createGraphFactory
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files

/**
 * The desktop graph test (01 "Testing" Graphs row): Metro already fails compilation on a missing or
 * duplicate binding, so the runtime part asserts the graph is constructible — the shell's window will
 * hang off this graph (11 Desktop shell). `AppDirs` is a factory input, built under a temporary
 * directory like the smoke mode's (11 Testing).
 */
class DesktopAppGraphTest {
    @Test
    fun `graph is constructible`() {
        val root = Files.createTempDirectory("neutrodyne-graph-test")
        val dirs =
            AppDirs(
                data = root.resolve("data"),
                config = root.resolve("config"),
                cache = root.resolve("cache"),
                state = root.resolve("state"),
                logs = root.resolve("logs"),
                downloadsDefault = root.resolve("downloads"),
            )
        val graph = createGraphFactory<DesktopAppGraph.Factory>().create(dirs)
        assertNotNull(graph)
    }
}
