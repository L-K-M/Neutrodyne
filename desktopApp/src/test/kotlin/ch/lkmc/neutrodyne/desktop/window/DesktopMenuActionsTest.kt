// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.swing.SwingUtilities

/**
 * The notification route bridge: a banner's "Open" reaches `DesktopMenuActions.applyRoute`,
 * which lands on the root's `AppNavigator` — dispatched onto the AWT thread where navigation
 * state lives (11: menus never touch a back stack directly).
 */
class DesktopMenuActionsTest {
    private val actions = DesktopMenuActions()
    private val navigator = RecordingNavigator()

    @Test
    fun aNavigateRouteOpensItsTabAndStackOnTheEdt() {
        actions.attachNavigator(navigator)

        assertThat(actions.applyRoute(Route.Navigate(LibraryKey, listOf(ImportKey("7"))))).isTrue()
        SwingUtilities.invokeAndWait {}

        assertThat(navigator.opens).containsExactly(LibraryKey to listOf<NavKey>(ImportKey("7")))
    }

    @Test
    fun aPushRoutePushesItsKey() {
        actions.attachNavigator(navigator)

        assertThat(actions.applyRoute(Route.Push(PodcastKey(42)))).isTrue()
        SwingUtilities.invokeAndWait {}

        assertThat(navigator.pushes).containsExactly(PodcastKey(42))
    }

    @Test
    fun aSelectFeedRouteSelectsTheFeedsTab() {
        actions.attachNavigator(navigator)

        // `groupUuid` is pager-level state, not navigator API — the Feeds tab is the applied part.
        assertThat(actions.applyRoute(Route.SelectFeed("group-uuid"))).isTrue()
        SwingUtilities.invokeAndWait {}

        assertThat(navigator.tabs).containsExactly(FeedsKey)
    }

    @Test
    fun withoutANavigatorTheRouteReturnsFalse() {
        assertThat(actions.applyRoute(Route.Push(PodcastKey(42)))).isFalse()
    }

    private class RecordingNavigator : AppNavigator {
        val pushes = mutableListOf<NavKey>()
        val opens = mutableListOf<Pair<TopLevelKey, List<NavKey>>>()
        val tabs = mutableListOf<TopLevelKey>()

        override fun push(key: NavKey) {
            pushes += key
        }

        override fun selectTab(key: TopLevelKey) {
            tabs += key
        }

        override fun pop(): Boolean = false

        override fun pop(key: NavKey): Boolean = false

        override fun resetTab(key: TopLevelKey) = Unit

        override fun open(
            tab: TopLevelKey,
            stack: List<NavKey>,
        ) {
            opens += tab to stack
        }

        override fun pushDetail(key: NavKey) {
            pushes += key
        }
    }
}
