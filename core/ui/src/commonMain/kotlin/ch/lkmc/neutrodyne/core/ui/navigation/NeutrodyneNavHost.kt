// SPDX-License-Identifier: Unlicense

// The nlopez compositionlocal-allowlist check mis-evaluates declarations in this file — names
// added to compose_allowed_composition_locals pass and fail nondeterministically while the same
// mechanism works elsewhere — so the file is suppressed instead.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.LocalAnimatedPaneOverride
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
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
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
    val provider =
        remember(installers) {
            entryProvider<NavKey> {
                installers.forEach { install -> install() }
            }
        }

    // A pushed key's pane role is not decorated yet when the top diff runs, so the diff reads
    // roles straight from the entry provider.
    val retention =
        remember(provider) {
            PaneRetention().apply {
                roleResolver = { key ->
                    provider(key).metadata[NdSceneMetadata.KEY_PANE] as? String
                }
            }
        }
    val stacks = state.tabs.associateWith { state.stack(it) }

    // A pane whose top entry just changed — push or pop — keeps the outgoing top pinned above
    // its successor on the decorated list below, so the pane callsite keeps emitting it
    // through the swap: decorator onPop (ViewModel/saveable clearing) is keyed on this list's
    // membership, and the pane's own retention machinery never sees a gap to re-enter across.
    retention.beginPass(stacks)

    val decoratedByTab: Map<TopLevelKey, List<NavEntry<NavKey>>> =
        state.tabs.associateWith { tab ->
            key(tab) {
                rememberDecoratedNavEntries(
                    backStack = retention.augmentedStack(tab, stacks.getValue(tab)),
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                            rememberTabLocalNavEntryDecorator(tab),
                        ),
                    entryProvider = { key -> withPaneRole(key, provider(key), tab) },
                )
            }
        }

    val entries = state.visibleTabs().flatMap { decoratedByTab.getValue(it) }

    // Entries that can land in an adaptive pane carry the pane transport, so a same-scene
    // content swap can translate the pinned outgoing and incoming emissions (see
    // PaneTransportContent).
    val displayedEntries = entries.map { it.withPaneTransport() }
    retention.indexEntries(entries)

    // Pinned entries a pane never emitted (the pane was not composed) would linger; expire them.
    PinnedEntryExpiry(retention)

    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalAnimatedPaneOverride provides
                rememberPaneSwapOverride(LocalAnimatedPaneOverride.current, retention),
        ) {
            NavDisplay(
                entries = displayedEntries,
                modifier = modifier,
                onBack = { state.pop() },
                sceneStrategies =
                    listOf(
                        rememberNdBottomSheetSceneStrategy(),
                        rememberNdDialogSceneStrategy(),
                        rememberListDetailSceneStrategy(directive = directive),
                    ),
                transitionSpec = {
                    transformFor(classifyTransition(initialState, targetState)) {
                        defaultTransitionSpec<NavKey>().invoke(this)
                    }
                },
                popTransitionSpec = {
                    transformFor(
                        classifyPopTransition(initialState, targetState, state.lastChangeWasPop),
                    ) {
                        defaultPopTransitionSpec<NavKey>().invoke(this)
                    }
                },
                predictivePopTransitionSpec = { swipeEdge ->
                    transformFor(classifyPredictivePopTransition(initialState, targetState)) {
                        defaultPredictivePopTransitionSpec<NavKey>().invoke(this, swipeEdge)
                    }
                },
                sharedTransitionScope = this,
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
            NdSceneTransition.SlideInCover
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
        lastChangeWasPop -> NdSceneTransition.SlideOutWipe
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
        NdSceneTransition.SlideOutWipe
    }

private fun <S> AnimatedContentTransitionScope<S>.transformFor(
    kind: NdSceneTransition,
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
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start),
                ExitTransition.None,
            )
        }

        NdSceneTransition.SlideOutWipe -> {
            ContentTransform(
                EnterTransition.None,
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End),
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
        contentKey = entry.contentKey,
        metadata =
            entry.metadata - NdSceneMetadata.KEY_PANE - NdSceneMetadata.KEY_DETAIL_PLACEHOLDER +
                pane + mapOf(NdSceneMetadata.KEY_TAB to tab) + mapOf(ND_NAV_KEY to key) +
                if (role != null) mapOf(ND_PANE_SLOT to role) else emptyMap(),
        content = { entry.Content() },
    )
}
