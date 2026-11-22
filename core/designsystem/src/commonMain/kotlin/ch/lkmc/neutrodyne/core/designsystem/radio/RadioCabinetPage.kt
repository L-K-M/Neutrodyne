// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Where a page sits in the cabinet. Panes shown side by side share one cabinet (PO-55): the first
 * has its start wall, the last its end wall, and the walls between them meet as one partition.
 */
public enum class CabinetSpan {
    /** The page fills the cabinet. */
    ALONE,

    /** The first pane of a row. */
    LEADING,

    /** A pane between two others. */
    MIDDLE,

    /** The last pane of a row. */
    TRAILING,
}

/** Whether a page's cabinet carries the valve deck on its roof. */
public enum class CabinetDeck {
    /** The page stands alone or is the list pane: its cabinet carries the deck. */
    SHOWN,

    /**
     * A later pane beside a cabinet that already carries the deck. In a pane row it still draws
     * its slice of the row's one deck.
     */
    HIDDEN,
}

/**
 * Every page other than the radio home is built into the radio's cabinet (owner decision
 * 2026-11-17): the charcoal stage, the valve deck with its glowing tubes sitting on the cabinet
 * roof, walnut walls, and a recessed paper tray that holds [content]. Pages draw their faceplate
 * header ([ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar]) at the top of the tray.
 *
 * The frame consumes the status bar, display cutout and navigation bar insets, so a page's own
 * inset handling sees them as already applied. The deck spans the cabinet like the home radio's,
 * and stows on short windows (below [DECK_MIN_HEIGHT] of usable height) to keep the content
 * readable. [span] places the page in a row of panes that share one cabinet: they keep one roof
 * line at the deck's height, and only the row's outer walls have gaps, rounded corners and lit or
 * shaded edges.
 *
 * [dock] is the height the app reserves at the bottom of the content for the docked mini radio,
 * which is set into the cabinet's base: the tray's bottom wall grows to that height, so nothing on
 * the page hides under the bar and the page itself has nothing left to reserve. While the
 * keyboard is up the page ends at the keyboard instead, so a focused field can scroll above it.
 */
@Composable
public fun RadioCabinetPage(
    deck: CabinetDeck,
    modifier: Modifier = Modifier,
    span: CabinetSpan = CabinetSpan.ALONE,
    dock: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // A pane row stands on the root's stage, whose light falls across the whole window; a
            // stage per pane would show a seam where each pane's own light pool ends. A pane of a
            // row draws the whole row's deck and shows only its slice, so it clips itself: Material
            // stops clipping a pane while it animates the pane's bounds.
            .let { if (span == CabinetSpan.ALONE) it.radioStage() else it.clipToBounds() }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        // The deck is the cabinet's lid, as on the home: as wide as the cabinet with a little
        // overhang, its height following the width up to a share of the window's height (beyond
        // that its chassis grows longer instead). A pane row is one cabinet across the window with
        // one lid: each pane draws its own slice of it, and the row's roof line cannot depend on
        // any one pane's width, so there the deck takes the share of the height.
        val inRow = span != CabinetSpan.ALONE
        val row = rememberRowPlacement()
        val deckWidth = (if (inRow) row.width() else maxWidth) - CABINET_SIDE_GAP * 2 + DECK_OVERHANG
        val tallest = maxHeight * DECK_HEIGHT_SHARE
        val deckHeight = if (span == CabinetSpan.ALONE) minOf(deckWidth / VALVE_DECK_ASPECT, tallest) else tallest
        val roomForDeck = maxHeight >= DECK_MIN_HEIGHT
        val showDeck = roomForDeck && (deck == CabinetDeck.SHOWN || inRow)
        // Every pane of a row keeps the deck's roof line, so the row reads as one cabinet.
        val roof = if (showDeck) deckHeight * (1f - DECK_OVERLAP_SHARE) else CABINET_TOP_GAP
        if (inRow) Box(Modifier.matchParentSize().onPlaced(row::place))
        val startWall = span == CabinetSpan.ALONE || span == CabinetSpan.LEADING
        val endWall = span == CabinetSpan.ALONE || span == CabinetSpan.TRAILING
        val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val shape = cabinetShape(startWall, endWall)
        MaterialPlate(
            RadioMaterial.WOOD,
            Modifier
                .fillMaxSize()
                .padding(
                    top = roof,
                    start = if (startWall) CABINET_SIDE_GAP else 0.dp,
                    end = if (endWall) CABINET_SIDE_GAP else 0.dp,
                ).shadow(8.dp, shape)
                .clip(shape)
                .cabinetEdges(leftWall = if (ltr) startWall else endWall, rightWall = if (ltr) endWall else startWall),
        ) {
            val bottomWall = maxOf(CABINET_WALL, dock)
            MaterialPlate(
                RadioMaterial.PAPER,
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(
                        start = CABINET_WALL,
                        top = CABINET_WALL,
                        end = CABINET_WALL,
                        bottom = bottomWall,
                    ).recess(TrayShape)
                    .testTag(CABINET_TRAY_TAG),
            ) {
                Box(Modifier.fillMaxSize().aboveKeyboard(bottomWall, WindowInsets.ime)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        content()
                    }
                }
            }
        }
        if (showDeck) {
            // Drawn after the cabinet so the chassis plate rests on the roof rather than behind it.
            val placed =
                if (inRow) {
                    // This pane's slice of the row's lid, which starts at the row's left wall.
                    val overhang = DECK_OVERHANG / 2 - CABINET_SIDE_GAP
                    Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .wrapContentWidth(AbsoluteAlignment.Left, unbounded = true)
                        .width(deckWidth)
                        .absoluteOffset { IntOffset(-(row.paneLeft + overhang.toPx()).roundToInt(), 0) }
                } else {
                    Modifier.align(Alignment.TopCenter).requiredWidth(deckWidth)
                }
            // The row's single deck is tagged once, on the pane that leads the row.
            val carrier = if (inRow) span == CabinetSpan.LEADING else deck == CabinetDeck.SHOWN
            val tagged = if (carrier) placed.testTag(CABINET_DECK_TAG) else placed
            ValveDeck(tagged.height(deckHeight))
        }
    }
}

