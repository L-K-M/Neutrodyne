// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/** The adaptive layout the root currently renders: pane count, the player side panel, width. */
public data class PaneLayout(
    val partitions: Int,
    val playerPanel: Boolean,
    val contentWidthDp: Int,
)

/** The current [PaneLayout], provided by `NeutrodyneRoot`. Changes only on resize or panel change. */
public val LocalPaneLayout: androidx.compose.runtime.ProvidableCompositionLocal<PaneLayout> =
    staticCompositionLocalOf { PaneLayout(partitions = 1, playerPanel = false, contentWidthDp = 360) }

/** The tab whose stack the current entry belongs to, provided per entry by the shared host. */
public val LocalNavTab: androidx.compose.runtime.ProvidableCompositionLocal<TopLevelKey> =
    staticCompositionLocalOf<TopLevelKey> { FeedsKey }
