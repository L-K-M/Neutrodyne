// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

actual object DateFormatter {
    private val mediumDate: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    actual fun date(epochMs: Long): String =
        mediumDate.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
