// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/** One installer per key, contributed by its feature module into a `Set` multibinding. */
public typealias EntryProviderInstaller = EntryProviderScope<NavKey>.() -> Unit

/**
 * Navigation operations. The implementation (`NavigationState` in `:core:ui`) keeps one back stack
 * per [TopLevelKey]; `push` and sheet/dialog keys always target the selected tab's stack.
 */
public interface AppNavigator {
    /** Pushes [key] onto the selected tab's stack (sheets and dialogs too). */
    public fun push(key: NavKey)

    /** Selects [key]'s tab, keeping its stack. */
    public fun selectTab(key: TopLevelKey)

    /** Pops the selected tab's stack. Returns false when nothing was popped. */
    public fun pop(): Boolean

    /** Pops the given tab's stack back to its root. */
    public fun resetTab(key: TopLevelKey)

    /** Deep links: selects [tab] and replaces its stack above the root with [stack]. */
    public fun open(
        tab: TopLevelKey,
        stack: List<NavKey>,
    )

    /**
     * List-to-detail navigation: when the pane layout has two or more partitions and the top entry
     * has the same key class as [key], the top entry is replaced instead of stacked. On one pane
     * this equals [push].
     */
    public fun pushDetail(key: NavKey)
}

/** The navigator of the shared host, provided by `NeutrodyneRoot`. */
public val LocalAppNavigator: androidx.compose.runtime.ProvidableCompositionLocal<AppNavigator> =
    staticCompositionLocalOf<AppNavigator> { error("AppNavigator not provided") }
