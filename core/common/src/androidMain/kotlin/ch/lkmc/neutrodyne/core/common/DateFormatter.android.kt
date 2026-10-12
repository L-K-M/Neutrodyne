// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

actual object DateFormatter {
    private val mediumDate: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d")

    private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")

    actual fun date(epochMs: Long): String =
        mediumDate.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    actual fun dayOfMonth(epochMs: Long): String =
        DAY_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    actual fun monthShort(epochMs: Long): String =
        MONTH_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
