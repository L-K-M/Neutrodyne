// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_hour
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_hours
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_manual

/** "Manual only" / "Every hour" / "Every N hours" — row summary, dialog label and home row. */
public fun feedsIntervalText(minutes: Int): UiText =
    when (minutes) {
        FeedsSettingKeys.MANUAL_ONLY_MINUTES -> UiText.Res(Res.string.feeds_interval_manual)
        MINUTES_PER_HOUR -> UiText.Res(Res.string.feeds_interval_hour)
        else -> UiText.Res(Res.string.feeds_interval_hours, listOf(minutes / MINUTES_PER_HOUR))
    }

private const val MINUTES_PER_HOUR = 60
