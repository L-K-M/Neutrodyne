// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import dev.zacsweers.metro.createGraph
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The desktop graph test (01 "Testing" Graphs row): Metro already fails compilation on a missing or
 * duplicate binding, so the runtime part asserts the graph is constructible — the shell's window will
 * hang off this graph (11 Desktop shell).
 */
class DesktopAppGraphTest {
    @Test
    fun `graph is constructible`() {
        val graph = createGraph<DesktopAppGraph>()
        assertNotNull(graph)
    }
}
