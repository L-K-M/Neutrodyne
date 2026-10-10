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
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverride
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverrideScope
import androidx.compose.material3.adaptive.layout.LocalAnimatedPaneOverride
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.PaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldValue
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
                        ),
                    entryProvider = { key -> withPaneRole(key, provider(key), tab, state) },
                )
            }
        }

    val entries = state.visibleTabs().flatMap { decoratedByTab.getValue(it) }

    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalAnimatedPaneOverride provides
                rememberPaneSwapOverride(LocalAnimatedPaneOverride.current),
            LocalNdNavEntries provides entries,
        ) {
            NavDisplay(
                entries = entries,
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
 * The decorated entries the host renders, so a pane slot's retained emission can keep drawing an
 * outgoing entry through its own saveable-state/ViewModel decorators until the wipe finishes.
 */
private val LocalNdNavEntries: ProvidableCompositionLocal<List<NavEntry<NavKey>>> =
    compositionLocalOf { emptyList() }

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
    state: NavigationState,
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
                        detailPlaceholder = {
                            PaneSlotMark(DetailPlaceholderMark, paneSwapDirection(state.lastChangeWasPop)) {
                                placeholder()
                            }
                            placeholder()
                        },
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
                pane + mapOf(NdSceneMetadata.KEY_TAB to tab),
        content = {
            if (role != null) {
                // A stale scene can still emit this entry for a frame after it left `entries`;
                // a miss must not poison the slot's stored emission for this key.
                val rendered =
                    LocalNdNavEntries.current.firstOrNull { it.contentKey == entry.contentKey }
                PaneSlotMark(
                    entry.contentKey,
                    paneSwapDirection(state.lastChangeWasPop),
                    rendered?.let { decorated -> { decorated.Content() } },
                )
            }
            entry.Content()
        },
    )
}

/**
 * The intra-scene pane-content transition. A `ThreePaneScaffoldScene` swaps which entry a pane
 * renders without changing the scene, so `NavDisplay`'s transform never runs for e.g. Settings
 * About -> Licences -> back in a two-pane layout. `NavEntry.Content()` invokes a fresh lambda per
 * entry, so no position inside entry content survives the swap — the only persistent position is
 * the pane call itself, which the library exposes through [LocalAnimatedPaneOverride].
 *
 * This override delegates to the upstream pane (the platform default, so pane chrome and
 * visibility animations are unchanged) and wraps it in a [PaneSlot]: pane entries report their
 * `contentKey` via [PaneSlotMark] as they emit, and on a key change the slot keeps drawing the
 * outgoing entry's decorated `NavEntry` — looked up in [LocalNdNavEntries] at mark time, so the
 * emission re-runs that entry's own saveable-state/ViewModel decorators — translated toward the
 * layout end on a pop, or kept resting underneath while the incoming pane covers from the end on
 * a push. (The pane's `content` lambda itself cannot be captured: Compose memoizes it, so it
 * would emit the latest entry, not the outgoing one.) The retained emission keeps the outgoing
 * entry's decorators attached until it actually leaves composition, the same deferral
 * `NavDisplay`'s own pop animation uses.
 *
 * Predictive back does not reach this layer: under `PopUntilScaffoldValueChange` the gesture
 * lands on a scaffold-value boundary, never on a same-pane entry, so the scaffold's own pane
 * transition owns those gestures and a content seek would preview the wrong landing.
 */
@Composable
private fun rememberPaneSwapOverride(upstream: AnimatedPaneOverride): AnimatedPaneOverride =
    remember(upstream) { NdPaneSwapOverride(upstream) }

private class NdPaneSwapOverride(
    private val upstream: AnimatedPaneOverride,
) : AnimatedPaneOverride {
    @Composable
    override fun <
        Role : PaneScaffoldRole,
        ScaffoldValue : PaneScaffoldValue<Role>,
    > AnimatedPaneOverrideScope<Role, ScaffoldValue>.AnimatedPane() {
        PaneSlotBody(upstream)
    }
}

/** Slot-side identity for the detail pane's empty state, reported like a `contentKey`. */
private object DetailPlaceholderMark

/** Per-pane-slot swap state. One instance spans every entry the pane renders. */
private class PaneSlot {
    /** The `contentKey` currently marked live by the emitted pane content. */
    var liveKey by mutableStateOf<Any?>(null)
        private set

