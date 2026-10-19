// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope

/**
 * The snackbar host every screen posts to (08 `:core:ui` inventory). `NeutrodyneRoot` provides the
 * one instance at the bottom of the content area, above the mini player.
 */
public val LocalSnackbarHost: androidx.compose.runtime.ProvidableCompositionLocal<SnackbarHostState> =
    compositionLocalOf { SnackbarHostState() }

/**
 * The coroutine scope owned by `NeutrodyneRoot`'s composition: work that must outlive the route
 * that triggered it — like the export dialog's post-pop snackbar — launches here so it survives
 * the entry's disposal but still ends with the root, instead of running `NonCancellable` on a
 * dead composition's own job.
 */
public val LocalRootScope: androidx.compose.runtime.ProvidableCompositionLocal<CoroutineScope> =
    compositionLocalOf { error("LocalRootScope is not provided by NeutrodyneRoot") }

/** Whether the Settings gear shows the update badge (08 Settings gear badge, M11a). */
public val LocalSettingsBadge: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * The height content must reserve at its bottom edge so a docked mini player does not cover it
 * (08 Insets and edge-to-edge: lists pad `navigationBars` + `LocalMiniPlayerInset`). Zero while the
 * side panel is shown or nothing plays.
 */
public val LocalMiniPlayerInset: androidx.compose.runtime.ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { 0.dp }
