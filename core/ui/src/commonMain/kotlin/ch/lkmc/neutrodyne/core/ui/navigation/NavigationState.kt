// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NavKeySerializers
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey

/**
 * The shared navigation state (01 AppNavigator and per-tab back stacks): one
 * `NavBackStack<NavKey>` per [TopLevelKey], each saveable through `rememberNavBackStack` with
 * [NavKeySerializers.savedStateConfiguration], plus the selected tab in `rememberSaveable`.
 *
 * Back order implemented by [pop] (08 Back handling order): pop the current tab's stack; when it
 * is at its root and the tab is not Feeds, select Feeds; at Feeds' root return `false` so Android
 * leaves to home and the desktop does nothing. Overlay scenes and the player sheet sit ahead of
 * this call site in their own handlers.
 */
@Stable
public class NavigationState internal constructor(
    private val stacks: Map<TopLevelKey, NavBackStack<NavKey>>,
    private val selected: MutableState<TopLevelKey>,
) : AppNavigator {

    /** The five tabs in suite order. */
    public val tabs: List<TopLevelKey> get() = TOP_LEVEL_TABS

    public val selectedTab: TopLevelKey get() = selected.value

    /**
     * The pane partition count the root renders (set by `NeutrodyneRoot` from the adaptive
     * directive); [pushDetail] reads it, so entries never subscribe to `LocalPaneLayout` themselves.
     */
    public var panePartitions: Int = 1
        internal set

    public fun stack(tab: TopLevelKey): NavBackStack<NavKey> = stacks.getValue(tab)

    /** The stacks [NeutrodyneNavHost] renders: Feeds, then the selected tab when different. */
    public fun visibleTabs(): List<TopLevelKey> =
        if (selectedTab == FeedsKey) listOf(FeedsKey) else listOf(FeedsKey, selectedTab)

    override fun push(key: NavKey) {
        stack(selectedTab).add(key)
    }

    override fun selectTab(key: TopLevelKey) {
        selected.value = key
    }

    override fun pop(): Boolean {
        val stack = stack(selectedTab)
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            return true
        }
        if (selectedTab != FeedsKey) {
            selected.value = FeedsKey
            return true
        }
        return false
    }

    override fun resetTab(key: TopLevelKey) {
        val stack = stack(key)
        while (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    override fun open(tab: TopLevelKey, stack: List<NavKey>) {
        resetTab(tab)
        stack(tab).addAll(stack)
        selected.value = tab
    }

    override fun pushDetail(key: NavKey) {
        val stack = stack(selectedTab)
        if (panePartitions >= 2 && stack.lastOrNull()?.let { it::class == key::class } == true) {
            stack.removeAt(stack.size - 1)
        }
        stack.add(key)
    }
}

/** The suite order (08 Destinations): Feeds, Library, Up next, Downloads, Discover. */
public val TOP_LEVEL_TABS: List<TopLevelKey> =
    listOf(FeedsKey, LibraryKey, UpNextKey, DownloadsKey, DiscoverKey)

private val TOP_LEVEL_BY_NAME: Map<String, TopLevelKey> =
    TOP_LEVEL_TABS.associateBy { it::class.simpleName.orEmpty() }

private val TopLevelKeySaver = listSaver<TopLevelKey, String>(
    save = { listOf(it::class.simpleName.orEmpty()) },
    restore = { TOP_LEVEL_BY_NAME[it.first()] },
)

/**
 * Creates the shared [NavigationState]. Each stack is created in the fixed tab order so every
 * `rememberNavBackStack` keeps a stable saveable slot across compositions and restores on Android.
 */
@Composable
public fun rememberNavigationState(initialTab: TopLevelKey = FeedsKey): NavigationState {
    val stacks = TOP_LEVEL_TABS.associateWith { tab ->
        key(tab) { rememberNavBackStack(NavKeySerializers.savedStateConfiguration, tab) }
    }
    val selected = rememberSaveable(stateSaver = TopLevelKeySaver) {
        mutableStateOf(initialTab)
    }
    return remember(stacks) { NavigationState(stacks, selected) }
}
