// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.compose.animation.SharedTransitionLayout
import androidx.navigation3.ui.NavDisplay
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.LocalNavTab
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey

/**
 * The shared `NavDisplay` (01 AppNavigator and per-tab back stacks). Every tab's stack is decorated
 * on every composition in the fixed tab order — a stack skipped for a frame would lose its
 * saveable state and ViewModel stores — and `LocalNavTab` is stamped per entry. Scene strategies
 * run in doc order: sheet and dialog overlays first, then [ListDetailSceneStrategy] (with 08's
 * `ndPaneLayout` partitions baked into [directive]), then Nav3's single-pane default.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
public fun NeutrodyneNavHost(
    state: NavigationState,
    installers: Set<EntryProviderInstaller>,
    directive: PaneScaffoldDirective,
    modifier: Modifier = Modifier,
) {
    val provider = remember(installers) {
        entryProvider<NavKey> {
            installers.forEach { install -> install() }
        }
    }

    val decoratedByTab: Map<TopLevelKey, List<NavEntry<NavKey>>> =
        state.tabs.associateWith { tab ->
            key(tab) {
                rememberDecoratedNavEntries(
                    backStack = state.stack(tab),
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                        rememberTabLocalNavEntryDecorator(tab),
                    ),
                    entryProvider = { key -> withPaneRole(key, provider(key), tab) },
                )
            }
        }

    val entries = state.visibleTabs().flatMap { decoratedByTab.getValue(it) }

    SharedTransitionLayout {
        NavDisplay(
            entries = entries,
            modifier = modifier,
            onBack = { state.pop() },
            sceneStrategies = listOf(
                rememberNdBottomSheetSceneStrategy(),
                rememberNdDialogSceneStrategy(),
                rememberListDetailSceneStrategy(directive = directive),
            ),
            sharedTransitionScope = this,
        )
    }
}

/**
 * Provides 08's `LocalNavTab` to every entry of [tab]'s stack — e.g. a podcast under Library and
 * the same podcast under Feeds know which stack they belong to (shared-element keys include it).
 */
@Composable
public fun rememberTabLocalNavEntryDecorator(
    tab: TopLevelKey,
): NavEntryDecorator<NavKey> = remember(tab) {
    NavEntryDecorator { entry ->
        CompositionLocalProvider(LocalNavTab provides tab) {
            entry.Content()
        }
    }
}

/**
 * Translates our platform-neutral [NdSceneMetadata.KEY_PANE] marker into
 * `ListDetailSceneStrategy`'s metadata with [tab] as the `sceneKey`, so a scene never merges
 * entries from two stacks. Entries without the marker pass through untouched.
 */
private fun withPaneRole(
    key: NavKey,
    entry: NavEntry<NavKey>,
    tab: TopLevelKey,
): NavEntry<NavKey> {
    val role = entry.metadata[NdSceneMetadata.KEY_PANE] as? String ?: return entry
    val placeholder = entry.metadata[NdSceneMetadata.KEY_DETAIL_PLACEHOLDER]
        as? (@Composable () -> Unit)
    val pane: Map<String, Any> = when (role) {
        NdSceneMetadata.PANE_LIST -> if (placeholder != null) {
            ListDetailSceneStrategy.listPane(sceneKey = tab, detailPlaceholder = { placeholder() })
        } else {
            ListDetailSceneStrategy.listPane(sceneKey = tab)
        }
        NdSceneMetadata.PANE_DETAIL -> ListDetailSceneStrategy.detailPane(sceneKey = tab)
        else -> ListDetailSceneStrategy.extraPane(sceneKey = tab)
    }
    return NavEntry(
        key = key,
        contentKey = entry.contentKey,
        metadata = entry.metadata - NdSceneMetadata.KEY_PANE - NdSceneMetadata.KEY_DETAIL_PLACEHOLDER + pane,
        content = { entry.Content() },
    )
}
