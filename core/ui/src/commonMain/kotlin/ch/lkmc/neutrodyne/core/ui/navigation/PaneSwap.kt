// SPDX-License-Identifier: Unlicense

// The nlopez compositionlocal-allowlist check mis-evaluates declarations in the navigation
// package — names added to compose_allowed_composition_locals pass and fail
// nondeterministically while the same mechanism works elsewhere — so the file is suppressed
// like NeutrodyneNavHost.kt.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverride
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverrideScope
import androidx.compose.material3.adaptive.layout.PaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import kotlinx.coroutines.delay

/** Metadata stamp carrying the entry's pane role ([NdSceneMetadata.PANE_LIST] and friends). */
internal const val ND_PANE_SLOT = "ndPaneSlot"

/** Metadata stamp carrying the entry's [NavKey], so a dropped stack key maps to a contentKey. */
internal const val ND_NAV_KEY = "ndNavKey"

/** Upper bound a pinned pane entry stays on the decorated list without a slot claiming it. */
private const val PINNED_ENTRY_TIMEOUT_MS = 800L

/**
 * Which pane-top entries stay pinned as their pane's top so the outgoing pane content keeps
 * drawing through the swap animation. Filled by [beginPass] diffs and drained by
 * [PaneSlot.settle] or [expireAll] on the host's timeout.
 *
 * A pane swap cannot re-emit the outgoing entry at a second position: the pane's own
 * visibility machinery can keep the callsite emission composed through the swap without
 * re-invoking it, so a parallel emission would register a second `SaveableStateProvider` for
 * the same key and crash. Pinning avoids re-emission entirely — the outgoing entry keeps its
 * one callsite emission, and the incoming entry emits only inside the transition Box.
 */
internal class PaneRetention {
    /**
     * [navKey] is presented directly above [coveredNavKey] while a pane swap animates, keeping
     * it as the pane's top entry so the scene keeps emitting it at its callsite. The covered
     * entry is resolved lazily by [coveredEntry]: for a push it is decorated only in this pass,
     * after the pin is created.
     */
    class Pin(
        val navKey: NavKey,
        val coveredNavKey: NavKey,
        val tab: TopLevelKey,
        val direction: NdSceneTransition,
    )

    /** Outgoing contentKey -> pin; snapshot state so releases recompose. */
    val pins = mutableStateMapOf<Any, Pin>()

    /** contentKeys emitted through a pane slot; pruned to mapped + pinned each host pass. */
    val emitted = mutableSetOf<Any>()

    /** NavKey -> contentKey from the latest decorated pass; maps stack keys to entries. */
    private var contentKeyOf: Map<NavKey, Any> = emptyMap()

    /** NavKey -> pane role from the latest decorated pass; drives the per-pane top diff. */
    private var roleOf: Map<NavKey, String> = emptyMap()

    /** NavKey -> pane role, queried lazily — a pushed key has no decorated entry yet. */
    var roleResolver: (NavKey) -> String? = { null }

    /** contentKey -> latest decorated entry, so a covered incoming entry emits its content. */
    val entryFor = mutableMapOf<Any, NavEntry<NavKey>>()

    /** Last pass' stacks; lets the host diff per-pane tops across pushes and pops. */
    private var previousStacks: Map<TopLevelKey, List<NavKey>> = emptyMap()

    /**
     * Diffs [stacks]' per-pane top entries against the previous pass: when a pane's top key
     * changes, the pane is swapping content, and the previous top is pinned above its
     * successor — dropped by a pop (wipe) or still live under a push (cover). Runs before
     * decoration, so this pass' [augmentedStack] lists already see the new pins.
     */
    fun beginPass(stacks: Map<TopLevelKey, List<NavKey>>) {
        emitted.retainAll(contentKeyOf.values + pins.keys)
        for ((tab, liveStack) in stacks) {
            val previousStack = previousStacks[tab]
            if (previousStack != null) diffPaneTops(tab, previousStack, liveStack)
            pins.entries.removeIf { it.value.tab == tab && it.value.coveredNavKey !in liveStack }
        }
        // Snapshot the contents: the stacks are live mutable lists and a stored reference would
        // already show the change, leaving nothing to diff.
        previousStacks = stacks.mapValues { it.value.toList() }
    }

