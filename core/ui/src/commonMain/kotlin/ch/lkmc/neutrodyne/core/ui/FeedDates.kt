// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.DateFormatter
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_hours
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_hours_minutes
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_minutes
import ch.lkmc.neutrodyne.core.ui.resources.date_today
import ch.lkmc.neutrodyne.core.ui.resources.date_weekday_fri
import ch.lkmc.neutrodyne.core.ui.resources.date_weekday_mon
import ch.lkmc.neutrodyne.core.ui.resources.date_weekday_sat
import ch.lkmc.neutrodyne.core.ui.resources.date_weekday_sun
import ch.lkmc.neutrodyne.core.ui.resources.date_weekday_thu
import ch.lkmc.neutrodyne.core.ui.resources.date_weekday_tue
import ch.lkmc.neutrodyne.core.ui.resources.date_weekday_wed
import ch.lkmc.neutrodyne.core.ui.resources.date_yesterday
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/** Feed date labels (08 Feeds day headers, EpisodeRow meta) — all `UiText` so callers stay resource-free. */
public object FeedDates {
    /** Number of days back that still get a weekday name rather than a date. */
    private const val WEEKDAY_WINDOW_DAYS = 6

    /**
     * Day-header label for [publishedMs] relative to [nowMs] (08 Feeds: "Today", "Yesterday",
     * weekday name within six days, else the medium localised date).
     */
    public fun dayLabel(publishedMs: Long, nowMs: Long): UiText {
        val zone = TimeZone.currentSystemDefault()
        val day = Instant.fromEpochMilliseconds(publishedMs).toLocalDateTime(zone).date
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val age = day.daysUntil(today)

        return when {
            age <= 0 -> UiText.Res(Res.string.date_today)
            age == 1 -> UiText.Res(Res.string.date_yesterday)
            age <= WEEKDAY_WINDOW_DAYS -> UiText.Res(weekdayRes(day))
            else -> UiText.Raw(DateFormatter.date(publishedMs))
        }
    }

    /**
     * Compact duration ("45 min", "1 h 5 min") for row meta and details. Null duration → the empty
     * raw so `Joined` drops it.
     */
    public fun duration(durationMs: Long?): UiText {
        if (durationMs == null || durationMs < 0) return UiText.Raw("")
        val minutes = durationMs / MS_PER_MINUTE
        val hours = minutes / MINUTES_PER_HOUR
        val remMinutes = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0L -> UiText.Res(Res.string.date_duration_minutes, listOf(minutes))
            remMinutes == 0L -> UiText.Res(Res.string.date_duration_hours, listOf(hours))
            else -> UiText.Res(Res.string.date_duration_hours_minutes, listOf(hours, remMinutes))
        }
    }

    private fun weekdayRes(day: LocalDate) =
        when (day.dayOfWeek) {
            kotlinx.datetime.DayOfWeek.MONDAY -> Res.string.date_weekday_mon
            kotlinx.datetime.DayOfWeek.TUESDAY -> Res.string.date_weekday_tue
            kotlinx.datetime.DayOfWeek.WEDNESDAY -> Res.string.date_weekday_wed
            kotlinx.datetime.DayOfWeek.THURSDAY -> Res.string.date_weekday_thu
            kotlinx.datetime.DayOfWeek.FRIDAY -> Res.string.date_weekday_fri
            kotlinx.datetime.DayOfWeek.SATURDAY -> Res.string.date_weekday_sat
            kotlinx.datetime.DayOfWeek.SUNDAY -> Res.string.date_weekday_sun
        }

    private const val MS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
}