/**
 * Ends the page at the keyboard, which covers the window edge to edge. The navigation bar's inset
 * is already consumed. [bottomWall] is plain padding, so it is consumed here to not count twice.
 */
internal fun Modifier.aboveKeyboard(
    bottomWall: Dp,
    keyboard: WindowInsets,
): Modifier =
    consumeWindowInsets(PaddingValues(bottom = bottomWall))
        .windowInsetsPadding(keyboard.only(WindowInsetsSides.Bottom))

/**
 * Where a pane sits in its row, which spans the window inside its safe-drawing insets: the
 * pane's left edge in pixels from the row's, updated as the pane is placed.
 */
@Stable
private class RowPlacement(
    private val density: Density,
    private val windowWidth: Int,
    private val leftInset: Int,
    private val rightInset: Int,
) {
    var paneLeft by mutableFloatStateOf(0f)
        private set

    fun width(): Dp = with(density) { (windowWidth - leftInset - rightInset).toDp() }

    fun place(coordinates: LayoutCoordinates) {
        paneLeft = coordinates.positionInWindow().x - leftInset
    }
}

@Composable
private fun rememberRowPlacement(): RowPlacement {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize.width
    val insets = WindowInsets.safeDrawing
    // Absolute sides: the row's lid is laid out from the window's left edge in either direction.
    val left = insets.getLeft(density, LayoutDirection.Ltr)
    val right = insets.getRight(density, LayoutDirection.Ltr)
    return remember(density, window, left, right) { RowPlacement(density, window, left, right) }
}

/** Test tag of the paper tray that holds a page. */
public const val CABINET_TRAY_TAG: String = "radio_cabinet_tray"

/** Test tag of a page's valve deck. */
public const val CABINET_DECK_TAG: String = "radio_cabinet_deck"

// The deck overhangs the cabinet's walls a little, and takes at most this share of the window's
// height, as on the home.
private val DECK_OVERHANG = 18.dp
private const val DECK_HEIGHT_SHARE = 0.22f

// The chassis plate's lower edge rests on the roof: this share of the artwork's height is its
// bottom margin and shadow, which overlap the cabinet.
private const val DECK_OVERLAP_SHARE = 0.12f
private val DECK_MIN_HEIGHT = 560.dp
private val CABINET_TOP_GAP = 6.dp
private val CABINET_SIDE_GAP = 6.dp
private val CABINET_WALL = 9.dp

private fun cabinetShape(
    startWall: Boolean,
    endWall: Boolean,
): Shape =
    RoundedCornerShape(
        topStart = if (startWall) TOP_CORNER else 0.dp,
        topEnd = if (endWall) TOP_CORNER else 0.dp,
        bottomEnd = if (endWall) BOTTOM_CORNER else 0.dp,
        bottomStart = if (startWall) BOTTOM_CORNER else 0.dp,
    )

private val TOP_CORNER = 14.dp
private val BOTTOM_CORNER = 6.dp
private val TrayShape = RoundedCornerShape(4.dp)
