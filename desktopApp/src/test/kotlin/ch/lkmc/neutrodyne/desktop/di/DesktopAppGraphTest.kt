// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import org.junit.Test

/**
 * The desktop graph test (01 Testing, Graphs row; 11 DesktopAppGraph): Metro already fails
 * compilation on a missing or duplicate binding, so the runtime part proves the graph is
 * constructible with a temporary `AppDirs` and resolves the shell's shared bindings.
 */
class DesktopAppGraphTest {
    @Test
    fun `the graph builds with temporary AppDirs and resolves the shared bindings`() {
        val dirs = tempAppDirs().also { it.ensureCreated() }
        val buildInfo = BuildInfoLoader.load()
        val reporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
        val graph = createDesktopGraph(dirs, buildInfo, reporter)
        try {
            assertThat(graph.dirs).isSameInstanceAs(dirs)
            assertThat(graph.buildInfo).isSameInstanceAs(buildInfo)
            assertThat(graph.settingsRepository).isNotNull()
            assertThat(graph.networkMonitor).isInstanceOf(
                ch.lkmc.neutrodyne.core.network.DesktopNetworkMonitor::class.java,
            )
            // The shell's pre-graph instance is the graph's crash reporter, not a second one.
            assertThat(graph.crashReporter).isSameInstanceAs(reporter)
            assertThat(graph.initializers).isNotNull()
            assertThat(graph.appScope).isNotNull()
        } finally {
            graph.appScope.cancel()
        }
    }
}
