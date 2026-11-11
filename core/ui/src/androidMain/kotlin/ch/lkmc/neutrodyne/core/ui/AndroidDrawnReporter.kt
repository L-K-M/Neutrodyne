// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/** [DrawnReporter] over activity-compose's `ReportDrawnWhen` (08 Feeds, 09 ColdStartToFeeds). */
public object AndroidDrawnReporter : DrawnReporter {
    @Composable
    override fun ReportWhen(predicate: () -> Boolean) {
        val view = LocalView.current
        ReportDrawnWhen {
            // Activity's late-report fallback posts an animation callback without a draw.
            // Schedule a draw on that vsync so the settled content has a render frame.
            predicate().also { ready -> if (ready) view.postInvalidateOnAnimation() }
        }
    }
}
