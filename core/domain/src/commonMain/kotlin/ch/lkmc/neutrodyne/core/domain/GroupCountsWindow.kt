// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.GroupCounts

/** Hour-rounded lower bound shared by feed badges and Library group tiles (05 Counts). */
fun groupCountsWindowStart(nowMs: Long): Long =
    nowMs - nowMs.mod(FeedPrefs.HOUR_MS) - GroupCounts.WINDOW_DAYS * GroupCounts.DAY_MS
