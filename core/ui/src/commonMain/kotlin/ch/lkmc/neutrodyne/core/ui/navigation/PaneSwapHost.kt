// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveComponentOverrideApi
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverride
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverrideScope
import androidx.compose.material3.adaptive.layout.AnimatedPaneScope
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey

/**
 * Post-decoration in-pane animator (08 Transitions): expanded multi-pane scenes keep one scene
 * identity while a pane's top entry changes, so `NavDisplay` never animates those swaps. This
 * host animates them inside `LocalAnimatedPaneOverride` instead.
 *
 * Each pane callsite remembers an [NdPaneCallsite]: a `SeekableTransitionState`-driven
 * `AnimatedContent` keyed on the pane's current contentKey. The live child always emits the
 * callsite's own `content` thunk; non-live children emit the fully decorated emission captured
 * while that state was live (`{ stampedEntry.Content() }`) or, for a previewed target that was
 * never shown, the decorated entry looked up in [NdPaneSwapHost.entries]. Because every retained
 * emission is a decorated entry, `DisposableEffect`-tracked `keysInComposition` bookkeeping keeps
 * the popped entry's saveable state and ViewModel store alive for exactly as long as it is drawn —
 * no navigation membership lives here.
 *
 * The callsite learns its current key from [PaneSwapTransportContent], which only ever runs inside
 * the live `content` thunk (captured thunks bypass it), so a retained emission can never publish.
 *
 * Back inside a two-pane scene is owned by [PaneTopBackHandler]: it lives inside the pane content,
 * which composes after the scene's own boundary handler and therefore wins gesture precedence for
 * the pane that hosts the stack top. That restores a single-entry pop for same-scaffold-value
 * tops (issue #75 — `PopUntilScaffoldValueChange` walks past them and over-pops) and collapses a
 * run of top extra-pane entries onto the boundary the gates pin. Every other handler — scene
 * boundary, `NavDisplay`, overlays — still outranks [PaneSwapBackHandler], the composition-earliest
 * fallback that only ever sees unclaimed gestures (tab-root fallbacks).
 */
internal val LocalNdPaneSwap = compositionLocalOf<NdPaneSwapHost?> { null }

internal val LocalNdPaneCallsite = compositionLocalOf<NdPaneCallsite?> { null }

internal class NdPaneSwapHost {
    /** Multi-pane only — single-pane layouts animate through the scene transition instead. */
    var enabled by mutableStateOf(false)

    /** The navigation model the in-pane handler mutates on a committed back gesture. */
    var navigation: NavigationState? = null

    /** Latest decorated entries by (tab, contentKey) — preview targets and role lookups. */
    var entries: Map<Pair<TopLevelKey, Any>, NavEntry<NavKey>> = emptyMap()

    /** Role-ordered decorated entries by (tab, role) in stack order — drives pop/preview targets. */
    var roleEntries: Map<Pair<TopLevelKey, String>, List<NavEntry<NavKey>>> = emptyMap()

    /** The contentKey of each tab's stack top — which pane hosts the stack top. */
    var tabTops: Map<TopLevelKey, Any> = emptyMap()

    /** The entry below [key] in the same (tab, role) pane — what a pop of it reveals. */
    fun previousPaneKey(
        tab: TopLevelKey,
        role: String,
        key: Any,
    ): Any? {
        val list = roleEntries[tab to role] ?: return null
        val index = list.indexOfFirst { it.contentKey == key }
        return list.getOrNull(index - 1)?.contentKey
    }

    /**
     * Every live pane top for [tab] — the last entry stamped with each role. A retained
     * outgoing thunk whose key reappears here is superseded by a live emission somewhere;
     * emitting it would mount a second `SaveableStateProvider` for the same key.
     */
    fun livePaneKeys(tab: TopLevelKey): Set<Any> =
        roleEntries.entries
            .filter { it.key.first == tab }
            .mapNotNullTo(mutableSetOf()) { it.value.lastOrNull()?.contentKey }

    /** Count of consecutive stack-top entries in the extra pane — a boundary back collapses them. */
    fun consecutiveTopExtras(tab: TopLevelKey): Int {
        val state = navigation ?: return 1
        val stack = state.stack(tab)
        var count = 0
        for (i in stack.size - 1 downTo 0) {
            val key = stack[i]
            val role = entries[tab to key]?.metadata?.get(NdSceneMetadata.KEY_PANE) as? String
            if (role != NdSceneMetadata.PANE_EXTRA) break
            count++
        }
        return count.coerceAtLeast(1)
    }
}

