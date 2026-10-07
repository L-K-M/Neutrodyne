// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.DateFormatter
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.date_days_ago
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_hours
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_hours_minutes
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_minutes
import ch.lkmc.neutrodyne.core.ui.resources.date_hours_ago
import ch.lkmc.neutrodyne.core.ui.resources.date_in_days
import ch.lkmc.neutrodyne.core.ui.resources.date_in_hours
import ch.lkmc.neutrodyne.core.ui.resources.date_in_minutes
import ch.lkmc.neutrodyne.core.ui.resources.date_in_moment
import ch.lkmc.neutrodyne.core.ui.resources.date_just_now
import ch.lkmc.neutrodyne.core.ui.resources.date_minutes_ago
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

    /** The PODCAST leading date block's parts: (day numeral, abbreviated month) — 08 EpisodeRow. */
    public fun dayMonth(publishedMs: Long): Pair<String, String> =
        DateFormatter.dayOfMonth(publishedMs) to DateFormatter.monthShort(publishedMs)

    /**
     * The local day [ms] falls in, as `LocalDate.toEpochDays()` — the bucket the feed's day
     * separators and `"d:{epochDay}"` item keys group by (08 Pages/paging/scroll memory).
     */
    public fun dayKey(ms: Long): Long =
        Instant.fromEpochMilliseconds(ms)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toEpochDays()
            .toLong()

    /**
     * Relative "… ago" label (08 Podcast detail "Updated {t}", download backoff "in {t}"): "Just
     * now" under a minute, minutes under an hour, hours under a day, days under a week, else the
     * medium localised date. Future and stale inputs clamp to "Just now" — rows show them as due.
     */
    public fun relative(
        atMs: Long,
        nowMs: Long,
    ): UiText {
        val elapsed = nowMs - atMs
        return when {
            elapsed < MS_PER_MINUTE -> UiText.Res(Res.string.date_just_now)
            elapsed < MS_PER_HOUR -> {
                val minutes = (elapsed / MS_PER_MINUTE).toInt()
                UiText.Plural(Res.plurals.date_minutes_ago, minutes, listOf(minutes))
            }
            elapsed < MS_PER_DAY -> {
                val hours = (elapsed / MS_PER_HOUR).toInt()
                UiText.Plural(Res.plurals.date_hours_ago, hours, listOf(hours))
            }
            elapsed < MS_PER_WEEK -> {
                val days = (elapsed / MS_PER_DAY).toInt()
                UiText.Plural(Res.plurals.date_days_ago, days, listOf(days))
            }
            else -> UiText.Raw(DateFormatter.date(atMs))
        }
    }

    /**
     * Forward relative label for [atMs] after [nowMs] (07's "Retrying in {t}"): "a moment" under a
     * minute, compact "5 min"/"2 h"/"3 d" spans, else the medium localised date.
     */
    public fun relativeIn(
        atMs: Long,
        nowMs: Long,
    ): UiText {
        val ahead = atMs - nowMs
        return when {
            ahead < MS_PER_MINUTE -> UiText.Res(Res.string.date_in_moment)
            ahead < MS_PER_HOUR -> {
                val minutes = (ahead / MS_PER_MINUTE).toInt()
                UiText.Plural(Res.plurals.date_in_minutes, minutes, listOf(minutes))
            }
            ahead < MS_PER_DAY -> {
                val hours = (ahead / MS_PER_HOUR).toInt()
                UiText.Plural(Res.plurals.date_in_hours, hours, listOf(hours))
            }
            ahead < MS_PER_WEEK -> {
                val days = (ahead / MS_PER_DAY).toInt()
                UiText.Plural(Res.plurals.date_in_days, days, listOf(days))
            }
            else -> UiText.Raw(DateFormatter.date(atMs))
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
    private const val MS_PER_HOUR = 3_600_000L
    private const val MS_PER_DAY = 86_400_000L
    private const val MS_PER_WEEK = 7 * MS_PER_DAY
    private const val MINUTES_PER_HOUR = 60L
}
