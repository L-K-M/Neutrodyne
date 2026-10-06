// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.log

import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * How a log entry becomes one text line — the same format [RollingFileSink] writes and
 * [ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter] quotes: `2026-10-06T14:03:22.123Z
 * INFO Tag message` plus the throwable's redacted stack.
 */
internal fun formatLogLine(
    level: LogLevel,
    tag: String,
    message: String,
    at: Instant,
): String {
    val builder =
        StringBuilder(TIMESTAMP_FORMAT.format(at))
            .append(' ')
            .append(level.name)
            .append(' ')
            .append(tag)
            .append(' ')
            .append(message)
    return builder.toString()
}

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