private var nextCallsiteId = 0

/** Per-pane-callsite swap state. Remembered inside the override's pane body. */
internal class NdPaneCallsite {
    val id = nextCallsiteId++

    /** The contentKey the callsite's `content` thunk currently emits — the live pane top. */
    var liveKey: Any? by mutableStateOf(null)

    /** Owning tab and pane role, stamped by the live transport. */
    var tab: TopLevelKey? by mutableStateOf(null)
    var role: String? by mutableStateOf(null)

    /** The pane's seekable transition — created by [NdPaneSwapContent], driven by gestures. */
    var transition: SeekableTransitionState<Any?>? = null

    /** True while a predictive gesture is scrubbing the transition. */
    var seeking by mutableStateOf(false)

    /**
     * Emission thunks captured while each state was live — `{ stampedEntry.Content() }` re-emits
     * the fully decorated chain at its retained position, keeping owner/saveable/lifecycle
     * bookkeeping continuous for exactly as long as the child stays composed.
     */
    val thunks = mutableMapOf<Any, @Composable () -> Unit>()

    /**
     * The same-role entry below each key, captured while it was live — after a pop the outgoing
     * key is gone from `host.roleEntries`, so the wipe/cover classification must read this
     * capture-time history, not the current stack.
     */
    val prevOf = mutableMapOf<Any, Any?>()
}

/**
 * The `LocalAnimatedPaneOverride` installed by `NeutrodyneNavHost`. Replicates the default pane
 * body's essentials on the public surface — pane-visibility `AnimatedVisibility` on
 * `scaffoldStateTransition`, the scaffold's pane saveable bucket, clip bounds — and wraps the
 * pane content in the pane-swap `AnimatedContent`. The default's internal-only parts
 * (`animatedPane()` marker, bounds animation, levitated scrim) are intentionally absent; hidden
 * panes simply stop being placed.
 */
