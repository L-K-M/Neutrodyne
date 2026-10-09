// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test

/**
 * The desktop graph test (01 Testing, Graphs row; 11 DesktopAppGraph): Metro already fails
 * compilation on a missing or duplicate binding, so the runtime part proves the graph is
 * constructible with a temporary `AppDirs` and resolves the shell's shared bindings — including
 * the same M0 key set `AndroidAppGraphTest` checks, through the feature modules' contributions.
 */
class DesktopAppGraphTest {
    private val dirs = tempAppDirs().also { it.ensureCreated() }
    private val buildInfo = BuildInfoLoader.load()
    private val reporter = DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer())
    private val graph = createDesktopGraph(dirs, buildInfo, reporter)

    /** JUnit builds a fresh instance per test, so every test's scope is cancelled here. */
    @After
    fun tearDown() {
        graph.appScope.cancel()
    }

    @Test
    fun theGraphBuildsWithTemporaryAppDirsAndResolvesTheSharedBindings() {
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
    }

    /** The same keys as `app/src/test/.../AndroidAppGraphTest` (01 Graph tests rule 11). */
    @Test
    fun everyM0bKeyHasAnEntry() {
        val provider = entryProvider<NavKey> { graph.entryInstallers.forEach { install -> install() } }

        for (key in M0B_KEYS) {
            assertThat(provider(key).contentKey).isNotNull()
        }
    }

    @Test
    fun initializerOrdersLieInStartupBands() {
        for (initializer in graph.initializers) {
            assertThat(initializer.order).isAtLeast(FIRST_BAND)
        }
    }

    private companion object {
        const val FIRST_BAND = 0

        /** The five destinations and the Settings screens M0 renders (01 M0 checklist step 29). */
        val M0B_KEYS: List<NavKey> =
            listOf(
                FeedsKey,
                LibraryKey,
                UpNextKey,
                DownloadsKey,
                DiscoverKey,
                SettingsHomeKey,
                SettingsKey(SettingsPage.ABOUT),
                LicencesKey,
            )
    }
}