    private fun diffPaneTops(
        tab: TopLevelKey,
        previousStack: List<NavKey>,
        liveStack: List<NavKey>,
    ) {
        val live = liveStack.toSet()
        val roleAt = { key: NavKey -> roleOf[key] ?: roleResolver(key) }
        val roles = (previousStack + liveStack).mapNotNullTo(mutableSetOf()) { roleAt(it) }
        for (role in roles) {
            val previousTop = previousStack.lastOrNull { roleAt(it) == role } ?: continue
            val currentTop = liveStack.lastOrNull { roleAt(it) == role } ?: continue
            if (previousTop == currentTop) continue
            val previousKey = contentKeyOf[previousTop]
            if (previousKey == null || previousKey !in emitted || previousKey in pins) continue
            when {
                // Back pop: the previous top left the stack — pin it above the same-role
                // successor the pane now covers.
                previousTop !in live -> {
                    pins[previousKey] =
                        Pin(previousTop, currentTop, tab, NdSceneTransition.SlideOutWipe)
                }

                // Forward push: the new top landed above a still-live previous top — pin the
                // previous top back on so its callsite emission survives the cover.
                liveStack.indexOf(currentTop) > liveStack.indexOf(previousTop) -> {
                    pins[previousKey] =
                        Pin(previousTop, currentTop, tab, NdSceneTransition.SlideInCover)
                }
            }
        }
    }

    /**
     * [stack] with every pinned key presented directly above its covered key, so a pinned pane
     * keeps its outgoing entry as the pane top — the scene's `lastList`/`lastDetail` picks it
     * and the callsite emission — and with it the saveable/ViewModel bookkeeping inside — stays
     * continuously composed through the swap. The decorator `onPop` that clears that state is
     * also keyed on this list's membership, so the pin defers the pop exactly until release.
     */
    fun augmentedStack(
        tab: TopLevelKey,
        stack: List<NavKey>,
    ): List<NavKey> {
        val pinsForTab = pins.values.filter { it.tab == tab }
        if (pinsForTab.isEmpty()) return stack
        return buildList {
            val pinnedLive = pinsForTab.mapTo(mutableSetOf()) { it.navKey }
            stack.forEach { key ->
                if (key in pinnedLive) return@forEach
                add(key)
                pinsForTab.forEach { pin -> if (pin.coveredNavKey == key) add(pin.navKey) }
            }
        }
    }

    /**
     * Rebuilds the lookup maps for the freshly decorated [entries]: [entryFor] keeps the newest
     * entry per contentKey, [contentKeyOf] and [roleOf] map each NavKey for the next top diff.
     */
    fun indexEntries(entries: List<NavEntry<NavKey>>) {
        entries.forEach { entry ->
            val role = entry.metadata[ND_PANE_SLOT] as? String ?: return@forEach
            entryFor[entry.contentKey] = entry
            (entry.metadata[ND_NAV_KEY] as? NavKey)?.let { roleOf += it to role }
        }
        entryFor.keys.retainAll(entries.mapTo(mutableSetOf()) { it.contentKey } + emitted)
        contentKeyOf =
            entries
                .mapNotNull { entry ->
                    (entry.metadata[ND_NAV_KEY] as? NavKey)?.let { it to entry.contentKey }
                }.toMap()
    }

    /** The decorated entry covered by [pin] — its sole emission lives in the transition Box. */
    fun coveredEntry(pin: Pin): NavEntry<NavKey>? = entryFor[contentKeyOf[pin.coveredNavKey]]

    /** Drops pins [stamp] recorded before the timeout; releases them all at once. */
    fun expireAll(stamp: Set<Any>) {
        pins.keys.removeAll(stamp)
    }

