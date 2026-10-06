// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import ch.lkmc.neutrodyne.core.navigation.PaneLayout

/**
 * The shared pane directive (08 Pane directive): pane count and player side panel from the window
 * width, the navigation chrome width (96 dp for rails, 0 for bars) and the player state. The root
 * recomputes it on resize without recreating anything and publishes it as `LocalPaneLayout`.
 */
public fun ndPaneLayout(
    windowWidthDp: Int,
    navChromeDp: Int,
    hasNowPlaying: Boolean,
    panelHidden: Boolean,
): PaneLayout {
    val panel = windowWidthDp >= PANEL_MIN_WINDOW_DP && hasNowPlaying && !panelHidden
    val panelDp = if (!panel) 0 else if (windowWidthDp >= WIDE_PANEL_MIN_WINDOW_DP) WIDE_PANEL_DP else PANEL_DP
    val content = windowWidthDp - navChromeDp - panelDp
    val partitions = when {
        content >= THREE_PANE_MIN_CONTENT_DP -> 3
        content >= TWO_PANE_MIN_CONTENT_DP -> 2
        else -> 1
    }
    return PaneLayout(partitions, panel, content)
}

/** The side panel appears from 840 dp of window width (08 Desktop windows). */
private const val PANEL_MIN_WINDOW_DP = 840

/** The wide 412 dp panel is used from 1,200 dp of window width; 360 dp below. */
private const val WIDE_PANEL_MIN_WINDOW_DP = 1200
private const val PANEL_DP = 360
private const val WIDE_PANEL_DP = 412

/** Two panes start at 600 dp of content width, three at 1,200 dp (08 Pane directive). */
private const val TWO_PANE_MIN_CONTENT_DP = 600
private const val THREE_PANE_MIN_CONTENT_DP = 1200
