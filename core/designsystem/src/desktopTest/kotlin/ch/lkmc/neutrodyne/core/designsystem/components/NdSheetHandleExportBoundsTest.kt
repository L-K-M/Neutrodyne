// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces the API 37 Subscribe journey ATF failures on the desktop by porting the
 * uncovered-space walk that Compose UI 1.12.1 uses to export accessibility bounds
 * (`SemanticsOwner.getAllUncoveredSemanticsNodesToIntObjectMap`): semantics children are
 * visited in reverse composition order and every "important" node subtracts its full
 * `touchBoundsInRoot` from the space later siblings may still claim. `touchBoundsInRoot`
 * of a clickable node is not clipped by its scroll container, so content scrolled past
 * the top of the sheet's scroll viewport claims the band above the viewport and the
 * drag handle - composed by Material before the content - exports clipped bounds
 * (the device measured 48x29 and 48x24 instead of 48x48).
 *
 * The port keeps the walk's exact traversal and subtraction order. Two details of the
 * original are omitted because they cannot change the handle's export: fake semantics
 * nodes, and the "include offscreen children" re-export of a scrolled merging node's
 * children, which only ever re-adds nodes inside that node's own unclipped bounds.
 */
@OptIn(ExperimentalTestApi::class)
class NdSheetHandleExportBoundsTest {
    @BeforeTest
    fun pinEnglishStrings() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @Test
    fun scrolledBodyDoesNotStealTheHandlesExportedTarget() =
        runDesktopComposeUiTest(width = 320, height = 640) {
            val scroll = ScrollState(0)
            setContent {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    NdModalBottomSheet(onDismissRequest = {}) { ScrollableSheetBody(scroll) }
                }
            }
            waitForIdle()

            // Scroll the body up 24 px, exactly the Apple-case device state: the first
            // body control then pokes 24 px above the scroll viewport, into the handle band.
            // The raw delta keeps the sheet itself out of it (its nested-scroll connection
            // would otherwise drag the sheet instead of scrolling the body).
            runOnIdle { scroll.dispatchRawDelta(POKE_PX) }
            waitForIdle()

            val body = onNodeWithTag(SCROLL_BODY_TAG, useUnmergedTree = true).fetchSemanticsNode()
            val topControl = onNodeWithTag(TOP_CONTROL_TAG, useUnmergedTree = true).fetchSemanticsNode()
            val handle =
                SemanticsMatcher("describes the drag handle") {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Drag handle")
                }.let { matcher ->
                    onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().single()
                }

            // Preconditions: without these the assertion below would be vacuous.
            val viewportTop = body.boundsInRoot.top
            val controlTop = topControl.touchBoundsInRoot.top
            assertTrue(
                controlTop > 0 && controlTop < handle.touchBoundsInRoot.bottom,
                "the scrolled body must poke into the handle band: controlTop=$controlTop" +
                    ", handle=${handle.touchBoundsInRoot}, viewportTop=$viewportTop" +
                    ", scroll=${scroll.value}..${scroll.maxValue}, control=${topControl.touchBoundsInRoot}",
            )
            assertTrue(
                with(density) {
                    handle.touchBoundsInRoot.width.toDp() >= MIN_TARGET &&
                        handle.touchBoundsInRoot.height.toDp() >= MIN_TARGET
                },
                "the handle's own touch target must stay ${MIN_TARGET}: ${handle.touchBoundsInRoot}",
            )

            val root = generateSequence(handle) { it.parent }.last()
            val exported = exportedBoundsByUncoveredWalk(root)
            val clickables =
                SemanticsMatcher("clickable") { node ->
                    node.config.contains(SemanticsActions.OnClick) ||
                        node.config.contains(SemanticsActions.OnLongClick)
                }.let { matcher ->
                    onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
                }
            val handleChainIds = generateSequence(handle) { it.parent }.map { it.id }.toSet()
            assertTrue(
                clickables.any { it.id in handleChainIds && exported.containsKey(it.id) },
                "the walk must export the handle's clickable wrappers",
            )