    fun unpin(key: Any) {
        pins.remove(key)
    }
}

/**
 * The intra-scene pane-content transition. A `ThreePaneScaffoldScene` swaps which entry a pane
 * renders without changing the scene, so `NavDisplay`'s transform never runs for e.g. Settings
 * About -> Licences -> back in a two-pane layout. `NavEntry.Content()` invokes a fresh lambda
 * per entry, so no position inside entry content survives the swap — the only persistent
 * position is the pane call itself, which the library exposes through
 * `LocalAnimatedPaneOverride`.
 *
 * This override delegates to the upstream pane (the platform default, so pane chrome and
 * visibility animations are unchanged) and wraps it in a [PaneSlot]: [PaneRetention] keeps the
 * outgoing entry pinned as the pane's top for the swap's duration, so the pane callsite keeps
 * emitting its decorated content — saveable state, ViewModel store and lifecycle ownership
 * inside never leave composition — while [PaneTransportContent] draws the swap around it:
 * outgoing content translated toward the layout end on a pop, or kept resting underneath while
 * the incoming pane covers from the end on a push. No entry is ever emitted at two positions.
 *
 * Predictive back does not reach this layer: under `PopUntilScaffoldValueChange` the gesture
 * lands on a scaffold-value boundary, never on a same-pane entry, so the scaffold's own pane
 * transition owns those gestures and a content seek would preview the wrong landing.
 */
@Composable
internal fun rememberPaneSwapOverride(
    upstream: AnimatedPaneOverride,
    retention: PaneRetention,
): AnimatedPaneOverride = remember(upstream, retention) { NdPaneSwapOverride(upstream, retention) }

private class NdPaneSwapOverride(
    private val upstream: AnimatedPaneOverride,
    private val retention: PaneRetention,
) : AnimatedPaneOverride {
    @Composable
    override fun <
        Role : PaneScaffoldRole,
        ScaffoldValue : PaneScaffoldValue<Role>,
    > AnimatedPaneOverrideScope<Role, ScaffoldValue>.AnimatedPane() {
        PaneSlotBody(upstream, retention)
    }
}

/**
 * Emits a roled entry's decorated content. While its key is pinned — it is the pane's
 * transitioning top — the same call also emits the incoming entry's decorated content and
 * translates the two through the wipe or cover.
 *
 * This is the only position that ever emits each entry: the pinned outgoing stays at its
 * callsite so its subtree — including Navigation3's saveable-state and ViewModel bookkeeping —
 * never leaves composition, and the incoming entry emits solely inside the transition Box
 * (the scene only renders the pane's pinned top, not the covered key). When the slot settles
 * the pin releases; the next pass renders the new top at the callsite in one ordinary
 * recomposition, and the outgoing's bookkeeping disposes it exactly once.
 */
@Composable
private fun PaneTransportContent(decorated: NavEntry<NavKey>) {
    val slot = LocalNdPaneSlot.current
    slot?.retention?.emitted?.add(decorated.contentKey)
    val pin = slot?.retention?.pins?.get(decorated.contentKey)
    val incoming = pin?.let { slot?.retention?.coveredEntry(it) }
    if (slot != null && pin != null && incoming != null) {
        slot.arm(decorated.contentKey, pin.direction)
    }
    val wiping = incoming != null && pin?.direction == NdSceneTransition.SlideOutWipe
    val covering = incoming != null && pin?.direction == NdSceneTransition.SlideInCover
    // Three fixed positions: `decorated.Content()` keeps the same group path whether or not a
    // swap runs, so arming or settling a pin never restarts its subtree — moving it under the
    // swap Box would recreate the decorated ViewModelStoreOwner wrapper the test asserts on.
    Box(Modifier.fillMaxSize().clipToBounds()) {
        val towardEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        // Beneath: the incoming pane revealed as the outgoing wipes toward the layout end.
        if (wiping) incoming.Content()
        // `size` inside the graphicsLayer block is the pane's draw-time size, so the width is
        // known without measuring ahead of composition.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX =
                    if (wiping) size.width * (slot?.progress ?: 1f) * towardEnd else 0f
            },
        ) {
            decorated.Content()
        }
        // On top: the incoming pane covering from the layout end on a push.
        if (covering) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * (1f - (slot?.progress ?: 1f)) * towardEnd
                },
            ) {
                incoming.Content()
            }
        }
    }
}

