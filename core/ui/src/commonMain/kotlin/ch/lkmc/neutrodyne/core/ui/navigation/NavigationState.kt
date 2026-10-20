// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.collections.immutable.toPersistentList

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
public class NavigationState private constructor(
    private val stacks: Map<TopLevelKey, NavBackStack<NavKey>>,
    private val selected: MutableState<TopLevelKey>,
) : AppNavigator {
    private val mutationListeners = mutableSetOf<(NavigationDeparture?) -> Unit>()

    /** The five tabs in suite order. */
    public val tabs: List<TopLevelKey> get() = TOP_LEVEL_TABS
    public val selectedTab: TopLevelKey get() = selected.value

    // Unsaved gesture invalidation: a round trip still changes this when selection writes coalesce.
    internal var selectionGeneration: Long by mutableLongStateOf(0L)
        private set

    /**
     * The pane partition count the root renders (set by `NeutrodyneRoot` from the adaptive
     * directive); [pushDetail] reads it, so entries never subscribe to `LocalPaneLayout` themselves.
     */
    public var panePartitions: Int = 1
        internal set

    /**
     * Whether the latest stack mutation came from a back action (`pop`). Selecting Feeds from
     * another tab and popping back to it produce the same rendered entries — a strict prefix —
     * which `NavDisplay` cannot tell apart, so [NeutrodyneNavHost]'s transition spec reads this
     * marker to animate only real pops.
     */
    internal var lastChangeWasPop: Boolean = false
        private set

    /** Immutable snapshot. Mutation and synchronous departure publication belong to this owner. */
    public fun stack(tab: TopLevelKey): List<NavKey> = stacks.getValue(tab).toPersistentList()

    /** The stacks [NeutrodyneNavHost] renders: Feeds, then the selected tab when different. */
    public fun visibleTabs(): List<TopLevelKey> =
        if (selectedTab == FeedsKey) listOf(FeedsKey) else listOf(FeedsKey, selectedTab)

    internal fun observeMutations(listener: (NavigationDeparture?) -> Unit): () -> Unit {
        mutationListeners.add(listener)
        return { mutationListeners.remove(listener) }
    }

    private fun <T> mutate(action: () -> T): T {
        val result = action()
        // Publish every mutation, including leave-return writes coalesced before recomposition.
        mutationListeners.toList().forEach { it(null) }
        return result
    }

    override fun push(key: NavKey) =
        mutate {
            lastChangeWasPop = false
            stacks.getValue(selectedTab).add(key)
            Unit
        }

    override fun selectTab(key: TopLevelKey) =
        mutate {
            lastChangeWasPop = false
            if (key == selectedTab) resetStack(key)
            changeSelection(key)
        }

    override fun pop(): Boolean =
        mutate {
            lastChangeWasPop = true
            val stack = stacks.getValue(selectedTab)
            when {
                stack.size > 1 -> {
                    removeTop(selectedTab)
                    true
                }

                selectedTab != FeedsKey -> {
                    changeSelection(FeedsKey)
                    true
                }

                else -> {
                    false
                }
            }
        }

    override fun pop(key: NavKey): Boolean {
        val stack = stacks.getValue(selectedTab)
        if (stack.size <= 1 || stack.last() != key) return false
        return pop()
    }

    override fun resetTab(key: TopLevelKey) =
        mutate {
            lastChangeWasPop = false
            resetStack(key)
        }

    private fun resetStack(key: TopLevelKey) {
        val stack = stacks.getValue(key)
        while (stack.size > 1) removeTop(key)
    }

    private fun removeTop(tab: TopLevelKey) {
        val stack = stacks.getValue(tab)
        val departed = NavigationDeparture(tab, stack.removeAt(stack.lastIndex))
        // Publish the removal itself: resetting and re-adding an equal key is a new visit.
        mutationListeners.toList().forEach { it(departed) }
    }

    override fun open(
        tab: TopLevelKey,
        stack: List<NavKey>,
    ) = mutate {
        lastChangeWasPop = false
        resetStack(tab)
        stacks.getValue(tab).addAll(stack)
        changeSelection(tab)
    }

    override fun pushDetail(key: NavKey) =
        mutate {
            lastChangeWasPop = false
            val stack = stacks.getValue(selectedTab)
            if (panePartitions >= 2 && stack.lastOrNull()?.let { it::class == key::class } == true) {
                removeTop(selectedTab)
            }
            stack.add(key)
            Unit
        }

    private fun changeSelection(tab: TopLevelKey) {
        if (tab == selectedTab) return
        selectionGeneration++
        selected.value = tab
    }

    internal companion object {
        internal fun create(initial: TopLevelKey = FeedsKey): NavigationState =
            NavigationState(TOP_LEVEL_TABS.associateWith { NavBackStack<NavKey>(it) }, mutableStateOf(initial))

        @Composable
        internal fun remember(initial: TopLevelKey): NavigationState {
            val stacks =
                TOP_LEVEL_TABS.associateWith { tab ->
                    key(tab) { rememberNavBackStack(NavKeySerializers.savedStateConfiguration, tab) }
                }
            val selected = rememberSaveable(stateSaver = TopLevelKeySaver) { mutableStateOf(initial) }
            return remember(stacks) { NavigationState(stacks, selected) }
        }
    }
}

internal data class NavigationDeparture(
    val tab: TopLevelKey,
    val key: NavKey,
)

/** The suite order (08 Destinations): Feeds, Library, Up next, Downloads, Discover. */
public val TOP_LEVEL_TABS: List<TopLevelKey> =
    listOf(FeedsKey, LibraryKey, UpNextKey, DownloadsKey, DiscoverKey).toPersistentList()

private val TOP_LEVEL_BY_NAME: Map<String, TopLevelKey> =
    TOP_LEVEL_TABS.associateBy { it::class.simpleName.orEmpty() }

private val TopLevelKeySaver =
    listSaver<TopLevelKey, String>(
        save = { listOf(it::class.simpleName.orEmpty()) },
        restore = { TOP_LEVEL_BY_NAME[it.first()] },
    )

/**
 * Creates the shared [NavigationState]. Each stack is created in the fixed tab order so every
 * `rememberNavBackStack` keeps a stable saveable slot across compositions and restores on Android.
 */
@Composable
public fun rememberNavigationState(initialTab: TopLevelKey = FeedsKey): NavigationState =
    NavigationState.remember(initialTab)