            val undersized =
                clickables
                    .mapNotNull { node ->
                        exported[node.id]?.let { node to it }
                    }.filter { (node, bounds) ->
                        with(density) { bounds.width.toDp() < MIN_TARGET || bounds.height.toDp() < MIN_TARGET }
                    }
            assertTrue(
                undersized.isEmpty(),
                "a11y targets under $MIN_TARGET after the uncovered-space walk: " +
                    undersized.joinToString("; ") { (node, bounds) ->
                        "id=${node.id} exported=$bounds cd=" +
                            "${node.config.getOrNull(SemanticsProperties.ContentDescription)}"
                    },
            )
        }

    /**
     * A sheet body tall enough to scroll, whose first control keeps a >= 48 dp touch
     * target. The control stands in for the Add sheet's URL field: clickable, so its
     * `touchBoundsInRoot` ignores the scroll viewport's top edge once scrolled past it.
     */
    @Composable
    private fun ScrollableSheetBody(scroll: ScrollState) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag(SCROLL_BODY_TAG)
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(TOP_CONTROL_TAG)
                    .clickable {},
            ) {
                Text("Sheet top control", Modifier.padding(8.dp))
            }
            repeat(BODY_LINES) { index -> Text("Filler line ${index + 1}", Modifier.padding(vertical = 12.dp)) }
            Text("Sheet bottom anchor", Modifier.padding(vertical = 12.dp))
        }
    }
}

/** Rectangular decomposition standing in for the platform `Region` the walk uses. */
private class UnaccountedSpace(
    initial: IntRect,
) {
    private var rects = mutableListOf(initial)

    /** Bounding box of the part of [rect] not yet claimed, or null when fully claimed. */
    fun claimedBounds(rect: IntRect): IntRect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (piece in rects) {
            val l = maxOf(piece.left, rect.left)
            val t = maxOf(piece.top, rect.top)
            val r = minOf(piece.right, rect.right)
            val b = minOf(piece.bottom, rect.bottom)
            if (r > l && b > t) {
                left = minOf(left, l)
                top = minOf(top, t)
                right = maxOf(right, r)
                bottom = maxOf(bottom, b)
            }
        }
        return if (right > left && bottom > top) IntRect(left, top, right, bottom) else null
    }

    fun difference(rect: IntRect) {
        val next = mutableListOf<IntRect>()
        for (piece in rects) {
            val l = maxOf(piece.left, rect.left)
            val t = maxOf(piece.top, rect.top)
            val r = minOf(piece.right, rect.right)
            val b = minOf(piece.bottom, rect.bottom)
            if (r <= l || b <= t) {
                next += piece
                continue
            }
            if (piece.top < t) next += IntRect(piece.left, piece.top, piece.right, t)
            if (piece.bottom > b) next += IntRect(piece.left, b, piece.right, piece.bottom)
            if (piece.left < l) next += IntRect(piece.left, maxOf(piece.top, t), l, minOf(piece.bottom, b))
            if (piece.right > r) next += IntRect(r, maxOf(piece.top, t), piece.right, minOf(piece.bottom, b))
        }
        rects = next
    }
}

/** Port of the pinned walk: reverse-order visit, per-node claim, then full subtraction. */
private fun exportedBoundsByUncoveredWalk(root: SemanticsNode): Map<Int, IntRect> {
    val exported = mutableMapOf<Int, IntRect>()
    val unaccounted = UnaccountedSpace(root.touchBoundsInRoot.roundToIntRect())

    fun visit(node: SemanticsNode) {
        val touch = node.touchBoundsInRoot.roundToIntRect()
        val claimed = unaccounted.claimedBounds(touch) ?: return
        exported[node.id] = claimed
        for (child in node.children.asReversed()) {
            visit(child)
        }
        if (node.claimsSpace()) {
            unaccounted.difference(touch)
        }
    }
    visit(root)
    return exported
}

/**
 * The walk subtracts the bounds of nodes that are merging or carry any accessibility
 * key (`AccessibilityKey`s and action keys in the pinned source). This predicate covers
 * every such key present in a sheet tree: text, descriptions, pane titles and the
 * standard actions.
 */
private fun SemanticsNode.claimsSpace(): Boolean = IMPORTANT_KEYS.any { config.contains(it) }

private val IMPORTANT_KEYS =
    listOf(
        SemanticsProperties.Text,
        SemanticsProperties.ContentDescription,
        SemanticsProperties.StateDescription,
        SemanticsProperties.PaneTitle,
        SemanticsProperties.LiveRegion,
        SemanticsActions.OnClick,
        SemanticsActions.OnLongClick,
        SemanticsActions.SetText,
        SemanticsActions.ScrollBy,
        SemanticsActions.Dismiss,
        SemanticsActions.Expand,
        SemanticsActions.Collapse,
    )

private const val SCROLL_BODY_TAG = "sheet-scroll-body"
private const val TOP_CONTROL_TAG = "sheet-top-control"
private const val BODY_LINES = 24
private const val POKE_PX = 24f
private val MIN_TARGET = 48.dp
