// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The home's responsive decisions, taken from the window's [width] and the [usableHeight] that
 * remains after the system bars (08 Adaptive layouts: dimensions are dp after insets). Pure, so
 * the thresholds can be tested without a window.
 *
 * [compact] and [short] describe the usable height, not the raw window: the deck and the cabinet
 * are already sized from the usable height, so the transport must follow the same measure. [rows]
 * picks the slip size; [minimumHeight] is the cabinet's minimum before the deck, scaled by the
 * text size because the radio's own rule grows its controls with text.
 */
internal data class RadioLayout(
    val narrow: Boolean,
    val wide: Boolean,
    val compact: Boolean,
    val short: Boolean,
    val rows: RadioRows,
    val minimumHeight: Dp,
)

internal fun radioLayout(
    width: Dp,
    usableHeight: Dp,
    fontScale: Float,
): RadioLayout {
    val narrow = width < NARROW_WIDTH
    val wide = width >= WIDE_WIDTH
    val compact = usableHeight < COMPACT_HEIGHT
    val short = usableHeight < SHORT_HEIGHT
    val rows =
        when {
            compact -> RadioRows.COMPACT
            wide -> RadioRows.WIDE
            else -> RadioRows.STANDARD
        }
    val minimumHeight =
        when {
            wide -> 280.dp
            compact -> 440.dp
            else -> 500.dp
        } * fontScale
    return RadioLayout(narrow, wide, compact, short, rows, minimumHeight)
}

/** Below this width the cabinet uses its tighter insets. */
private val NARROW_WIDTH = 370.dp

/** From this width the home is two columns: programme left, speaker and transport right. */
private val WIDE_WIDTH = 600.dp

/** Below this usable height the home uses the compact transport and the compact slips. */
private val COMPACT_HEIGHT = 800.dp

/** Below this usable height the speaker becomes a slot and the player drops its status row. */
private val SHORT_HEIGHT = 660.dp
