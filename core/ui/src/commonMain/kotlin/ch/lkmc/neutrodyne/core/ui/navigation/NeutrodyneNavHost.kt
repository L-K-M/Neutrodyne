// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
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
@Suppress("FunctionNaming") // Compose functions use PascalCase.
public fun NeutrodyneNavHost(
    state: NavigationState,
    installers: Set<EntryProviderInstaller>,
    directive: PaneScaffoldDirective,
    modifier: Modifier = Modifier,
) {
    val provider =
        remember(installers) {
            entryProvider<NavKey> {
                installers.forEach { install -> install() }
            }
        }

    val decoratedByTab: Map<TopLevelKey, List<NavEntry<NavKey>>> =
        state.tabs.associateWith { tab ->
            key(tab) {
                rememberDecoratedNavEntries(
                    backStack = state.stack(tab),
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                            rememberTabLocalNavEntryDecorator(tab),
                            rememberPaneFocusDecorator(),
                        ),
                    entryProvider = { key -> withPaneRole(key, provider(key), tab) },
                )
            }
        }

    val entries = state.visibleTabs().flatMap { decoratedByTab.getValue(it) }

    val adaptive = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    val panes =
        remember(state, provider) {
            PaneSceneStrategy(state) { key, tab -> withPaneRole(key, provider(key), tab) }
        }
    panes.updateCurrent(
        adaptive,
        decoratedByTab,
        if (LocalReducedMotion.current) snappedPaneAnimations() else emptyMap(),
    )

    // The scene transitions share the pane-swap spec so reduced motion snaps both (see 08).
    val motionSpec = NeutrodyneMotion.navigation<IntOffset>()

    SharedTransitionLayout {
        NavigationBackDispatch(panes) {
            NavDisplay(
                entries = entries,
                modifier = modifier,
                onBack = { state.pop() },
                sceneStrategies =
                    listOf(
                        rememberNdBottomSheetSceneStrategy(),
                        rememberNdDialogSceneStrategy(),
                        panes,
                    ),
                transitionSpec = {
                    transformFor(classifyTransition(initialState, targetState), motionSpec) {
                        defaultTransitionSpec<NavKey>().invoke(this)
                    }
                },
                popTransitionSpec = {
                    transformFor(
                        classifyPopTransition(initialState, targetState, state.lastChangeWasPop),
                        motionSpec,
                    ) {
                        defaultPopTransitionSpec<NavKey>().invoke(this)
                    }
                },
                predictivePopTransitionSpec = { swipeEdge ->
                    transformFor(classifyPredictivePopTransition(initialState, targetState), motionSpec) {
                        defaultPredictivePopTransitionSpec<NavKey>().invoke(this, swipeEdge)
                    }
                },
                // Expanded panes share movable entries, not whole-entry animated bounds.
                sharedTransitionScope = if (directive.maxHorizontalPartitions == 1) this else null,
            )
        }
    }
}

private val InstantTransform = ContentTransform(EnterTransition.None, ExitTransition.None)

/**
 * Owner transition model: tab switches are immediate; a forward push covers horizontally; a back
 * pop wipes horizontally, revealing the previous entry underneath. Overlay scenes (sheet/dialog)
 * keep the platform default.
 */
internal enum class NdSceneTransition {
    PlatformDefault,
    Instant,
    SlideInCover,
    PaneCover,
    SlideOutWipe,
}

/**
 * A tab tap and a forward push both reach the display as a "navigate" — the
 * [NdSceneMetadata.KEY_TAB] stamps distinguish them by the top entry's owning tab.
 */
internal fun classifyTransition(
    initial: Scene<NavKey>,
    target: Scene<NavKey>,
): NdSceneTransition =
    when {
        initial is OverlayScene || target is OverlayScene -> {
            NdSceneTransition.PlatformDefault
        }

        topTab(initial) != null && topTab(target) != null && topTab(initial) != topTab(target) -> {
            NdSceneTransition.Instant
        }

        else -> {
            paneSceneTransition(initial, target, NdSceneTransition.SlideInCover)
        }
    }

/**
 * Selecting Feeds from another tab and popping back to it reach the display as the same strict
 * prefix, so only [NavigationState.lastChangeWasPop] can tell the tap (immediate) from a real
 * back action (wipe).
 */
