// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable

/** [DrawnReporter] over activity-compose's `ReportDrawnWhen` (08 Feeds, 09 ColdStartToFeeds). */
public object AndroidDrawnReporter : DrawnReporter {
    @Composable
    override fun reportWhen(predicate: () -> Boolean) {
        ReportDrawnWhen(predicate = predicate)
    }
}
