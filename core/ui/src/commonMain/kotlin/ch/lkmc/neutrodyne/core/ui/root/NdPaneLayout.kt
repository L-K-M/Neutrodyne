// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import ch.lkmc.neutrodyne.core.navigation.PaneLayout

/**
 * The shared pane directive (08 Pane directive): pane count from the window width and the
 * navigation chrome width (96 dp for rails, 0 for bars). The root recomputes it on resize without
 * recreating anything and publishes it as `LocalPaneLayout`. The radio home is the only full
 * player, so no side panel takes width.
 */
public fun ndPaneLayout(
    windowWidthDp: Int,
    navChromeDp: Int,
): PaneLayout {
    val content = windowWidthDp - navChromeDp
    val partitions =
        when {
            content >= THREE_PANE_MIN_CONTENT_DP -> 3
            content >= TWO_PANE_MIN_CONTENT_DP -> 2
            else -> 1
        }
    return PaneLayout(partitions, content)
}

/** Two panes start at 600 dp of content width, three at 1,200 dp (08 Pane directive). */
private const val TWO_PANE_MIN_CONTENT_DP = 600
private const val THREE_PANE_MIN_CONTENT_DP = 1200
