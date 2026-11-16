// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether a page's cabinet carries the valve deck on its roof. */
public enum class CabinetDeck {
    /** The page stands alone or leads a pane row: its cabinet carries the deck. */
    SHOWN,

    /** A later pane beside a cabinet that already carries the deck. */
    HIDDEN,
}

/**
 * Every page other than the radio home is built into the radio's cabinet (owner decision
 * 2026-11-17): the charcoal stage, the valve deck with its glowing tubes sitting on the cabinet
 * roof, walnut walls, and a recessed paper tray that holds [content]. Pages draw their faceplate
 * header ([ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar]) at the top of the tray.
 *
 * The frame consumes the status bar, display cutout and navigation bar insets, so a page's own
 * inset handling sees them as already applied. The deck stows on short windows (below
 * [DECK_MIN_HEIGHT] of usable height), like the home radio's deck, to keep the content readable.
 */
@Composable
public fun RadioCabinetPage(
    deck: CabinetDeck,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .radioStage()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        val showDeck = deck == CabinetDeck.SHOWN && maxHeight >= DECK_MIN_HEIGHT
        val roof = if (showDeck) DECK_HEIGHT - DECK_OVERLAP else CABINET_TOP_GAP
        MaterialPlate(
            RadioMaterial.WOOD,
            Modifier
                .fillMaxSize()
                .padding(top = roof, start = CABINET_SIDE_GAP, end = CABINET_SIDE_GAP)
                .shadow(8.dp, CabinetShape)
                .clip(CabinetShape)
                .cabinetEdges(),
        ) {
            MaterialPlate(
                RadioMaterial.PAPER,
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(CABINET_WALL)
                    .recess(TrayShape)
                    .testTag(CABINET_TRAY_TAG),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    content()
                }
            }
        }
        if (showDeck) {
            // Drawn after the cabinet so the chassis plate rests on the roof rather than behind it.
            ValveDeck(
                Modifier
                    .align(Alignment.TopCenter)
                    .height(DECK_HEIGHT)
                    .width(deckWidth(DECK_HEIGHT))
                    .testTag(CABINET_DECK_TAG),
            )
        }
    }
}

/** Test tag of the paper tray that holds a page. */
public const val CABINET_TRAY_TAG: String = "radio_cabinet_tray"

/** Test tag of a page's valve deck. */
public const val CABINET_DECK_TAG: String = "radio_cabinet_deck"

private fun deckWidth(height: Dp): Dp = height * VALVE_DECK_ASPECT

// The deck is about 11 % of a typical phone's height, as in the owner-approved renders.
private val DECK_HEIGHT = 84.dp

// The chassis plate's lower edge sits this far into the artwork's bottom margin.
private val DECK_OVERLAP = 10.dp
private val DECK_MIN_HEIGHT = 560.dp
private val CABINET_TOP_GAP = 6.dp
private val CABINET_SIDE_GAP = 6.dp
private val CABINET_WALL = 9.dp
private val CabinetShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
private val TrayShape = RoundedCornerShape(4.dp)