internal fun classifyPopTransition(
    initial: Scene<NavKey>,
    target: Scene<NavKey>,
    lastChangeWasPop: Boolean,
): NdSceneTransition =
    when {
        initial is OverlayScene || target is OverlayScene -> NdSceneTransition.PlatformDefault
        lastChangeWasPop -> paneSceneTransition(initial, target, NdSceneTransition.SlideOutWipe)
        else -> NdSceneTransition.Instant
    }

/** The predictive-back gesture always goes back, so it always wipes under gesture progress. */
internal fun classifyPredictivePopTransition(
    initial: Scene<NavKey>,
    target: Scene<NavKey>,
): NdSceneTransition =
    if (initial is OverlayScene || target is OverlayScene) {
        NdSceneTransition.PlatformDefault
    } else {
        paneSceneTransition(initial, target, NdSceneTransition.SlideOutWipe)
    }

private fun <S> AnimatedContentTransitionScope<S>.transformFor(
    kind: NdSceneTransition,
    motionSpec: FiniteAnimationSpec<IntOffset>,
    platformDefault: AnimatedContentTransitionScope<S>.() -> ContentTransform,
): ContentTransform =
    when (kind) {
        NdSceneTransition.PlatformDefault -> {
            platformDefault()
        }

        NdSceneTransition.Instant -> {
            InstantTransform
        }

        NdSceneTransition.SlideInCover -> {
            ContentTransform(
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = motionSpec,
                ),
                ExitTransition.None,
            )
        }

        NdSceneTransition.PaneCover -> {
            // The pane's child transition supplies the clock; retain the covered scene until it finishes.
            ContentTransform(EnterTransition.None, ExitTransition.KeepUntilTransitionsFinished)
        }

        NdSceneTransition.SlideOutWipe -> {
            ContentTransform(
                EnterTransition.None,
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = motionSpec,
                ),
            )
        }
    }

/** The [TopLevelKey] stamped on [scene]'s top entry, or null when the stamp is absent. */
private fun topTab(scene: Scene<NavKey>): TopLevelKey? =
    scene.entries
        .lastOrNull()
        ?.metadata
        ?.get(NdSceneMetadata.KEY_TAB) as? TopLevelKey

/**
 * Provides 08's `LocalNavTab` to every entry of [tab]'s stack — e.g. a podcast under Library and
 * the same podcast under Feeds know which stack they belong to (shared-element keys include it).
 */
@Composable
public fun rememberTabLocalNavEntryDecorator(tab: TopLevelKey): NavEntryDecorator<NavKey> =
    remember(tab) {
        NavEntryDecorator { entry ->
            CompositionLocalProvider(LocalNavTab provides tab) {
                entry.Content()
            }
        }
    }

/**
 * Stamps [tab] as [NdSceneMetadata.KEY_TAB] on every entry — a tab switch and a back pop can
 * produce the same rendered entries, so the transition spec needs the recorded owner — and
 * translates the platform-neutral [NdSceneMetadata.KEY_PANE] marker into
 * `ListDetailSceneStrategy`'s metadata with [tab] as the `sceneKey`, so a scene never merges
 * entries from two stacks.
 */
private fun withPaneRole(
    key: NavKey,
    entry: NavEntry<NavKey>,
    tab: TopLevelKey,
): NavEntry<NavKey> {
    val role = entry.metadata[NdSceneMetadata.KEY_PANE] as? String
    val placeholder =
        entry.metadata[NdSceneMetadata.KEY_DETAIL_PLACEHOLDER]
            as? (@Composable () -> Unit)
    val pane: Map<String, Any> =
        when (role) {
            NdSceneMetadata.PANE_LIST -> {
                if (placeholder != null) {
                    ListDetailSceneStrategy.listPane(
                        sceneKey = tab,
                        detailPlaceholder = { placeholder() },
                    )
                } else {
                    ListDetailSceneStrategy.listPane(sceneKey = tab)
                }
            }

            NdSceneMetadata.PANE_DETAIL -> {
                ListDetailSceneStrategy.detailPane(sceneKey = tab)
            }

            NdSceneMetadata.PANE_EXTRA -> {
                ListDetailSceneStrategy.extraPane(sceneKey = tab)
            }

            else -> {
                emptyMap()
            }
        }
    return NavEntry(
        key = key,
        contentKey = "$tab:${entry.contentKey}",
        metadata =
            entry.metadata - NdSceneMetadata.KEY_DETAIL_PLACEHOLDER +
                pane + mapOf(NdSceneMetadata.KEY_TAB to tab, PANE_NAV_KEY to key),
        content = { entry.Content() },
    )
}
