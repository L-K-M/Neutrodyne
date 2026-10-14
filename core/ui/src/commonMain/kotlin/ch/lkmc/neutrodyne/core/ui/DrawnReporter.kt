// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * First-draw reporting (08 Feeds: Android's `ReportDrawnWhen` marks the activity fully drawn when
 * the feed stops loading). The shell provides a reporter; on the desktop the local stays `null`
 * and calls compile away.
 */
public fun interface DrawnReporter {
    /** Runs [predicate] until it first holds, then reports the frame drawn. */
    @Composable
    public fun ReportWhen(predicate: () -> Boolean)
}

/** The shell-provided [DrawnReporter]; `null` where the platform has nothing to report to. */
public val LocalDrawnReporter: androidx.compose.runtime.ProvidableCompositionLocal<DrawnReporter?> =
    staticCompositionLocalOf { null }