/** Wraps pane-roled entries in the pane transport; other entries pass through. */
internal fun NavEntry<NavKey>.withPaneTransport(): NavEntry<NavKey> =
    if (metadata.containsKey(ND_PANE_SLOT)) {
        NavEntry(navEntry = this) { PaneTransportContent(this) }
    } else {
        this
    }

/** Per-pane-slot swap state. One instance spans every entry the pane renders. */
internal class PaneSlot(
    val retention: PaneRetention,
) {
    /** The pinned outgoing contentKey while a swap runs; null once the swap settles. */
    var outgoingKey by mutableStateOf<Any?>(null)
        private set

    /** Cover for a forward push, wipe for a back pop — mirrors the outer scene transform pair. */
    var direction by mutableStateOf(NdSceneTransition.SlideInCover)
        private set

    /** 0 -> 1 over the swap; written by the running animation, read in the draw phase. */
    var progress by mutableFloatStateOf(1f)

    /** Increments once per swap so the animation effect keys on the swap, not the emission. */
    var swapVersion by mutableIntStateOf(0)
        private set

    /** Arms the slot for [contentKey]'s pin; a repeated emission of the same pin is a no-op. */
    fun arm(
        contentKey: Any,
        direction: NdSceneTransition,
    ) {
        if (outgoingKey == contentKey) return
        this.direction = direction
        outgoingKey = contentKey
        progress = 0f
        swapVersion++
    }

    /** Drops the pinned outgoing emission and releases its stack pin. */
    fun settle() {
        outgoingKey?.let(retention::unpin)
        outgoingKey = null
    }
}

internal val LocalNdPaneSlot: ProvidableCompositionLocal<PaneSlot?> = compositionLocalOf { null }

/**
 * Provides the [PaneSlot] for one pane and runs its swap animation. The pane call delegates to
 * [upstream] unchanged; the transition drawing lives in [PaneTransportContent] where the
 * pinned callsite emission lands.
 */
@Composable
private fun <
    Role : PaneScaffoldRole,
    ScaffoldValue : PaneScaffoldValue<Role>,
> AnimatedPaneOverrideScope<Role, ScaffoldValue>.PaneSlotBody(
    upstream: AnimatedPaneOverride,
    retention: PaneRetention,
) {
    val slot = remember { PaneSlot(retention) }
    val swapSpec = NeutrodyneMotion.effects<Float>()

    CompositionLocalProvider(LocalNdPaneSlot provides slot) {
        with(upstream) { this@PaneSlotBody.AnimatedPane() }
    }

    LaunchedEffect(slot.swapVersion) {
        val version = slot.swapVersion
        val outgoing = slot.outgoingKey ?: return@LaunchedEffect
        try {
            animate(0f, 1f, animationSpec = swapSpec) { value, _ -> slot.progress = value }
        } finally {
            // A newer swap owns its own settle; only the version this effect ran for ends here.
            if (slot.swapVersion == version && slot.outgoingKey == outgoing) slot.settle()
        }
    }
}

/**
 * Expires pinned entries a pane never emitted (e.g. a pane that never re-composed after the
 * stack change) so they cannot linger on the decorated list past [PINNED_ENTRY_TIMEOUT_MS].
 */
@Composable
internal fun PinnedEntryExpiry(retention: PaneRetention) {
    val stamp = retention.pins.keys.toSet()
    LaunchedEffect(stamp) {
        if (stamp.isEmpty()) return@LaunchedEffect
        delay(PINNED_ENTRY_TIMEOUT_MS)
        retention.expireAll(stamp)
    }
}