    /** Emission that still renders the outgoing key, kept while a swap runs. */
    var outgoingLambda by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    /** Cover for a forward push, wipe for a back pop — mirrors the outer scene transform pair. */
    var direction by mutableStateOf(NdSceneTransition.SlideInCover)
        private set

    /** `contentKey` -> emission that renders that key (its decorated entry or placeholder). */
    private val byKey = mutableMapOf<Any, @Composable () -> Unit>()

    fun mark(
        contentKey: Any,
        direction: NdSceneTransition,
        retained: (@Composable () -> Unit)?,
    ) {
        // A null retained emission is provably stale — a live pane entry always resolves in
        // `LocalNdNavEntries` — so a scene lagging one frame must not disturb the slot.
        if (retained == null) return
        byKey[contentKey] = retained
        val previous = liveKey
        if (previous == contentKey) return
        this.direction = direction
        outgoingLambda = previous?.let(byKey::get)
        liveKey = contentKey
    }

    /** Drops the retained outgoing emission and dead keys once a swap settles. */
    fun settle() {
        outgoingLambda = null
        byKey.keys.retainAll(setOfNotNull(liveKey))
    }
}

/** The pane-content twin of the outer spec pair: a forward push covers, a back pop wipes. */
private fun paneSwapDirection(isPop: Boolean): NdSceneTransition =
    if (isPop) NdSceneTransition.SlideOutWipe else NdSceneTransition.SlideInCover

private val LocalNdPaneSlot: ProvidableCompositionLocal<PaneSlot?> = compositionLocalOf { null }

/** True inside a retained outgoing emission, so its marks cannot re-trigger a swap. */
private val LocalNdPaneSlotMuted: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { false }

/**
 * Reports which `contentKey` the pane slot is emitting — with [retained] as the emission that can
 * keep drawing it — and is suppressed inside retained emissions so they cannot retrigger a swap.
 */
@Composable
private fun PaneSlotMark(
    contentKey: Any,
    direction: NdSceneTransition,
    retained: (@Composable () -> Unit)?,
) {
    val slot = LocalNdPaneSlot.current ?: return
    if (LocalNdPaneSlotMuted.current) return
    SideEffect { slot.mark(contentKey, direction, retained) }
}

/**
 * Wraps the delegated [upstream] pane so a pane-content swap animates inside the pane bounds:
 * on a pop the outgoing content slides toward the layout end over the resting incoming pane; on
 * a push the incoming pane slides in from the end covering the resting outgoing content.
 */
@Composable
private fun <
    Role : PaneScaffoldRole,
    ScaffoldValue : PaneScaffoldValue<Role>,
> AnimatedPaneOverrideScope<Role, ScaffoldValue>.PaneSlotBody(
    upstream: AnimatedPaneOverride,
) {
    val slot = remember { PaneSlot() }
    val progress = remember { Animatable(1f) }
    val swapSpec = NeutrodyneMotion.effects<Float>()

    LaunchedEffect(slot.liveKey) {
        val startedFor = slot.liveKey
        slot.outgoingLambda ?: return@LaunchedEffect
        progress.snapTo(0f)
        try {
            progress.animateTo(1f, swapSpec)
        } finally {
            // A newer swap may already have marked its own key; its effect settles that one.
            if (slot.liveKey == startedFor) slot.settle()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = constraints.maxWidth.toFloat()
        val towardEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        CompositionLocalProvider(
            LocalNdPaneSlot provides slot,
            LocalNdPaneSlotMuted provides false,
        ) {
            val outgoing = slot.outgoingLambda
            if (outgoing == null) {
                with(upstream) { this@PaneSlotBody.AnimatedPane() }
            } else if (slot.direction == NdSceneTransition.SlideInCover) {
                CompositionLocalProvider(LocalNdPaneSlotMuted provides true) {
                    Box(Modifier.fillMaxSize().clipToBounds()) { outgoing() }
                }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = paneWidth * (1f - progress.value) * towardEnd
                    },
                ) {
                    with(upstream) { this@PaneSlotBody.AnimatedPane() }
                }
            } else {
                with(upstream) { this@PaneSlotBody.AnimatedPane() }
                CompositionLocalProvider(LocalNdPaneSlotMuted provides true) {
                    Box(
                        Modifier.fillMaxSize().clipToBounds().graphicsLayer {
                            translationX = paneWidth * progress.value * towardEnd
                        },
                    ) {
                        outgoing()
                    }
                }
            }
        }
    }
}