@OptIn(ExperimentalMaterial3AdaptiveComponentOverrideApi::class)
internal object NdPaneSwapOverride : AnimatedPaneOverride {
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Composable
    override fun <Role : PaneScaffoldRole, ScaffoldValue : PaneScaffoldValue<Role>>
        AnimatedPaneOverrideScope<Role, ScaffoldValue>.AnimatedPane() {
        with(scope) {
            scaffoldStateTransition.AnimatedVisibility(
                visible = { value: ScaffoldValue -> value[paneRole] != PaneAdaptedValue.Hidden },
                modifier = modifier.clipToBounds(),
                enter = enterTransition,
                exit = exitTransition,
            ) {
                saveableStateHolder.SaveableStateProvider(paneRole.toString()) {
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        NdPaneSwapContent(
                            content = content,
                            paneScope = AnimatedPaneScope.create(this@AnimatedVisibility),
                            scaffoldRole = paneRole,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The pane-callsite swap layer: a `SeekableTransitionState` whose target tracks the callsite's
 * live key. Pops wipe the outgoing content off toward the end edge; anything else covers with the
 * incoming content — direction is classified structurally (target == previous same-pane entry)
 * rather than by stack flags so a predictive seek and its committed pop share one transition.
 */
@Composable
private fun NdPaneSwapContent(
    content: @Composable AnimatedPaneScope.() -> Unit,
    paneScope: AnimatedPaneScope,
    scaffoldRole: PaneScaffoldRole,
) {
    // Keyed on the role: forEachPaneByPriority only iterates adapted panes, so a callsite's
    // composition position can serve a different role after a pane restructure. A migrated
    // position gets a fresh callsite instead of animating across roles.
    val callsite = remember(scaffoldRole) { NdPaneCallsite() }
    println(
        "PANE-DEBUG ndps cs=${callsite.id} srole=$scaffoldRole content=${content.hashCode()}"
    )
    val host = LocalNdPaneSwap.current
    val transition = remember(callsite) { SeekableTransitionState<Any?>(null) }
    callsite.transition = transition
    val liveKey = callsite.liveKey

    // Track the published live key: seed instantly, animate every later change — unless a
    // predictive gesture is currently scrubbing (it owns the target until it ends). Thunk
    // captures are pruned only once the transition has settled, so an outgoing state can
    // never lose its retained emission mid-flight.
    LaunchedEffect(liveKey) {
        println(
            "PANE-DEBUG effect role=${callsite.role} live=$liveKey cur=${transition.currentState} " +
                "tgt=${transition.targetState} seeking=${callsite.seeking}"
        )
        if (liveKey == null) return@LaunchedEffect
        if (transition.currentState == null) {
            transition.snapTo(liveKey)
        } else if (transition.targetState != liveKey && !callsite.seeking) {
            transition.animateTo(liveKey)
            println("PANE-DEBUG settled role=${callsite.role} live=$liveKey")
        }
        val keep = setOfNotNull(callsite.liveKey, transition.targetState)
        callsite.thunks.keys.retainAll(keep)
        callsite.prevOf.keys.retainAll(keep)
    }

    val runningTransition = rememberTransition(transition)
    val spec = NeutrodyneMotion.effects<IntOffset>()
    runningTransition.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val prev = initialState?.let { callsite.prevOf[it] }
            paneSwapTransform(spec, targetState == prev)
        },
        contentAlignment = Alignment.TopStart,
    ) { state ->
        CompositionLocalProvider(LocalNdPaneCallsite provides callsite) {
            // The live branch is keyed on the callsite's own published key: in a retained
            // outgoing scene the callsite's pane still emits its scene-local top, which may
            // differ from the current stack's pane tops. A retained state whose key is live
            // again (reset+repush) is superseded — the live emission owns that provider.
            val liveKeys = callsite.tab?.let { host?.livePaneKeys(it) }.orEmpty()
            println(
                "PANE-DEBUG cs=${callsite.id}/$scaffoldRole emit state=$state " +
                    "live=${callsite.liveKey} role=${callsite.role} tab=${callsite.tab} " +
                    "liveKeys=$liveKeys thunks=${callsite.thunks.keys}"
            )
            when {
                state == null || state == callsite.liveKey -> {
                    androidx.compose.runtime.DisposableEffect(state) {
                        println("PANE-DEBUG +LIVE $state cs=${callsite.id}")
                        onDispose { println("PANE-DEBUG -LIVE $state cs=${callsite.id}") }
                    }
                    println("PANE-DEBUG branch=live state=$state cs=${callsite.id}")
                    content(paneScope)
                }
                state in liveKeys -> Box(Modifier.fillMaxSize())
                else -> {
                    androidx.compose.runtime.DisposableEffect(state) {
                        println("PANE-DEBUG +RET $state cs=${callsite.id}")
                        onDispose { println("PANE-DEBUG -RET $state cs=${callsite.id}") }
                    }
                    val t = callsite.thunks[state]
                    if (t != null) {
                        println("PANE-DEBUG branch=thunk state=$state cs=${callsite.id}")
                        t()
                    } else {
                        val e = callsite.tab?.let { host?.entries?.get(it to state) }
                        if (e != null) {
                            println("PANE-DEBUG branch=host-entry state=$state cs=${callsite.id}")
                            e.Content()
                        } else {
                            println("PANE-DEBUG branch=content state=$state cs=${callsite.id}")
                            content(paneScope)
                        }
                    }
                }
            }
        }
    }
}

private fun <S> AnimatedContentTransitionScope<S>.paneSwapTransform(
    spec: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset>,
    isWipe: Boolean,
): ContentTransform =
    if (isWipe) {
        ContentTransform(
            EnterTransition.None,
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = spec,
            ),
        )
    } else {
        ContentTransform(
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = spec,
            ),
            ExitTransition.None,
            targetContentZIndex = 1f,
        )
    }

/**
 * The transport wrapped around every decorated entry — inside a pane callsite it publishes the
 * emitted (tab, role, contentKey) and hosts the pane-top back handler before emitting the
 * decorated content. Outside a callsite (compact scenes, overlays, placeholders) it emits
 * directly. It adds no membership: [decorated] is invoked exactly once.
 */
@Composable
internal fun PaneSwapTransportContent(decorated: NavEntry<NavKey>) {
    val callsite = LocalNdPaneCallsite.current
    val host = LocalNdPaneSwap.current
    val tab = decorated.metadata[NdSceneMetadata.KEY_TAB] as? TopLevelKey
    val role = decorated.metadata[NdSceneMetadata.KEY_PANE] as? String
    if (callsite == null || host == null || !host.enabled || tab == null || role == null) {
        println(
            "PANE-DEBUG XPORT-direct ${decorated.contentKey} cs=${callsite?.hashCode()} " +
                "role=$role tab=$tab enabled=${host?.enabled}"
        )
        decorated.Content()
        return
    }
    println(
        "PANE-DEBUG XPORT ${decorated.contentKey}@${decorated.hashCode()} " +
            "cs=${callsite?.id} role=$role tab=$tab enabled=${host.enabled}"
    )
    if (callsite?.role != null && callsite.role != role) {
        println(
            "PANE-DEBUG STACK for mismatched ${decorated.contentKey}:\n" +
                Exception().stackTraceToString().lineSequence().take(60).joinToString("\n")
        )
    }
    callsite.liveKey = decorated.contentKey
    callsite.tab = tab
    callsite.role = role
    callsite.thunks[decorated.contentKey] = { decorated.Content() }
    // Capture-time history: the outgoing key is absent from roleEntries once popped.
    callsite.prevOf[decorated.contentKey] = host.previousPaneKey(tab, role, decorated.contentKey)
    PaneTopBackHandler(host, callsite, decorated, tab, role)
    decorated.Content()
}

/**
 * The in-pane back handler (issue #75): enabled only in the pane that hosts the selected stack's
 * top entry, so exactly one pane handler claims a gesture. It composes inside the pane content —
 * after the scene's boundary handler registers — and therefore wins precedence. A committed
 * gesture pops one entry, or the run of top extra-pane entries when the top is extra (the extras
 * collapse onto the scaffold boundary). Predictive progress seeks the same pane wipe the commit
 * runs; a cancelled gesture animates back to the live state.
 */
@Composable
private fun PaneTopBackHandler(
    host: NdPaneSwapHost,
    callsite: NdPaneCallsite,
    decorated: NavEntry<NavKey>,
    tab: TopLevelKey,
    role: String,
) {
    val state = host.navigation ?: return
    val stack = state.stack(tab)
    val enabled =
        state.selectedTab == tab &&
            stack.size > 1 &&
            host.tabTops[tab] == decorated.contentKey
    val eventState =
        rememberNavigationEventState<NavigationEventInfo.None>(NavigationEventInfo.None)
    NavigationBackHandler(
        state = eventState,
        isBackEnabled = enabled,
        onBackCancelled = { callsite.seeking = false },
        onBackCompleted = {
            callsite.seeking = false
            repeat(host.consecutiveTopExtras(tab)) { state.pop() }
        },
    )

    val gesture = eventState.transitionState
    LaunchedEffect(gesture) {
        val ts = callsite.transition ?: return@LaunchedEffect
        val inProgress = gesture as? NavigationEventTransitionState.InProgress
        if (
            inProgress == null ||
                inProgress.direction != NavigationEventTransitionState.TRANSITIONING_BACK
        ) {
            // The gesture ended: on commit the live-key tracker finishes the wipe; on cancel the
            // transition animates back to the live state.
            if (callsite.seeking) {
                callsite.seeking = false
                callsite.liveKey?.let { ts.animateTo(it) }
            }
            return@LaunchedEffect
        }
        val preview = host.previousPaneKey(tab, role, decorated.contentKey) ?: return@LaunchedEffect
        callsite.seeking = true
        ts.seekTo(inProgress.latestEvent.progress, preview)
    }
}

/**
 * The lowest-precedence back handler: enabled whenever the selected stack can pop or fall back to
 * Feeds. Every pane-top handler and every library handler (scene boundary, `NavDisplay`,
 * overlays) registers later and wins, so this handler only runs gestures nothing else claims —
 * the tab-root fallback.
 */
@Composable
internal fun PaneSwapBackHandler(
    state: NavigationState,
    host: NdPaneSwapHost,
) {
    val eventState =
        rememberNavigationEventState<NavigationEventInfo.None>(NavigationEventInfo.None)
    NavigationBackHandler(
        state = eventState,
        isBackEnabled =
            state.stack(state.selectedTab).size > 1 || state.selectedTab != FeedsKey,
        onBackCancelled = {},
        onBackCompleted = { state.pop() },
    )
}
